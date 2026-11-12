# Memory retrieval evaluation

## Result

The available evidence does not establish that embeddings or model-invoked
search improve answer quality over the profile and lexical baseline. Embeddings
remain disabled by default. The internal search tool remains available when
recall is enabled, and can be disabled independently with
`chomiii.memory.search-tool.enabled=false`.

## Four-way retrieval ablation

On 2026-11-12, `MemoryAblationSuiteTest` ran 28 retrieval checks selected from
the existing synthetic M2 fixtures. Each configuration used the same fixtures,
five repetitions, and a disposable PostgreSQL 17.11 database. The test seeded
the expected active facts directly and added three distractor facts per fixture.
It used deterministic fake embeddings and invoked the tool with a scripted
decision for every C and D observation. No model chose the tool calls.

| Configuration | Precision | Recall | False positives | False negatives | Tool calls | Fake query embeddings | Retrieval p50 / p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A: profile and lexical | 73.5% | 92.6% | 45 | 10 | 0 | 0 | 4.2 / 16.6 ms |
| B: A plus embeddings | 73.5% | 92.6% | 45 | 10 | 0 | 10 | 7.6 / 29.5 ms |
| C: B plus scripted tool | 73.5% | 92.6% | 45 | 10 | 140 | 28 | 15.7 / 60.5 ms |
| D: A plus scripted tool | 73.5% | 92.6% | 45 | 10 | 140 | 0 | 9.4 / 33.2 ms |

Each arm recorded 140 observations and 125 true positives. The five repetitions
are repeated measurements of 28 cases, not 140 independent cases. The test
treated all added distractors as irrelevant without independent review, so the
false-positive counts are a proxy. It made 111 fake document-embedding calls
before timing. No C or D tool call returned an error. In 28 separate simulated
provider-outage checks, hybrid search returned the lexical result every time.
The second owner received no context in the 28 isolation checks.

The timings cover context assembly, SQL reads, and the scripted tool where
enabled. They do not include real model inference or browser rendering. The
two-core Celeron N4020 was at 100% CPU in three pre-run samples, with OpenCode
and memory compression active. No production bottleneck was profiled. The
latency comparison is therefore inconclusive. Observed minimum and maximum
times were 2.3–56.0 ms for A, 2.5–186.6 ms for B, 9.3–280.6 ms for C, and
5.2–127.1 ms for D. The [raw ablation report](../evals/reports/memory-ablation-2026-11-12.json)
records the fixture IDs, dataset hash, counts, and unrounded times. The working
tree was dirty during the run; it included the evaluation instrumentation in
this change and pre-existing user edits.

An earlier version of this test exposed irrelevant profile injection. A
regression test reproduced a saved stamp-collecting hobby appearing in a
request for a mountain poem. Commit `37bcd12` restricts generic topic matches
and only injects unrelated profile categories for stable work, location,
education, or project context. The table above is from the subsequent run.

The ablation does not measure outdated-memory retrieval, answer quality,
personalization, avoided clarification questions, real embedding quality,
or real model tool decisions. A reviewed dataset with conflicting and corrected
facts and independently graded answers is still needed.

Editing a canonical memory replaces its derived content and invalidates its
embedding job and vector. Automatic extraction does not generally reconcile
contradictory memories learned in separate turns. The current user correction
takes precedence in the current reply, but users may need to edit or delete an
older saved record in the Memories panel.

## Existing M2 and M3.1 evaluations

The offline M2 harness completed all 150 synthetic fixtures. Its scripted
extractions produced retrieval precision of 82.7% (124/150) and recall of
87.9% (124/141). The scorer identified 39 unsupported active memories and 23
duplicate candidate issues. These results exercise the pipeline with scripted
provider responses; they are not Gemini accuracy measurements. The generated
report is under `backend/target/evals/` and is not committed.

The offline M3.1 planning harness completed 30 oracle and 30 actual-memory
cases. Completion means that scripted output passed JSON and plan validation.
It does not grade plan quality, clarification choices, or safety semantics.

## Budgeted live Gemini checks

Two runs used the same fictional M2 appointment fixture with a limit of four
provider attempts each and ten seconds between reservations. Both completed
the fixture. Each used one chat call, two extraction calls, and one follow-up
call. Neither run invoked the tool. In both runs the selected memory matched
the expected appointment preference, and the follow-up answer repeated it.
The answers were not independently graded.

The second run reported 1,524 input tokens, 107 candidate-output tokens, and
1,631 total tokens for its four Gemini calls. The provider did not report a
separate thought-token count. Token usage for the first run was not captured.
Cost was not measured. One additional synthetic `gemini-embedding-2` request
returned a normalized 768-dimensional vector. Its token usage was not captured.

A separate fictional prompt explicitly asked Gemini to use `memory.search`.
The model invoked the native function and continued the same turn with the
requested food. That probe used a mocked memory service and two model requests;
database isolation was tested separately. Gemini reported 1,068 input tokens,
21 candidate-output tokens, and 1,089 total tokens. Thought tokens were not
reported. The probe does not measure how often the model chooses to search in
ordinary conversations.

The milestone used eleven live provider requests in total. These samples are too
small to measure p50/p95 latency or compare the four configurations.

## Reproduce the retrieval-only ablation

Create an empty local PostgreSQL or MySQL database whose name starts with
`chomiii_eval_`. Set `CHOMIII_DB_URL`, `CHOMIII_DB_USER`, and
`CHOMIII_DB_PASSWORD` for that database. From `backend/`, run:

```powershell
$env:EVAL_DISPOSABLE_DB = 'true'
.\mvnw.cmd '-Dtest=MemoryAblationSuiteTest' '-Deval.ablation=true' test
```

The test writes `target/evals/memory-ablation.json`, including fixture IDs,
counts, and timings. It uses no live provider calls. See
[evaluation instructions](../evals/README.md) for the existing M2 and M3.1
harness and its live-call budget controls.
