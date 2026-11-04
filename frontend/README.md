# Chomiii frontend

The React and TypeScript app configures a user-supplied Gemini key and reads
streamed responses from the Spring Boot API. It stores the key only in React
memory. Refreshing or closing the tab clears it. Browser-session cookies are
HttpOnly and issued by the backend; they keep the conversation list available
after a reload in the same browser.

Run `npm ci` followed by `npm run dev`, then open <http://localhost:5173>.
Vite proxies `/api` to `BACKEND_URL`, which defaults to
`http://localhost:8080`. Start the backend and database separately or use
the root [Compose setup](../README.md#run-the-stack-with-docker).

Run local checks with `npm run lint`, `npm run build`, `npm run test`, and
`npm run test:browser`. For real backend checks, start the API with
`CHOMIII_PUBLIC_ORIGIN=http://127.0.0.1:5175`, then run
`npm run test:browser:integration`. The live Gemini browser test is opt-in
with `CHOMIII_RUN_LIVE_CHAT=true` and `GEMINI_API_KEY`.

The app sends the API key only in the `X-Gemini-Api-Key` request header.
It uses `fetch()` to read the POST SSE stream. Model text is rendered as React
text, not HTML. See the [API contract](../docs/api.md).
