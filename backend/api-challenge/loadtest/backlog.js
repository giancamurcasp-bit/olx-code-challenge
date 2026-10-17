// Backlog drain: a burst of creates, then the time until moderation clears it. The API absorbs the
// burst in seconds; the worker drains it at ~2.3 ads/s, held back by the provider's quota (DECISIONS.md 11).
// Fails on any 5xx and when the drain is slower than MIN_RATE ads/s (default 2; measured ~2.4).
// The script waits up to twice that long: the ads still pending then count as moderation timeouts.
// Start from an empty database: any pending ad, not only the burst, keeps the backlog open.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, COMMON_THRESHOLDS, createAd, moderationTimeouts, track } from './lib.js';

const ADS = Number(__ENV.ADS || 500);
const MIN_RATE = Number(__ENV.MIN_RATE || 2); // ads/s the drain must sustain
const DRAIN_SLO_S = ADS / MIN_RATE;
const DEADLINE_S = 2 * DRAIN_SLO_S;

const drainSeconds = new Trend('backlog_drain_seconds');

export const options = {
  scenarios: {
    burst: { executor: 'shared-iterations', vus: Math.min(50, ADS), iterations: ADS, exec: 'burst' },
    drain: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      exec: 'drain',
      maxDuration: `${Math.ceil(DEADLINE_S + 60)}s`, // the drain stops itself at DEADLINE_S
    },
  },
  thresholds: {
    ...COMMON_THRESHOLDS,
    'http_req_duration{name:POST /ads}': ['p(95)<200'],
    backlog_drain_seconds: [`max<${DRAIN_SLO_S}`],
    moderation_timeouts: ['count==0'],
  },
};

export function burst() {
  createAd(__ITER);
}

/**
 * Polls for pending ads until the backlog has been seen and is empty again. A failed poll proves
 * nothing either way: it counts as an error and polling goes on.
 */
export function drain() {
  const start = Date.now();
  let seen = false;
  while ((Date.now() - start) / 1000 < DEADLINE_S) {
    const res = pollPending('status=pending&limit=1');
    if (res.status === 200) {
      const pending = res.json().data.length > 0;
      seen = seen || pending;
      if (seen && !pending) {
        const seconds = (Date.now() - start) / 1000;
        drainSeconds.add(seconds);
        console.log(`Backlog of ${ADS} ads drained in ${seconds.toFixed(1)}s (${(ADS / seconds).toFixed(2)} ads/s)`);
        return;
      }
    }
    sleep(1);
  }
  const left = countPending();
  moderationTimeouts.add(Math.max(left, 1)); // a backlog never seen (every create failed) also fails
  console.error(`Backlog not drained after ${DEADLINE_S}s: ${left} ads still pending`);
}

/** Pages through the pending ads to count them. */
function countPending() {
  let count = 0;
  let cursor = null;
  do {
    const res = pollPending(`status=pending&limit=50${cursor ? `&cursor=${cursor}` : ''}`);
    if (res.status !== 200) {
      return count;
    }
    count += res.json().data.length;
    cursor = res.json().next_cursor;
  } while (cursor);
  return count;
}

function pollPending(query) {
  const res = track(http.get(`${BASE_URL}/ads?${query}`, { tags: { name: 'poll pending' } }), 'poll pending');
  check(res, { 'poll is 200': (r) => r.status === 200 });
  return res;
}
