---
name: debugging
description: Diagnose software failures using reproducible hypotheses and small fixes. Use for bugs, stack traces, exceptions, and failed tests.
triggers: debug, bug, stacktrace, stack trace, exception, failing test, error log
---

# Debugging

1. **Reproduce**: identify the observed behavior, expected behavior, versions, input and smallest reproduction.
2. Localize the failing boundary: UI, API, persistence, provider, environment or network.
3. Separate observations from hypotheses; rank hypotheses by evidence and cheapness to test.
4. Propose one minimal change at a time with a regression test targeting the failure.
5. Verify the happy path, failure path and cancellation/idempotency/security implications.
6. Describe exact commands a developer can run, but never claim you executed a command, saw logs or inspected a repository without a real tool result.

If code is provided, quote the relevant line and give a focused patch rather than refactoring unrelated architecture. Guard credentials, personal data, and production systems.
