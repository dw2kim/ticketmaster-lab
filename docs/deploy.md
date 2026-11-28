# Deploy to Render (without Elasticsearch)

Put the app on the internet: the Spring Boot app, a managed Postgres and a managed Redis,
all on [Render](https://render.com). Search uses Postgres full-text (step 8), so
Elasticsearch is not needed. Why Render: [ADR-0011](decisions/0011-deploy-on-render.md).

## The picture

```
 you: git push --> GitHub --(CI: tests + docker build)--> main
                                                          |
                                         Render builds the Dockerfile, then deploys
                                                          v
 browser / curl --HTTPS--> ticketmaster-app (Docker, port from $PORT)
                              |                     |
                    private network only   private network only
                              v                     v
                     ticketmaster-db         ticketmaster-redis
                     (Postgres, the record)  (Key Value: holds, cache, queue)
```

## The files

| File | What it does |
| --- | --- |
| `Dockerfile` | Builds the app image in two stages: JDK + Maven to build, small JRE to run. Runs as a non-root user. |
| `.dockerignore` | Keeps `target/`, `.git/`, `docs/`, `load/` out of the image build. |
| `render.yaml` | The Render "Blueprint": app, Postgres and Redis in one file (infrastructure as code). |
| `.github/workflows/ci.yml` | GitHub Actions: on every PR and push to main, run all tests and build the image. |
| `application.properties` | Reads `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `REDIS_URL`, `PORT` from the environment. Defaults are your laptop (docker compose), so local runs do not change. |

The app exposes `GET /actuator/health`. Render calls it to decide if a new version is
healthy before sending traffic to it. It says `UP` when Postgres is reachable. Redis is left
out on purpose: the app keeps working without it (cache fallback, step 7).

## Cost (check https://render.com/pricing, plans change)

`render.yaml` uses the **free** plans: $0. The catches:

- the app sleeps after 15 minutes idle; the next request takes about a minute to wake it
- free Postgres is deleted after about 30 days (one free database per account)
- free Redis keeps nothing on restart (fine: it only has holds, cache and the queue)

Fine for a demo. For an always-on deploy, change the `plan:` lines to paid plans:

| Piece | Paid plan | About |
| --- | --- | --- |
| App (web service) | `starter` (512 MB) | $7 / month |
| Postgres | `basic-256mb` | $6 / month |
| Redis (Key Value) | `starter` | $10 / month |
| | **Total** | **about $23 / month** |

**Before you click Apply, read the plan next to each line on Render's page.** Render creates
exactly what it shows there, and paid plans start billing at once.

## How to run it

### 1. Check the image builds on your laptop (optional)

Terminal 2, folder `ticketmaster-lab`, branch `main`:

```bash
git pull
docker build -t ticketmaster-lab .
```

Expected: the last lines say `naming to docker.io/library/ticketmaster-lab` (first build
takes a few minutes: it downloads Maven dependencies).

Run that image against your local Postgres and Redis, on port 10000 like Render does.
Terminal 1 (stop `./mvnw spring-boot:run` first), same folder:

```bash
docker compose up -d
docker run --rm -p 10000:10000 -e PORT=10000 -e DB_HOST=host.docker.internal -e REDIS_URL=redis://host.docker.internal:6379 ticketmaster-lab
```

Expected: Spring starts and logs `Tomcat started on port 10000`.

Terminal 2:

```bash
curl -s localhost:10000/actuator/health
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}`. Stop Terminal 1 with Ctrl+C.

### 2. Create everything on Render (browser, one time)

1. Sign up at <https://dashboard.render.com> with your GitHub account.
2. Click **+ New** (top right) -> **Blueprint**. Not the cards on the "Create a new ..."
   page: each card makes only one piece by hand.
3. Pick the repo `ticketmaster-lab`. On the next page:
   - **Blueprint Name**: `ticketmaster-lab` (required; until it is filled the page says
     "A Blueprint file was found, but there were a few issues")
   - **Branch**: `main`, **Blueprint Path**: leave `render.yaml`
4. It lists "Create database ticketmaster-db", "Create Key Value ticketmaster-redis",
   "Create web service ticketmaster-app". **This page does not show the plans.** The plans
   come from `render.yaml` (all `free`). Click **Deploy Blueprint**.
5. The sync page now shows each one with its plan in brackets, e.g.
   `Create database ticketmaster-db (Free)`. **If any says a paid plan** (e.g.
   `(Basic-256mb)`, `(Starter)`), delete it right away (see "Delete everything" below).
6. Wait about 5 minutes. The Dashboard then shows (our run, 2026-11-28):

```
ticketmaster-app     Deployed    Docker         Oregon
ticketmaster-db      Available   PostgreSQL 18  Oregon
ticketmaster-redis   Available   Valkey 8       Oregon
```

   Render picks the versions: PostgreSQL 18 (we use 17 locally) and Valkey 8 (an open-source
   fork of Redis, same commands). The app and all tests work on both.
7. Open `ticketmaster-app` and copy the URL at the top, like
   `https://ticketmaster-app-xxxx.onrender.com`.

### 3. Check it from your laptop

