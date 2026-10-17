// Create ramp: how far does POST /ads scale? Moderation is async, so this only measures the API
// and the database (one insert per request). Every ad joins the moderation backlog, drained at
// ~2.3 ads/s: the default run (~20k ads) keeps the worker busy for hours, so `docker compose down -v`
// afterwards, or try a smaller run first: -e PEAK=50 -e RAMP=20s -e HOLD=20s.
import { COMMON_THRESHOLDS, createAd } from './lib.js';

const PEAK = Number(__ENV.PEAK || 200);

export const options = {
  scenarios: {
    create: {
      executor: 'ramping-arrival-rate',
      startRate: Math.min(10, PEAK),
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 300,
      stages: [
        { target: PEAK, duration: __ENV.RAMP || '1m' }, // ramp to PEAK creates/s
        { target: PEAK, duration: __ENV.HOLD || '1m' },
        { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds: {
    ...COMMON_THRESHOLDS,
    'http_req_duration{name:POST /ads}': ['p(95)<200'],
  },
};

export default function () {
  createAd(__ITER);
}
