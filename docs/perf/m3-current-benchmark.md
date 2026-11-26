# M3 current latency: exploratory local measurement

**Verdict: comparison inconclusive.** At merged main `7b72dd2`, three successful
browser measurements had a **2.08 s median** and **2.38 s p95** to first visible
answer text, after one warmup. The historical browser median was **15.14 s**
([five samples](browser-after.json)); the runs were not interleaved, use different
app versions and may have different provider conditions and context. The figures
do **not** establish a speedup. The tiny sample and one API timeout prevent a
reliable latency or tail comparison. [Sanitized samples and stage data](m3-current-benchmark.json).

## Measurements

Times are milliseconds; p50 and p95 use nearest rank. With three observations,
p95 is the maximum, not a stable tail estimate. Order shown is execution order.

| Path and event | Measured samples | p50 | p95 | Failures |
| --- | --- | ---: | ---: | --- |
| Browser, first visible | 1583.6, 2376.1, 2081.4 | 2081.4 | 2376.1 | 0/3 |
| Browser, complete event | 1509.2, 2309.3, 2019.0 | 2019.0 | 2309.3 | 0/3 |
| App API, first text | 1894.5, 9794.6 | 1894.5 | 9794.6 | 1/3 timed out |
| App API, complete | 1898.5, 9799.0 | 1898.5 | 9799.0 | 1/3 timed out |
| Direct provider, first text | 5719.8, 1363.0, 2829.6 | 2829.6 | 5719.8 | 0/3 |
| Direct provider, complete | 5723.2, 1363.7, 2830.3 | 2830.3 | 5723.2 | 0/3 |

The API percentiles use only **two successes**, exclude the 90-second timeout,
and are particularly misleading as estimates. That attempt was reported as
`UNEXPECTED` by the harness; the server recorded provider cancellation at
90,013 ms and an interrupted turn. No latency is imputed for it. New-conversation
creation for the two successful API samples was 16.6 and 18.9 ms, outside their
stream timer. Direct calls use a different system instruction from current M3;
they are exploratory only, not a controlled app-overhead comparison.

Browser timing starts immediately before the automated Send click and ends after
the first non-empty assistant Markdown is observed plus two animation frames.
Completion uses the application's interval from submit handling to the stream
complete event, including new-chat creation. It is not a paint timestamp and can
precede first-visible timing. All successful samples checked for `4`; app streams
also required a complete event. This verifies the simple task, not general answer
quality. Each new chat had no earlier turns or user memories; it does not exercise
M3 planning or actions. The direct harness checks text but not a distinct provider
finish reason.

## What was observed

The cold browser warmup's backend client initialization was 2459 ms and provider
stream opening 7503 ms; its first backend text arrived at 10,186 ms. Browser
warmup duration itself was not saved. Across the three measured browser calls,
provider stream opening was 1388–2177 ms, versus initial store 10–20 ms,
context construction 6–24 ms, queue 1–5 ms and final store 2–4 ms. These stage
intervals overlap other cumulative measures; do not add them indiscriminately.
The provider opening interval includes network, provider queueing and inference;
it dominated these particular calls but does not identify which upstream part was
the limiter. No separate profiler or network probe was run. The cold/warm SDK
contrast is one observation, not a repeatable cold-start estimate.

The run attempted 12 generation requests: four browser, four app API and four
direct, each including one warmup. Eleven completed and one was interrupted.
No token usage or provider-side retries/billing were captured. The test prompt
does not meet the application's personal-memory extraction eligibility check;
the fresh database contained no memories. Do not generalize that request count
to personal prompts, which can trigger a separate background model call.

## Setup, limits and commands

On 2026-11-26: Windows 11 Home, Intel Celeron N4020, two logical CPUs, about
4 GB RAM, Node 22.16.0, Java 21.0.7 and embedded PostgreSQL 17.11.0-beta.19.
CPU snapshots before the run were 93%, 99% and 100%, with roughly 0.46–0.49 GB
free physical memory. Load was not continuously recorded or controlled. The
launcher used a fresh temporary PostgreSQL data directory and random local ports;
the app ran Spring Boot and Vite development servers, not a production deployment.
The browser ran first, then app and direct API calls alternated. Provider service
conditions, machine load, model implementation and prompt/context changes can
all differ from the historical run. No live old-version control was measured.

The recorded run used a temporary predecessor of
[`tools/bench/m3-retrospective.mjs`](../../tools/bench/m3-retrospective.mjs),
not the portable runner itself. Both use a fresh temporary database and random
ports, three measured samples and one warmup per path, browser first and then
alternating app and direct API calls. The portable runner fixes the direct model
and instruction to the predecessor's defaults, keeps sanitized output in a new
temporary results directory, and returns failure if either benchmark fails. It
does not write historical artifacts. Results remain subject to the caveats above.

To reproduce the procedure from the project root, install dependencies, provide
an authorized `GEMINI_API_KEY` in the environment, and run:

```sh
npm ci --no-audit --no-fund --prefix tools/launcher
npm ci --no-audit --no-fund --prefix frontend
node tools/bench/m3-retrospective.mjs
```

These are live, billable calls: up to 12 generation attempts, excluding any
provider-side retries. The browser and API JSON reports and allowlisted stage
timings are written to the temporary results directory printed by the runner;
raw child output and Playwright diagnostic files are not retained there. The
isolated app data and Playwright diagnostics are removed after the run.

An initial isolated preflight with a short Windows path got a Vite 403, then
timed out waiting for the key field; its subsequent API session was refused.
No generation timing events were logged in that attempt. The successful-path
run above used the full path and a new isolated database. The application and
database were stopped afterward. No real responses, credentials or personal
data are included in the report.
