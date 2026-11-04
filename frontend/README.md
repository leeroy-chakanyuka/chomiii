# Chomiii frontend

The React and TypeScript app configures a user-supplied Gemini key and reads
streamed responses from the Spring Boot API. By default, it keeps the key in
React memory and clears it on reload. With **Remember this key on this device**,
it encrypts the key using Web Crypto and stores ciphertext and a non-extractable
key in IndexedDB. Scripts running on the same origin can still use that key,
and anyone who can use the browser profile can start a chat. **Remove key**
deletes the saved record. Browser-session cookies are
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
It uses `fetch()` to read the POST SSE stream. Model text renders as Markdown;
raw HTML and external images are not rendered. See the [API contract](../docs/api.md).
