# Evaluate Chomiii memory

This harness measures whether Chomiii retains useful personal context, avoids
unsupported claims, and retrieves the right facts for an everyday assistant
user. Fixtures are fictional. Their expected and forbidden facts are written by
people; the application supplies the observed results.

The harness lives in `backend/src/test/java/com/chomiii/evaluation/`. It uses
Java 21, JUnit, Jackson, the existing `ChatProvider` boundary, and the M2
services. It does not ship in the application jar. This branch is based on PR
#3 so the harness can be reviewed separately and kept after M2 merges.

See the [product vision](../docs/product-vision.md), [target architecture](../docs/agent-architecture-plan.md),
[context strategy](../docs/context.md), and [M2 design](../docs/m2-design.md).

## Execution modes

| Mode | Provider | Storage and application boundary | Purpose |
| --- | --- | --- | --- |
| `offline` | Scripted, no paid calls | Actual chat, learning, parser, and memory services with an in-memory test repository and turn store | Fast checks of the harness and pipeline. The scripted output is not a model-quality measurement. |
| `integration` | Scripted, no paid calls | Spring Boot, Flyway, actual JDBC and transaction-backed services, plus HTTP memory-owner checks | Exercise the pipeline and lifecycle against a disposable MySQL or PostgreSQL database. |
| `live` | Gemini through the application's `ChatProvider` | The same disposable-database path | Measure actual model output under an explicit call budget. |
| Independent judge | Optional separate model family | OpenAI-compatible HTTPS endpoint, or loopback HTTP for a local test | Assist semantic review. The declared family is checked, but the endpoint's identity cannot be proven by the harness. |
| Manual review | No provider calls | Existing report and human decisions | Regrade saved observations without changing the original run. |

The runner calls `ChatStreamService` directly. It validates requests with the
same bean-validation annotations as the controller, but does not measure a
browser's first visible text. Database-mode isolation checks call the real
memory HTTP API with two synthetic session cookies. Chat streaming itself is
not exercised through HTTP by this harness.

## Run offline

Run commands from `backend/`. On Windows use `mvnw.cmd`; on macOS or Linux
replace it with `./mvnw`.

```powershell
# Run the full synthetic dataset and write reports.
.\mvnw.cmd "-Dtest=EvaluationSuiteTest" test

# Run all harness tests, including fixture and scoring checks.
.\mvnw.cmd "-Dtest=com.chomiii.evaluation.*Test" test

# Select one fixture, one category, or repeat the selected cases.
.\mvnw.cmd "-Dtest=EvaluationSuiteTest" "-Deval.fixture=m2-long-capacity" test
.\mvnw.cmd "-Dtest=EvaluationSuiteTest" "-Deval.category=lifecycle" test
.\mvnw.cmd "-Dtest=EvaluationSuiteTest" "-Deval.fixture=m2-explicit-appointments-001" "-Deval.repetitions=3" test
```

`EvaluationSuiteTest` writes the report path to the console. A completed run
can contain quality failures. The test fails for evaluation errors, while
`summary.md` and `failures.md` describe quality findings. A scripted run cannot
establish Gemini accuracy or meet the live-model quality gates.

## Run with a disposable database

Create a new, empty database on the local machine. Its name must start with
`chomiii_eval_`. The harness rejects remote hosts and requires an explicit
`EVAL_DISPOSABLE_DB=true`. It applies Flyway migrations and starts the app;
startup may mark pending messages interrupted. Do not use a database containing
real conversations, even if its name matches the prefix.

Set the database credentials in the environment, then run:

```powershell
$env:EVAL_DISPOSABLE_DB = 'true'
$env:CHOMIII_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/chomiii_eval_example'
$env:CHOMIII_DB_USER = '<disposable database user>'
$env:CHOMIII_DB_PASSWORD = '<disposable database password>'
.\mvnw.cmd "-Dtest=EvaluationSuiteTest,BudgetedEvaluationDatabaseTest" "-Deval.mode=integration" "-Deval.category=lifecycle" test
```

For MySQL, use a disposable URL such as
`jdbc:mysql://127.0.0.1:3306/chomiii_eval_example`. The harness creates two
synthetic session owners per case, checks their HTTP memory access, and deletes
those owners when the case finishes. Dispose of the entire test database after
the run. The runner pins both datasource and Flyway configuration to the
validated URL, binds its ephemeral server to loopback, and uses a local test
origin. URL query parameters are limited to boolean `useSSL` and
`allowPublicKeyRetrieval`, `sslmode`, and numeric `connectTimeout`.

## Run a small live evaluation

Live mode requires a disposable database, an authorized Gemini test key, and
an explicit call budget. Set `GEMINI_API_KEY` privately in the environment;
do not put the key in a fixture, repository file, or command-line argument.

