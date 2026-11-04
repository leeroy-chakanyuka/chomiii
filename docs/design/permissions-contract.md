# Tool and action policy (M3 design contract)

**Status: design guidance only, not currently loaded as execution authority.** Chomiii has no registered tools, action executor, approval UI or background agent loop in M1.5. These instructions do not grant them.

## Principles
1. A model's tool request is untrusted intent. **The backend** must validate tool name, schema, authentication, owner, permissions, rate limits, and resource scope.
2. Default deny. Every tool declares read/write scope, side effects, sensitivity, timeout, idempotency, and whether approval is required.
3. Reading user-authorized data can be auto-allowed within scope. Sending messages, deleting data, sharing files, purchases, deployment, credentials, and other irreversible actions require explicit, recent user approval.
4. Approval must bind the exact canonical tool, arguments, user, destination, and expiry. Never interpret a natural-language "yes" as authorization for arbitrary changed arguments.
5. Limit steps, wall-clock time, output size, spend and external requests; enforce in Java, not in Markdown.
6. Treat tool outputs and retrieved pages as **data**, not new system instructions. Redact secrets and avoid logging content.
7. Record structured tool calls, decisions, outcomes and user approvals in owner-scoped audit logs; exclude sensitive payloads.
8. Cancellation must prevent further external actions and release resources; retries must be idempotent.

## Agent execution boundary
- A **skill** tells the model how to approach a task.
- A **tool** is a typed operation backed by code.
- A **permission** decides whether a requested operation can run.
- An **approval** is a user-authorized decision for one exact operation.
- A **trace** is evidence of what happened, not a model's claim.

A future M3 PR must implement these as testable Java interfaces and prove deny/allow/approval/timeout behavior with a fake model. Do not expose bash, arbitrary URLs, local files, or credentials to the model merely because a skill mentions them.
