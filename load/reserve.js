// Step 5: 200 buyers try to RESERVE (hold) seats at the same moment. 20 seats.
// Same shape as buy.js, but calls POST /bookings/reserve.
//
// Run (Mac / Docker Desktop):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/reserve.js
// Run (Linux):
//   docker run --rm -i --network host grafana/k6 run - < load/reserve.js

import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BUYERS = Number(__ENV.BUYERS || 200);

const held = new Counter('buyers_got_a_hold');   // 201
const toldNo = new Counter('buyers_told_no');    // 409, or no free seat on the map
const errors = new Counter('buyers_got_error');  // anything else

export const options = {
  scenarios: { rush: { executor: 'per-vu-iterations', vus: BUYERS, iterations: 1 } },
  summaryTrendStats: ['p(50)', 'p(95)', 'max'],
};

export default function () {
  const map = http.get(`${BASE_URL}/events/1`);
  if (map.status !== 200) { errors.add(1); return; }
  const free = map.json('tickets').filter((t) => t.status === 'available');
  if (free.length === 0) { toldNo.add(1); return; }

  const seat = free[Math.floor(Math.random() * free.length)];
  const res = http.post(
    `${BASE_URL}/bookings/reserve`,
    JSON.stringify({ eventId: 1, ticketIds: [seat.id] }),
    { headers: { 'Content-Type': 'application/json', 'X-User-Id': `buyer-${__VU}` } },
  );
  if (res.status === 201) held.add(1);
  else if (res.status === 409) toldNo.add(1);
  else errors.add(1);
}
