// Step 7: many people refresh the same event page. 200 viewers, 30 seconds, as fast as they can.
//
// Run (Mac / Docker Desktop):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/view.js
// Run (Linux): use --network host instead of BASE_URL.
// Look at: http_reqs (requests per second) and http_req_duration (p50, p95, p99).

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    refresh: { executor: 'constant-vus', vus: Number(__ENV.VIEWERS || 200), duration: __ENV.DURATION || '30s' },
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE_URL}/events/1`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
