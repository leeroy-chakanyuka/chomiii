# Chat latency measurements

## Observed result

The requested under-two-second first-visible-token target was not met in the
local run. The available measurements do not demonstrate an end-to-end speedup:
provider response time varied by seconds, far more than the local change.
The comparison is inconclusive, not a speedup claim.

On Windows with Java 21, Node 22, two logical CPUs, local PostgreSQL 17, and
`gemini-3.5-flash-lite`, the direct Gemini and Chomiii requests alternated.
Each run used the same short arithmetic prompt and equivalent system
instruction on both sides, default generation settings, no prior conversation,
one warmup per side, and checked that the answer contained `4`.

| Run | Samples per side | Chomiii first text p50 / p95 | Direct Gemini p50 / p95 | Failures |
| --- | --- | --- | --- | --- |
| [Before](baseline.json) | 10 | 5.66 s / 35.61 s | 7.11 s / 12.93 s | 0 |
| [After](after.json) | 5 | 13.67 s / 21.94 s | 18.88 s / 21.63 s | 0 |

The after run used an isolated local database and a slightly revised Chomiii
system instruction. The before run used the original local app and instruction.
They ran at different times and are not a controlled before/after speedup
comparison. With five samples the reported p95 is the maximum, so it is not a
stable estimate. Both the direct and app paths varied widely.

[Five real Chromium requests](browser-after.json), after one warmup, measured
15.14 s p50 and 17.43 s p95 to the first rendered response, with a 10.11–17.43 s
range and no errors. The browser measured from submit to a painted response;
the server benchmark starts its clock at the stream POST and reports new-chat
creation separately. Some single-chunk replies completed at the protocol level
before the next browser paint, so completion can be less than first-visible time.

## Where the time went

The [backend stage log](stages-after.txt) includes the warmup plus five API
and five browser calls. In the five measured API calls, opening the Gemini
stream took 7.91–21.83 seconds, while the initial transaction took 9–28 ms,
context construction rounded to 0 ms, queue time took 1–15 ms, and the first
SSE send call took 1–8 ms. Finishing the database transaction took 4–11 ms.
The first cold SDK setup took 1.47 seconds; warm setups took 4–36 ms.
The SDK setup measurement includes request-object and HTTP-client creation.
SSE send time measures the server call, not browser paint.

A [five-call model-list probe](network.json) took 0.53–1.10 seconds, with a
0.60-second median. This shows that a small authenticated Google API request
was faster than text generation during the measurement window. It does not
separate network, provider queueing, and inference, so the exact upstream
cause remains unproven. Machine load and provider latency varied over time.

The UI no longer waits for an extra conversation-list GET before starting a
new stream. A regression test held that GET open and observed that the old
code could not start the stream; the new code did. This removes one blocking
round trip, but the noisy live samples do not establish a measurable
end-to-end improvement. The initial database transaction stays before the
provider call because it records the user's message, claims the conversation,
and prevents duplicate requests. The final transaction occurs after all text
deltas are delivered and before the `complete` event.

## HTTP clients, Markdown, and model thinking

The current Google SDK closes its per-request client and evicts that client's
pool. Sharing a pool without accounting for that behavior could affect other
concurrent credentials. No connection-pooling speedup is claimed. Chomiii uses
`react-markdown` and `remark-gfm` for text, lists, tables, and code while
disabling raw HTML and external images.

[Google's thinking documentation](https://ai.google.dev/gemini-api/docs/thinking)
says `gemini-3.5-flash-lite` supports thinking, with minimal thinking by default.
Google exposes optional thought summaries, not a complete internal trace.
Chomiii currently streams answer text only; it does not claim its `Thinking…`
label is an actual model trace. A thought-summary UI remains future work.

`tools/bench/ttft.mjs` compares the existing Chomiii POST SSE path with the
Gemini Developer API using the same prompt, model, and fixed system instruction.
It creates and deletes an isolated conversation for each app sample. The script
requires an authorized `GEMINI_API_KEY` in the environment, records errors,
and never writes the key or generated text to its report. It measures time to
the first non-thought text chunk and complete stream duration. The first turn's
conversation-creation time is reported separately.

Set `CHOMIII_BENCH_API` if your local app is not at
`http://127.0.0.1:5173/api`. Set `CHOMIII_BENCH_OUTPUT` to save JSON results.
If you call the backend directly rather than through Vite, set
`CHOMIII_BENCH_ORIGIN` to the configured browser origin so its CSRF check
accepts the benchmark POSTs.
The default is ten measured calls per side plus one warmup each. With ten
samples, p95 is the slowest call. It is an exploratory measurement, not an SLA.
`tools/bench/network.mjs` times five small Gemini model-list calls with the
same key. It helps separate basic network and authentication overhead from
the time spent waiting for generated text.

For the isolated benchmark, run `npm ci` in `tools/launcher` and `frontend`,
then set `GEMINI_API_KEY` in your shell and run
`node tools/bench/run-local.mjs` from the project root. It starts a disposable
local app and PostgreSQL instance, alternates five app and direct calls, then
performs five browser calls. It writes sanitized results under `docs/perf/`.
The browser stores no benchmark key. The requests can incur Google charges.
