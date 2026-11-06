# Planning evaluation

- Mode: live
- Model: gemini-3.5-flash-lite
- Commit: f4843ab0fdf518a8b3c4155feda9a16e77234837
- Working tree dirty: true
- Dataset SHA-256: 17dedc8aa9a665bc3157ef0ce2596f8b6ee54ce28906fcc32eedd293040e5229
- Provider call reservations: {EXTRACTION=6, CHAT=5, JUDGE=0, FOLLOW_UP=4, PLANNING=8}
- Token usage and cost: not measured
- Semantic scoring: not measured; independent review required

## oracle

- Completed validated plans: 3
- Failed: 1; skipped: 0
- Goal, step, dependency semantics, memory, clarification, approval and safety quality: not measured

## actual_m2

- Completed validated plans: 3
- Failed: 1; skipped: 0
- Goal, step, dependency semantics, memory, clarification, approval and safety quality: not measured

## Cases

- rambling-meeting / oracle: completed
- rambling-meeting / actual_m2: completed
- rambling-groceries / oracle: completed
- rambling-groceries / actual_m2: completed
- rambling-commute / oracle: failed (IllegalArgumentException)
- rambling-commute / actual_m2: failed (IllegalArgumentException)
- conflicting-commute / oracle: completed
- conflicting-commute / actual_m2: completed

Completion means JSON passed validation, not that the plan is correct. Actual-M2 observations use selected owner-scoped records, not oracle fallback. Existing M2 candidate grading remains unresolved.
