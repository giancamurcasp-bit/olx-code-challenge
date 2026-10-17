import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const CATEGORIES = ['eletronicos', 'moveis', 'veiculos', 'imoveis', 'esportes'];

/** The moderation criterion: a seller waits at most this long for a decision (p95). */
export const MODERATION_SLO_MS = 60000;

/**
 * Responses with a 5xx status or none at all (connection error, timeout). Every script holds it at
 * zero: unlike http_req_failed, which tolerates 1% failures, a server error always fails the run.
 */
export const serverErrors = new Counter('server_errors');

/** Time from POST /ads until the ad leaves pending, for the ads a script follows. */
export const moderationTime = new Trend('moderation_time', true);

/** Followed ads that moderation decided. Guards against a run where none was: an empty Trend passes. */
export const adsModerated = new Counter('ads_moderated');

/** Followed ads still pending when the script stopped waiting for them. */
export const moderationTimeouts = new Counter('moderation_timeouts');

/** Thresholds every script shares: no server errors, and under 1% of other unexpected responses. */
export const COMMON_THRESHOLDS = {
  server_errors: ['count==0'],
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
};

/** Thresholds for the scripts that follow ads until moderated. */
export const MODERATION_THRESHOLDS = {
  moderation_time: [`p(95)<${MODERATION_SLO_MS}`],
  ads_moderated: ['count>0'],
  moderation_timeouts: ['count==0'],
};

/** Counts a 5xx or a missing response in server_errors, tagged with the request name. */
export function track(res, name) {
  if (res.status === 0 || res.status >= 500) {
    serverErrors.add(1, { name, status: String(res.status) });
  }
  return res;
}

/** One ad in five has contact info in the title, so moderation rejects it. */
export function newAd(n) {
  const rejected = n % 5 === 0;
  return {
    title: rejected ? `Celular novo, chama no whatsapp ${n}` : `Sofa retratil 3 lugares ${n}`,
    description: 'Bom estado, pouco uso.',
    price: 900,
    category: 'moveis',
    images: ['https://example.com/loadtest.jpg'],
  };
}

/** POST /ads with a fresh Idempotency-Key; returns the created ad, or null. */
export function createAd(n) {
  const res = track(
    http.post(`${BASE_URL}/ads`, JSON.stringify(newAd(n)), {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': `loadtest-${Date.now()}-${n}-${Math.random()}`,
      },
      tags: { name: 'POST /ads' },
    }),
    'POST /ads',
  );
  check(res, { 'create is 201': (r) => r.status === 201 });
  return res.status === 201 ? res.json() : null;
}

export function getAd(id) {
  const res = track(http.get(`${BASE_URL}/ads/${id}`, { tags: { name: 'GET /ads/{id}' } }), 'GET /ads/{id}');
  check(res, { 'detail is 200': (r) => r.status === 200 });
  return res;
}

/**
 * Polls an ad every 2s until moderation decides, as the app does with Location. Returns the final
 * status, or null if the ad is still pending after timeoutMs (counted in moderation_timeouts). A
 * failed poll is an error (server_errors, checks), not a pending ad, and polling goes on.
 */
export function awaitModeration(id, start, timeoutMs, tags = {}) {
  while (Date.now() - start < timeoutMs) {
    sleep(2);
    const res = getAd(id);
    if (res.status !== 200) {
      continue;
    }
    const status = res.json().status;
    if (status !== 'pending') {
      moderationTime.add(Date.now() - start, tags);
      adsModerated.add(1, { ...tags, status });
      return status;
    }
  }
  moderationTimeouts.add(1, tags);
  return null;
}
