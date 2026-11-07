# Planning evaluation

- Mode: live
- Model: gemini-3.5-flash-lite
- Commit: f4843ab0fdf518a8b3c4155feda9a16e77234837
- Working tree dirty: true
- Dataset SHA-256: 17dedc8aa9a665bc3157ef0ce2596f8b6ee54ce28906fcc32eedd293040e5229
- Provider call reservations: {JUDGE=0, CHAT=2, PLANNING=4, FOLLOW_UP=2, EXTRACTION=4}
- Token usage and cost: not measured
- Semantic scoring: not measured; independent review required

## oracle

- Completed validated plans: 2
- Failed: 0; skipped: 0
- Goal, step, dependency semantics, memory, clarification, approval and safety quality: not measured

## actual_m2

- Completed validated plans: 2
- Failed: 0; skipped: 0
- Goal, step, dependency semantics, memory, clarification, approval and safety quality: not measured

## Cases

- rambling-meeting / oracle: completed
- rambling-meeting / actual_m2: completed
- rambling-volunteer / oracle: completed
- rambling-volunteer / actual_m2: completed

Completion means JSON passed validation, not that the plan is correct. Actual-M2 observations use selected owner-scoped records, not oracle fallback. Existing M2 candidate grading remains unresolved.
