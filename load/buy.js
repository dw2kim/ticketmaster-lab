// Step 3: 200 buyers, 20 seats, all at the same moment.
//
// Each buyer does what a real person does:
//   1. look at the seat map (GET /events/1)
//   2. pick a random seat that looks free
//   3. try to book it (POST /bookings/1)
//
// Run (Mac / Docker Desktop):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < load/buy.js
// Run (Linux):
//   docker run --rm -i --network host grafana/k6 run - < load/buy.js

import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BUYERS = Number(__ENV.BUYERS || 200);

const toldYes = new Counter('buyers_told_you_got_it'); // 201
const toldNo = new Counter('buyers_told_sold_out');    // 409, or no free seat on the map
const errors = new Counter('buyers_got_error');        // anything else

export const options = {
  scenarios: {
    rush: {
      executor: 'per-vu-iterations', // every buyer starts at once...
      vus: BUYERS,
      iterations: 1,                 // ...and tries exactly once
    },
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'max'],
};

export default function () {
  const user = `buyer-${__VU}`;

  // 1. look
  const map = http.get(`${BASE_URL}/events/1`);
  if (map.status !== 200) {
    errors.add(1);
    return;
  }
  const free = map.json('tickets').filter((t) => t.status === 'available');
  if (free.length === 0) {
    toldNo.add(1);
    return;
  }

  // 2. pick
  const seat = free[Math.floor(Math.random() * free.length)];

  // 3. book
  const res = http.post(
    `${BASE_URL}/bookings/1`,
    JSON.stringify({ ticketIds: [seat.id] }),
    { headers: { 'Content-Type': 'application/json', 'X-User-Id': user } },
  );

  if (res.status === 201) toldYes.add(1);
  else if (res.status === 409) toldNo.add(1);
  else errors.add(1);
}
