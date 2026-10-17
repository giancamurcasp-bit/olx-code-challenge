// Moderation capacity: production-like readers in the background while the creation rate climbs
// in steps (0.5, 1, 2, 3 and 4 ads/s, 2 min each). Below capacity a seller waits a few seconds for
// moderation; above it the backlog grows and the wait climbs with every step.
// Each step is held to the moderation criterion (p95 under 60s, no ad still pending after 10 min),
// so the summary marks the steps the system sustains with ✓ and the first one it doesn't with ✗.
// With the default steps the run is expected to fail from 3 ads/s on (capacity ~2.2–2.4 ads/s:
// DECISIONS.md, 11); -e RATES=30,60,120 (ads per minute) runs only the steps that must pass.
// One ad in five is followed until moderated (sampling, so a growing backlog doesn't need hundreds
// of polling users); the run ends once those ads are moderated or timed out.
// Run: docker compose run --rm k6 run /scripts/capacity.js   (~14 min)
import exec from 'k6/execution';
import { COMMON_THRESHOLDS, MODERATION_SLO_MS, awaitModeration, createAd } from './lib.js';
import { browser, linkVisitor, setup as findAds } from './production.js';

const STEP = 120; // seconds per step
const RATES = (__ENV.RATES || '30,60,120,180,240').split(',').map(Number); // ads per minute
const FOLLOW_TIMEOUT_MS = 600000; // above capacity the wait grows; past 10 min the ad is a timeout

const stepTag = (rate) => `${rate / 60}/s`;

export const options = {
  scenarios: {
    browsers: { executor: 'constant-vus', vus: 70, duration: `${RATES.length * STEP}s`, exec: 'browser' },
    linkVisitors: { executor: 'constant-vus', vus: 15, duration: `${RATES.length * STEP}s`, exec: 'linkVisitor' },
    sellers: {
      executor: 'ramping-arrival-rate',
      exec: 'seller',
      timeUnit: '1m',
      startRate: RATES[0],
      preAllocatedVUs: 50,
      maxVUs: 500,
      stages: RATES.flatMap((rate) => [
        { target: rate, duration: '5s' },
        { target: rate, duration: `${STEP - 5}s` },
      ]),
      gracefulStop: '11m', // longer than FOLLOW_TIMEOUT_MS: every followed ad is moderated or timed out
    },
  },
  // One threshold per step, so the summary shows moderation_time and timeouts for each creation rate.
  thresholds: Object.fromEntries([
    ...Object.entries(COMMON_THRESHOLDS),
    ['ads_moderated', ['count>0']],
    ...RATES.flatMap((rate) => [
      [`moderation_time{rate:${stepTag(rate)}}`, [`p(95)<${MODERATION_SLO_MS}`]],
      [`moderation_timeouts{rate:${stepTag(rate)}}`, ['count==0']],
    ]),
  ]),
};

export function setup() {
  return findAds();
}

export { browser, linkVisitor };

/** Creates an ad; one in five is followed until moderation decides, tagged with its step's rate. */
export function seller() {
  const step = Math.min(Math.floor(exec.instance.currentTestRunDuration / 1000 / STEP), RATES.length - 1);
  const start = Date.now();
  const ad = createAd(exec.scenario.iterationInTest);
  if (ad && Math.random() < 0.2) {
    awaitModeration(ad.id, start, FOLLOW_TIMEOUT_MS, { rate: stepTag(RATES[step]) });
  }
}
