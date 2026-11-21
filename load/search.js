// Step 8: 50 people search at the same time for 30 seconds, with a mix of keywords:
// common words (jazz: ~10,000 events), rarer ones (Sparrows: 5,000), and no match (Taylor).
//
// Run (Mac / Docker Desktop):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/search.js
// Run (Linux): use --network host instead of BASE_URL.
// Goal from the requirements: search under 500 ms. Look at http_req_duration p95 / p99.

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WORDS = ['jazz', 'Sparrows', 'Moonrise', 'Boston', 'comedy', 'Golden', 'festival', 'Taylor'];

export const options = {
  scenarios: {
    search: { executor: 'constant-vus', vus: Number(__ENV.USERS || 50), duration: __ENV.DURATION || '30s' },
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const word = WORDS[Math.floor(Math.random() * WORDS.length)];
  const res = http.get(`${BASE_URL}/events/search?keyword=${word}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