```powershell
$env:EVAL_DISPOSABLE_DB = 'true'
$env:EVAL_LIVE = 'true'
$env:EVAL_MAX_PROVIDER_CALLS = '8'
$env:EVAL_MAX_FIXTURES = '1'
$env:EVAL_REPETITIONS = '1'
$env:EVAL_TIMEOUT_SECONDS = '60'
# Set CHOMIII_DB_URL, CHOMIII_DB_USER, CHOMIII_DB_PASSWORD, and GEMINI_API_KEY.
.\mvnw.cmd "-Dtest=EvaluationSuiteTest" "-Deval.mode=live" "-Deval.fixture=m2-explicit-appointments-001" test
```

The default model is `gemini-3.5-flash-lite`; set `EVAL_MODEL` to change it.
The budget counts main chat, extraction, follow-up chat, and judge calls before
each provider request. These are reserved attempts, not confirmed billable
requests; a provider can fail before opening the network call. A follow-up can
itself schedule extraction. Exhaustion,
timeouts, invalid output, and provider errors are not successful observations.
Live runs use the model's actual assistant replies between fixture user turns;
scripted runs replay the assistant text included in the fixture.

Optional limits are `EVAL_MAX_FIXTURES`, `EVAL_REPETITIONS`, and
`EVAL_TIMEOUT_SECONDS`. Defaults are 10 fixtures for provider-backed runs, one
repetition, and 60 seconds. The call budget must be supplied for every live or
judge-backed run. No provider call is made by the default offline mode.

## Use an independent judge

The judge can grade a live run or explicitly grade scripted results without
enabling Gemini. It sends the synthetic conversation, expected facts, saved
memories, and follow-up answers to the configured provider. It does not receive
the harness's score. It is not a substitute for deterministic isolation or
deletion tests.

```powershell
$env:EVAL_JUDGE = 'true'
$env:EVAL_MAX_PROVIDER_CALLS = '3'
$env:EVAL_MAX_FIXTURES = '1'
$env:EVAL_JUDGE_URL = 'https://example.invalid/v1/chat/completions'
$env:EVAL_JUDGE_MODEL = '<separate model id>'
$env:EVAL_JUDGE_FAMILY = '<non-Gemini model family>'
# Set EVAL_JUDGE_API_KEY privately in the environment.
.\mvnw.cmd "-Dtest=EvaluationSuiteTest" "-Deval.fixture=m2-explicit-appointments-001" test
```

Use a real OpenAI-compatible endpoint instead of the example URL. Gemini and
Google endpoints are rejected as judges for a Gemini extraction run. A caller
could still misdeclare a proxy's family, so verify the endpoint independently.
The judge rubric is in [judge/rubric.md](judge/rubric.md). Invalid judge JSON,
unknown IDs, duplicate matches, or unsupported evidence are evaluation errors.

If the judge reports token usage, the report records it. To estimate judge cost,
set `EVAL_JUDGE_INPUT_USD_PER_MILLION` and
`EVAL_JUDGE_OUTPUT_USD_PER_MILLION` to the provider's current published rates.
The report labels that estimate and does not claim a total cost when Gemini
token usage is unavailable.

## Review a saved run manually

Each report includes `manual-review.md` and `manual-review-template.json`.
Inspect the source fixture and the actual memories in `results.json`. In the
template, remove resolved IDs from `needsHumanReview` and add decisions to the
appropriate arrays. A positive match has `expectedId`, `actualId`,
`verdict: "supported"`, a verbatim `evidence` quote, and a `reason`. Issue
entries have `actualId` and `reason`. Optional answer decisions use
`retrievalId`, `verdict`, `evidence`, and `reason`.

Save the edited template separately, then run:

```powershell
.\mvnw.cmd "-Dtest=ManualReviewCliTest" "-Deval.sourceRun=target/evals/<run-id>" "-Deval.reviewFile=target/evals/<run-id>/manual-review-template.json" test
```

The command validates IDs and evidence, checks that the fixture dataset has not
changed, and writes a new report. It does not change the original report or make
provider calls. Unresolved decisions remain ungraded.

## Fixtures and scoring

The [fixture schema](schemas/m2-fixture.schema.json) describes each versioned
case. `FixtureLoader` also checks linked IDs, lifecycle operations, and planning
dependencies. The dataset now contains **150 entirely fictional fixtures**: 24 explicit-fact,
15 long-message, 24 conversation, 24 correction, 16 inference, 26 negative, and
21 lifecycle cases. The original 40 cases are preserved unchanged. Another
110 adversarial cases are in the `adversarial-expanded.json` files in each
category. `DatasetCoverageTest` checks category minimums and the
500, 1,000, 1,800, 2,000, 2,001, and 8,100-character boundaries.

### Adversarial corpus

The expanded cases intentionally mix valid evidence with distractions instead
of relying on short, single-fact declarations:

- Three durable facts in one message compete for the two-candidate extraction
  limit; the third remains annotated as out-of-scope for capacity scoring.