Terminal 2, any folder. Replace the URL with yours. These outputs are from our run.
On the free plan the app sleeps when idle: the first call may take about a minute.

```bash
APP=https://ticketmaster-app-xxxx.onrender.com
```

```bash
curl -s $APP/actuator/health
```

```
{"groups":["liveness","readiness"],"status":"UP"}
```

```bash
curl -s $APP/events/1
```

```
{"id":1,"name":"Lab Concert","description":"The one concert everybody wants.","venueName":"Small Hall","performerName":"The Racers","startsAt":"2026-12-31T20:00:00Z","tickets":[{"id":1,"seat":"A1","priceCents":5000,"status":"available"}, ... 20 seats ...]}
```

```bash
curl -s "$APP/events/search?keyword=jazz"
```

```
[{"id":2,"name":"Quiet Jazz Night","venueName":"Corner Cafe","startsAt":"2027-01-15T19:00:00Z"}]
```

The database started empty; Flyway created the tables and the two seed events on first start.

Now the invariant, in the cloud. Ana holds seat A1:

```bash
curl -s -X POST $APP/bookings/reserve -H 'X-User-Id: ana' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

```
{"bookingId":1,"expiresAt":"2026-11-28T15:52:44.567679Z"}
```

Ben tries the same seat:

```bash
curl -i -X POST $APP/bookings/reserve -H 'X-User-Id: ben' -H 'Content-Type: application/json' -d '{"eventId":1,"ticketIds":[1]}'
```

```
HTTP/2 409
server: cloudflare
...
{"timestamp":"2026-11-28T15:42:53.462Z","status":409,"error":"Conflict","message":"seat already held or booked","path":"/bookings/reserve"}
```

Ana pays:

```bash
curl -i -X POST $APP/bookings/1/confirm -H 'X-User-Id: ana'
```

```
HTTP/2 200
...
{"bookingId":1,"status":"confirmed"}
```

`curl -s $APP/events/1` now shows `{"id":1,"seat":"A1","priceCents":5000,"status":"booked"}`
and the other 19 seats `available`. (Pipe it to `jq` to read it more easily.)

`server: cloudflare` in the headers: Render puts Cloudflare in front of the app, which is
where the free HTTPS comes from.

### 4. Deploy a change (every time after)

1. Push a branch, open a PR. CI runs: tests (~5 min) and the Docker build.
2. Merge when CI is green.
3. Render sees the new commit on `main` and deploys it (`autoDeploy: true`). The old
   version keeps serving until the new one passes `/actuator/health`: no downtime.

Roll back: app page -> **Events** -> pick an older deploy -> **Rollback**.

### 5. Delete everything

Disconnecting the Blueprint does **not** delete what it created. In this order:

1. Blueprints -> `ticketmaster-lab` -> **Settings** -> **Disconnect Blueprint** (so a push to
   `main` can not create anything again).
2. For each of `ticketmaster-app`, `ticketmaster-redis`, `ticketmaster-db`: open it ->
   **Settings** -> bottom -> **Delete**, type the name.
3. The Dashboard says "You haven't created any services yet." Check **Billing** too.

## Security: read before you share the URL

- **No login.** Anyone with the URL can reserve seats and rename events (`PUT /events/{id}`).
  That is fine for a lab demo; do not put real data in it. Real systems put auth in an API
  gateway (design doc 04).
- **Postgres and Redis are private.** `ipAllowList: []` means nothing on the internet can
  connect; only the app inside Render can. Do not open them "to make psql work"; use the
  database page's **Connect** -> shell instead, or add only your own IP for a moment.
- **Passwords are generated by Render** and passed to the app as environment variables.
  They are not in git. The `lab` / `lab` defaults only apply on your laptop.
- **Only `/actuator/health` is exposed**, not `/actuator/env` or others (they leak config).
- **No big load tests against Render.** 200 k6 users against a $7 instance measures the
  instance, not the design, and may break Render's terms. Load tests stay local.

## Later (not set up here)

- **Elasticsearch** (steps 9-10): Elastic Cloud has a 14-day trial, then roughly $95+ / month.
  Set `SEARCH_STRATEGY=elastic`, `SEARCH_SYNC=outbox` and `SEARCH_ELASTICSEARCH_URL` on the app,
  plus an API key. Spring maps those env vars onto `search.*` properties.
- **Turn on a step's option in the cloud** without code: add an env var on the app, e.g.
  `QUEUE_ENABLED=true` or `BOOKING_HOLD_STORE=redis` (Spring maps `QUEUE_ENABLED` to
  `queue.enabled`).
- **Custom domain**, **staging environment** (a second Blueprint on a `staging` branch),
  **alerts**: all in the Render dashboard.

## What to understand

- The same image runs everywhere; only environment variables change between laptop, CI and
  cloud ("config in the environment", from the Twelve-Factor App).
- CI is the gate: Render deploys whatever lands on `main`, so `main` only gets changes whose
  tests passed. The health check is the second gate: a broken version never gets traffic.
- Managed Postgres and Redis trade money for not running backups, upgrades and failover
  yourself. Keeping them private is the cheapest security win you get.
