// Production-like traffic: 100 users at once, most of them reading. Three kinds of users run in
// parallel, each pausing between actions like a person (think time), so the load comes from
// behaviour, not from a fixed request rate:
//   - browsers (70): open the feed, filter by category, page through it and open some ads;
//   - link visitors (15): open an ad straight from a shared link, sometimes the feed after it;
//   - sellers (15): create an ad and poll it until moderation ends, like the app does. Now and
//     then a seller retries with the same Idempotency-Key (a network retry) or sends an invalid ad.
// Fails on any 5xx, on moderation slower than 60s (p95) and on an ad still pending after 2 min.
// Sellers create about one ad every 30s each (~0.5/s in total), below the moderation capacity
// (~2.2–2.4 ads/s, DECISIONS.md 11), so the backlog stays small, as it should in production.
// Run: docker compose run --rm k6 run /scripts/production.js   (-e HOLD=10m for a longer run)
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import {
  BASE_URL,
  CATEGORIES,
  COMMON_THRESHOLDS,
  MODERATION_THRESHOLDS,
  awaitModeration,
  getAd,
  newAd,
  track,
} from './lib.js';

const HOLD = __ENV.HOLD || '5m';

/** A seller stops waiting after this long; the ad then counts as a moderation timeout. */
const MODERATION_TIMEOUT_MS = 120000;

/** Ramps users up and down like a traffic peak, scaled to each kind's share of the 100. */
function users(target) {
  return {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { target, duration: '1m' },
      { target, duration: HOLD },
      { target: 0, duration: '30s' },
    ],
    gracefulRampDown: '30s',
  };
}

export const options = {
  scenarios: {
    browsers: { ...users(70), exec: 'browser' },
    linkVisitors: { ...users(15), exec: 'linkVisitor' },
    // Sellers get time to finish following their ad when the run ends, so no followed ad is cut off
    // uncounted: each ends in moderation_time or moderation_timeouts.
    sellers: { ...users(15), exec: 'seller', gracefulRampDown: '3m', gracefulStop: '3m' },
  },
  thresholds: {
    ...COMMON_THRESHOLDS,
    ...MODERATION_THRESHOLDS,
    'http_req_duration{name:GET /ads}': ['p(95)<200'],
    'http_req_duration{name:GET /ads/{id}}': ['p(95)<100'],
    'http_req_duration{name:POST /ads}': ['p(95)<300'],
  },
};

/** Ids of existing ads, for visitors arriving from a shared link. */
export function setup() {
  const ids = [];
  let cursor = null;
  for (let page = 0; page < 10; page++) {
    const res = http.get(`${BASE_URL}/ads?limit=50${cursor ? `&cursor=${cursor}` : ''}`);
    ids.push(...res.json().data.map((ad) => ad.id));
    cursor = res.json().next_cursor;
    if (!cursor) break;
  }
  return { ids };
}

const pick = (items) => items[Math.floor(Math.random() * items.length)];
const think = (min, max) => sleep(min + Math.random() * (max - min));

function listAds(query) {
  const res = track(http.get(`${BASE_URL}/ads?${query}`, { tags: { name: 'GET /ads' } }), 'GET /ads');
  check(res, { 'list is 200': (r) => r.status === 200 });
  return res.status === 200 ? res.json() : { data: [], next_cursor: null };
}

/** A session on the feed: up to 4 pages, opening about a third of the pages' ads. */
export function browser() {
  group('browse', () => {
    let query = 'limit=20';
    if (Math.random() < 0.5) {
      query += `&category=${pick(CATEGORIES)}`;
    }
    let page = listAds(query);
    for (let i = 0; i < 4 && page.data.length > 0; i++) {
      think(1, 3); // reading the page
      if (Math.random() < 0.35) {
        getAd(pick(page.data).id);
        think(2, 5); // looking at the ad
      }
      if (!page.next_cursor || Math.random() < 0.4) {
        break; // most users don't scroll far
      }
      page = listAds(`${query}&cursor=${page.next_cursor}`);
    }
  });
  think(3, 8); // between sessions
}

/** Arrives on an ad from a shared link; a few then look at the feed. */
export function linkVisitor(data) {
  group('shared link', () => {
    getAd(pick(data.ids));
    think(3, 8);
    if (Math.random() < 0.3) {
      listAds('limit=20');
    }
  });
  think(2, 6);
}

/** Creates an ad and follows it until moderation decides, like the app polling Location. */
export function seller() {
  const roll = Math.random();
  if (roll < 0.05) {
    invalidAd();
  } else {
    group('sell', () => sell(roll < 0.1));
  }
  think(20, 40); // writing the next ad
}

function sell(retryWithSameKey) {
  const key = `seller-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify(newAd(Math.floor(Math.random() * 1e6)));
  const params = {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    tags: { name: 'POST /ads' },
  };
  const start = Date.now();
  const res = track(http.post(`${BASE_URL}/ads`, body, params), 'POST /ads');
  if (!check(res, { 'create is 201': (r) => r.status === 201 })) {
    return;
  }
  if (retryWithSameKey) {
    // A client that timed out and retries: same key, same ad back, no duplicate.
    const retry = track(http.post(`${BASE_URL}/ads`, body, params), 'POST /ads');
    check(retry, {
      'retry returns the same ad': (r) =>
        r.status === 201 && r.json().id === res.json().id && r.headers['Idempotent-Replayed'] === 'true',
    });
  }
  awaitModeration(res.json().id, start, MODERATION_TIMEOUT_MS);
}

/** A client bug: price 0. Expected to be refused with 400, so it doesn't count as a failure. */
function invalidAd() {
  const ad = { ...newAd(0), price: 0 };
  const res = track(
    http.post(`${BASE_URL}/ads`, JSON.stringify(ad), {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `invalid-${__VU}-${Date.now()}` },
      tags: { name: 'POST /ads (invalid)' },
      responseCallback: http.expectedStatuses(400),
    }),
    'POST /ads (invalid)',
  );
  check(res, { 'invalid ad is 400': (r) => r.status === 400 });
}
