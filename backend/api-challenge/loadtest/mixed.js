// Realistic mix at a constant 100 req/s: 80% listing (following next_cursor), 15% detail, 5% create.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, CATEGORIES, COMMON_THRESHOLDS, createAd, getAd, track } from './lib.js';

export const options = {
  scenarios: {
    mixed: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    ...COMMON_THRESHOLDS,
    'http_req_duration{name:GET /ads}': ['p(95)<200'],
    'http_req_duration{name:GET /ads/{id}}': ['p(95)<100'],
  },
};

/**
 * Ids of existing ads for the detail requests, taken from the listing: ids have gaps (an identity
 * sequence skips the values of conflicting inserts), so random numbers would hit 404s.
 */
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

export default function (data) {
  const roll = Math.random();
  if (roll < 0.8) {
    list();
  } else if (roll < 0.95) {
    getAd(data.ids[Math.floor(Math.random() * data.ids.length)]);
  } else {
    createAd(__ITER);
  }
}

/** First page, filtered by category half of the time, then up to two more pages. */
function list() {
  let url = `${BASE_URL}/ads?limit=20`;
  if (Math.random() < 0.5) {
    url += `&category=${CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)]}`;
  }
  let cursor = null;
  for (let page = 0; page < 3; page++) {
    const res = track(http.get(cursor ? `${url}&cursor=${cursor}` : url, { tags: { name: 'GET /ads' } }), 'GET /ads');
    check(res, { 'list is 200': (r) => r.status === 200 });
    cursor = res.status === 200 ? res.json().next_cursor : null;
    if (!cursor || Math.random() < 0.5) {
      return;
    }
  }
}
