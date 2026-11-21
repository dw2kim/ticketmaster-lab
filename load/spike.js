// Step 6: flash sale. 10,000 buyers arrive at the same moment for 20 seats.
//
//   QUEUE=0  everyone goes straight to the seat map and tries to reserve (app: queue.enabled=false)
//   QUEUE=1  everyone joins the waiting room first, waits to be let in, then reserves
//            (app: queue.enabled=true)
//
// Run (Mac / Docker Desktop):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e QUEUE=0 grafana/k6 run - < load/spike.js
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e QUEUE=1 grafana/k6 run - < load/spike.js
// Run (Linux): use --network host instead of BASE_URL.

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BUYERS = Number(__ENV.BUYERS || 10000);
const QUEUE = __ENV.QUEUE === '1';

const held = new Counter('buyers_got_a_hold');        // 201 from reserve
const toldNo = new Counter('buyers_told_no');         // 409, or no free seat on the map
const errors = new Counter('buyers_got_error');       // 5xx, timeouts, refused connections
const bookingPath = new Trend('booking_path_ms', true); // seat map + reserve, after any waiting
const waited = new Trend('queue_wait_s');              // seconds in the waiting room

export const options = {
  scenarios: { flash_sale: { executor: 'per-vu-iterations', vus: BUYERS, iterations: 1, maxDuration: '10m' } },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: false,
};

export default function () {
  const user = `buyer-${__VU}`;
  const headers = { 'Content-Type': 'application/json', 'X-User-Id': user };

  if (QUEUE) {
    const start = Date.now();
    let spot = http.post(`${BASE_URL}/events/1/queue`, null, { headers });
    while (spot.status === 200 && !spot.json('admitted')) {
      // wait as long as the server suggests (1 to 5 s), plus jitter so polls spread out
      const suggested = Math.min(5, Math.max(1, spot.json('estimatedWaitSeconds')));
      sleep(suggested + Math.random());
      spot = http.get(`${BASE_URL}/events/1/queue`, { headers });
    }
    if (spot.status !== 200) { errors.add(1); return; }
    waited.add((Date.now() - start) / 1000);
  }

  const t0 = Date.now();
  const map = http.get(`${BASE_URL}/events/1`);
  if (map.status !== 200) { errors.add(1); bookingPath.add(Date.now() - t0); return; }
  const free = map.json('tickets').filter((t) => t.status === 'available');
  if (free.length === 0) { toldNo.add(1); bookingPath.add(Date.now() - t0); return; }

  const seat = free[Math.floor(Math.random() * free.length)];
  const res = http.post(`${BASE_URL}/bookings/reserve`,
    JSON.stringify({ eventId: 1, ticketIds: [seat.id] }), { headers });
  bookingPath.add(Date.now() - t0);

  if (res.status === 201) held.add(1);
  else if (res.status === 409) toldNo.add(1);
  else errors.add(1);
}