- Interleaved multi-turn conversations contain repeated preferences, quoted
  first-person text from other speakers, assistant acknowledgements, and
  irrelevant factual detail.
- A revised preference should supersede the original, not survive alongside it.
  These deliberately difficult scenarios can expose a known M2 limitation; a
  scripted replay's quality finding is not automatically a harness failure.
- Quoted or hypothetical first-person speech, prompt-injection examples, and
  third-party facts should not be attributed to the actual user.
- Uncertain preferences remain suggestions rather than becoming permanent
  habits, while isolated incidents and fictional plans should not be remembered.
- Length cases exercise 1,500, 1,750, 1,900, 1,999, 2,000, 2,001, 8,001
  and 8,100 characters with exact filler expansion.
- Lifecycle cases require executed approve, edit, delete, clear, or cross-owner
  isolation behavior rather than accepting an unexercised/skipped action.

**Important:** These are stress cases and failure detectors, not 110
independent measurements of live model quality. Some scripted extractions are
deliberately wrong to check whether the memory pipeline rejects unsupported
facts. The fixture's expected/forbidden facts are the human-authored oracle;
they must not be inferred from the scripted model output. No actual users,
credentials, or real-world actions are included.

For a representative offline sample run from `backend/`:

```powershell
.\mvnw.cmd "-Dtest=EvaluationSuiteTest" "-Deval.fixture=m2-adversarial-schedule-dense" test
.\mvnw.cmd "-Dtest=EvaluationSuiteTest" "-Deval.fixture=m2-adversarial-software-revision" test
.\mvnw.cmd "-Dtest=EvaluationSuiteTest" "-Deval.fixture=m2-adversarial-lifecycle-books" test
```


Long fixtures may specify `targetChars` and `paddingText`. The loader preserves
the complete authored prefix and appends fictional filler to the explicit Java
character count. It never truncates the authored message to pass an application
limit. The 2,001-character case is reported as an extraction skip; the
8,100-character case is rejected by request validation.

Each expected memory has a stable ID, source turns, expected final status,
importance, and an `inScope` label. `inScope` marks the human-selected facts
within the current two-candidate limit. Other expected facts still count in
whole-dataset recall. A `manual_edit` fact is scored after the edit and is not
counted as a missed model extraction. `absent` means the final record should
have been removed or superseded.

Matching is one-to-one. Exact normalized text and human-written aliases are
deterministic; other paraphrases require a judge or human review. Unknown
memories do not become false positives by assumption, and incomplete grades
produce `not measured` metrics. Empty denominators are also `not measured`.
Literal evidence matching is reported separately from semantic grounding.

## Reports and limits

Reports are written under `backend/target/evals/<run-id>/`, which Git ignores:

- `run-metadata.json` records the commit, dirty-tree flag, dataset hash, mode,
  model, database, limits, call counts, elapsed time, and available usage.
- `results.json` contains per-case candidate, persistence, retrieval, lifecycle,
  answer, and scoring observations.
- `summary.md` contains aggregate metrics and engineering targets.
- `failures.md` identifies missing, unsupported, stale, and unreviewed results.
- `manual-review.md` and `manual-review-template.json` support human review.

Configured keys and some credential-like text are redacted in reports; this is
not a complete secret detector. Keep raw reports local; they can still contain
synthetic conversation content and model output. To compare two
compatible clean runs, pass `-Deval.compare=target/evals/<previous-run-id>` to
`EvaluationSuiteTest`. Different datasets, modes, models, judges, databases,
coverage, or dirty trees are reported as incompatible.

The current extractor considers only the latest user message, accepts 25–2,000
characters after trimming, uses a personal-language heuristic, and accepts at
most two candidates. It does not resolve contradictory memories or semantic
duplicates. The parser checks a case-insensitive evidence substring, but that
substring does not prove the saved claim. It does not expose per-candidate
rejection reasons, and the storage service does not expose skipped-write reasons.
The report marks those details unavailable. Mixed sensitive messages can lose
otherwise useful facts because the filter rejects the entire message.

The report records request processing, provider-stream first token and duration,
post-completion scheduling delay, extraction-stream duration, and direct
retrieval time. It does not measure actual validation or persistence duration,
browser paint, or Gemini token usage. Live percentile summaries require at
least five samples for p50 and 20 for p95. Small or unmatched samples do not
establish a performance improvement. A provider key, timeout, or quota error is
an evaluation error, not a passing result.

## M3.1 extension

The optional `planning` object in the appointment fixture is an illustrative
contract, not a planner or a claim that calendar tools exist. Validation checks
memory references, fixture-declared tool names, approval flags, dependencies,
and cycles. A future planner must validate tools against the real catalog.

Future M3.1 evaluations should reuse fixture IDs, filtering, budgets, judging,
and report metadata. Run each planning case twice: first with the human-curated
`oracleMemoryIds`, then with the actual M2 retrieval. Compare the paths to
separate extraction, retrieval, and planning errors. No real-world action is
executed by this harness.
