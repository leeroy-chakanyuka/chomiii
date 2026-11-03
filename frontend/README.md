# chomiii frontend

Minimal React + TypeScript shell (M0). It renders the lowercase `chomiii`
wordmark with the three i's accented, and checks the backend once on load via
`GET /api/health` (contract: HTTP 200 with JSON
`{status: 'UP', service: 'chomiii'}`):

- `checking…` while the request is in flight (`data-state="loading"`)
- `up` when the response matches the contract (`data-state="up"`)
- `unavailable` for any non-OK status, malformed JSON, wrong payload shape, or
  connection failure — without crashing the app

## Commands

```sh
npm ci
npm run dev         # Vite dev server, /api proxied to the backend
npm run lint        # ESLint (strict, typescript-eslint)
npm run typecheck   # tsc --noEmit, strict
npm run test        # Vitest + React Testing Library (behavior tests)
npm run build       # typecheck + vite build
npm run test:browser               # Playwright chromium smoke suite
npm run test:browser:integration   # Healthy-backend test only (see below)
npm run ci          # everything above, for CI
```

## Backend URL configuration (documented contract, no backend included here)

- **Development proxy** (default): the dev server forwards `/api` to
  `BACKEND_URL` (default `http://localhost:8080`, the Spring Boot default),
  e.g. `BACKEND_URL=http://localhost:9000 npm run dev`. This is same-origin to
  the browser and needs no CORS.
- **Build-time override**: set `VITE_API_BASE_URL` to point the frontend
  directly at another origin, e.g.
  `VITE_API_BASE_URL=http://localhost:8080/api npm run build`.
  Cross-origin requests made this way need CORS enabled on the backend (it
  currently is not) or a same-origin reverse proxy in front of it; without one
  of those the health check fails and the shell shows `unavailable`.
  The unit test for this behavior lives in `src/App.test.tsx`.

## Browser tests

- `npm run test:browser` — standalone checks that need no chomiii backend.
  They start two dev servers (`5173` against the configured backend origin,
  `5174` against a deliberately unreachable port) and verify in real Chromium
  that the shell renders and that non-OK, malformed, and unreachable backends
  lead to `unavailable` without crashing. Tests intercepting the response run
  in-browser, and the unreachable test uses a real connection-refused port, so
  passing these does **not** prove the backend is healthy.
- `npm run test:browser:integration` — real frontend-to-backend integration.
  It requires the Java backend and its configured database actually running at
  `BACKEND_URL` (default `http://localhost:8080`) and has **no skip gate**:
  a missing or unhealthy backend fails it loudly. CI runs both suites, so CI
  must start the backend before `test:browser:integration`.
