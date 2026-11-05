# Chomiii evaluation harness

This directory contains synthetic evaluation scenarios for the personal-memory
milestone. The harness is being built on `eval/m2-agent-evaluation`, based on
PR #3, so it can be reviewed independently and retained after M2 merges.

## Design and discrepancies

Chomiii is an everyday assistant. The evaluation should show whether it retains
useful context without inventing personal facts or exposing another person's
data. The fixture labels describe intended behavior; the application output is
the evidence. Offline scripted responses do not measure model intelligence.

The current extractor considers only the latest user message, accepts 25–2,000
Java characters, uses a personal-language heuristic, and accepts at most two
candidates. The parser returns accepted candidates but not rejection reasons.
Persistence does not report why a write was skipped. The provider boundary does
not expose token usage. The evaluation must distinguish observable outcomes from
unavailable details instead of inferring a model failure from an eligibility
skip or presenting an evidence substring as semantic proof.

`docs/m2-milestone-contract.md` was removed in the preceding PR review at the
owner's request. Its last committed version is available in Git history. The
current code, `docs/m2-design.md`, and `docs/m2-research.md` remain the active
references.

## Implementation sequence

1. Validate versioned fixtures and test one-to-one scoring.
2. Observe the existing pipeline using scripted and real provider boundaries.
3. Add synthetic conversations and lifecycle cases.
4. Add opt-in, budget-limited live runs, optional judging, and local reports.
5. Document the future planning contract without implementing a planner.
