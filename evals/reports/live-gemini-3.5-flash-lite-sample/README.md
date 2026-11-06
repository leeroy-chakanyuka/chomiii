# Live sample report: gemini-3.5-flash-lite

Committed snapshot of the live M2 evaluation run
`backend/target/evals/2026-11-06T01-07-13.848324400Z-9d66b41b-1650-43e2-8765-d5a6b2bafe9c/`,
produced on 2026-11-06 against commit `ce50917` (working tree with the
`cf5e8be` harness changes, recorded as dirty). Raw live reports are normally
kept local because they contain model output; this small sample is committed
so the evaluation branch can be reviewed with results attached.

- 6 fixtures, one per category, 23 Gemini provider calls (8 chat,
  9 extraction, 6 follow-up), no failures, skips, or rate-limit responses.
- Free-tier guardrails: 10 s minimum spacing between calls, 20 KB input
  cap, 40-call budget, disposable PostgreSQL database.
- Precision/recall are `not measured`: one stored paraphrase and the
  follow-up answers need human or judge review
  (`manual-review-template.json`).
