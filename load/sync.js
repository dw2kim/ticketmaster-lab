// Step 10: how long until a change is searchable in Elasticsearch, and is any change lost?
//
// 5 renames per second of random events, for 60 s, whatever happens to Elasticsearch. After
// each rename, a checker asks Elasticsearch DIRECTLY (not the app, which would fall back to
// Postgres) every 200 ms until the new name is found. Found -> record the lag. Not found
// within 90 s -> count it as lost.
//
// Run (Mac / Docker Desktop):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e ES_URL=http://host.docker.internal:9200 grafana/k6 run - < load/sync.js
// Run (Linux): use --network host instead of the two URLs.
// Look at: sync_lag_ms (p50, p95, max) and changes_lost.

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ES_URL = __ENV.ES_URL || 'http://localhost:9200';
const GIVE_UP_MS = Number(__ENV.GIVE_UP_S || 90) * 1000;

const lag = new Trend('sync_lag_ms', true);
const synced = new Counter('changes_synced');
const lost = new Counter('changes_lost');

export const options = {
  scenarios: {
    editors: {
      executor: 'constant-arrival-rate', // a new rename every 200 ms, even while checkers wait
      rate: 5, timeUnit: '1s', duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 100, maxVUs: 600, gracefulStop: '120s',
    },
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'max'],
};

function randomWord() {
  const letters = 'abcdefghijklmnopqrstuvwxyz';
  let w = 'zz';
  for (let i = 0; i < 8; i++) w += letters[Math.floor(Math.random() * 26)];
  return w;
}

export default function () {
  const id = 3 + Math.floor(Math.random() * 100000); // one of the seeded events
  const word = randomWord();                          // unique, so we know when it shows up
  const put = http.put(`${BASE_URL}/events/${id}`, JSON.stringify({ name: `${word} Show` }),
    { headers: { 'Content-Type': 'application/json' } });
  if (put.status !== 200) return;

  const start = Date.now();
  const query = JSON.stringify({ query: { match: { name: word } }, size: 1 });
  while (Date.now() - start < GIVE_UP_MS) {
    const res = http.post(`${ES_URL}/events/_search`, query, { headers: { 'Content-Type': 'application/json' } });
    if (res.status === 200 && res.json('hits.total.value') > 0) {
      lag.add(Date.now() - start);
      synced.add(1);
      return;
    }
    sleep(0.2);
  }
  lost.add(1);
}
