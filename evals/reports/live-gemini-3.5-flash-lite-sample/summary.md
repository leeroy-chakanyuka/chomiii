# M2 evaluation report

- Commit: ce50917546d31b43cff25fa9ddd2c1b9c0c03ec1
- Working tree dirty: true
- Dataset: m2-fixtures-v1 (84a05ce8a445282d674fa55f58304221c36ba524940a1387098ff50c49c25bcd)
- Mode: live
- Provider: gemini / gemini-3.5-flash-lite
- Judge: not independently graded
- Database: postgresql
- Selected fixtures: 6
- Attempts: 6, completed: 6, skipped: 0, failed: 0

## Final-memory metrics

- Precision: not measured
- Recall: not measured
- Active-memory recall: not measured
- Within-capacity recall: not measured
- Correct final status among matched facts: not measured
- Correct source kind among matched facts: not measured
- F1: not measured
- Unreviewed memories: 1

- Eligible-case precision: not measured
- Eligible-case recall: not measured (cases with at least one eligible user turn)

## Extraction and retrieval

- Accepted-candidate precision: not measured
- Accepted-candidate recall: not measured
- Accepted-candidate within-capacity recall: not measured
- Known over-inference rate among accepted candidates: not measured
- Candidate issue categories: {}
- Retrieval precision and recall: not graded: at least one retrieved memory needs semantic review
- Literal evidence for learned stored memories: 1.000 (4/4)
- Grounded-answer accuracy: not graded
- Semantic grounding: not independently graded
- Literal evidence matching is not proof that a memory is supported.

## Pipeline and usage

- Extraction attempts: 7
- Pre-extraction skips or rejected chats: 1
- Eligibility outcomes: {eligible=7, no_personal_language=1}
- Validation outcomes: {accepted_or_empty=7, not_applicable=1}
- Provider call reservations (may fail before network): {JUDGE=0, FOLLOW_UP=6, CHAT=8, EXTRACTION=9}
- Total evaluation time: 256305 ms
- Token usage: not measured
- Judge token usage: not measured
- Cost: not measured
- First visible browser text: not measured
- Latency percentiles: request processing p50 22 ms, p95 not enough samples (n=8); first provider token p50 6959 ms, p95 not enough samples (n=8); main provider stream p50 7453 ms, p95 not enough samples (n=8); scheduling delay p50 1 ms, p95 not enough samples (n=8); extraction stream p50 1604 ms, p95 not enough samples (n=7); retrieval p50 3 ms, p95 not enough samples (n=6)
- Actual validation and persistence durations: not exposed by the application.

## Critical observations

- Follow-up context checks: 6; memory in system instructions: 0
- Cross-owner checks exercised: 0; failed: 0
- Stale-deletion checks exercised: 0; failed: 0
- Known unsupported active memories: 0
- Known sensitive retained memories: 0
- Tool execution: not applicable; M2 has no tool executor.
- These counts cover only exercised cases; they are not a security certification.

## Acceptance gates

Targets are engineering thresholds, not production guarantees. Ordinary cases here exclude difficult, negative, and inference scenarios and require an eligible turn.

- Final-memory precision >= 95%: NOT EVALUATED
- Candidate recall against all expected facts >= 80%: NOT EVALUATED
- Candidate recall within the documented cap >= 80%: NOT EVALUATED
- Correct matched-memory status >= 90%: NOT EVALUATED
- Correct matched-memory source kind >= 90%: NOT EVALUATED
- Cross-owner leaks: NOT EVALUATED
- Deleted-memory resurrection: NOT EVALUATED
- Memory in system instructions: PASS (exercised cases only)
- Unauthorized tool execution: NOT APPLICABLE (no M2 tool executor)

See `failures.md` and `results.json` for individual outcomes.
