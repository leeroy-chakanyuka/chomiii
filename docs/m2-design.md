# M2: owner-scoped personal memory — implementation notes

This file describes **draft PR #3**. The present Chomiii product is still a single-browser-session chat app; M2 does not add cross-device identity or real-world tools.

```mermaid
flowchart LR
    UI["Memory panel: explicit save/edit/delete"] --> API["Spring MVC /api/memories"]
    API --> Service["MemoryService"]
    Service --> JDBC["Owner-filtered JDBC"]
    JDBC --> Store["Flyway V3 personal_memories"]
    Chat["Chat request"] --> Selector["Bounded lexical selector"]
    Selector --> Service
    Selector --> Input["ChatProvider: user-role memory context"]
    Input --> Gemini["Gemini streaming"]
```

## Data and trust invariants

- `browser_sessions.owner_hash` (established in a secure HttpOnly cookie filter) owns each memory row by foreign key. Every read, update and delete includes `owner_hash`; clients cannot supply or override it.
- `source=user_explicit` means a person saved it deliberately from the Memories UI. There is no agent-authored or automatically ingested memory in this milestone.
- Hard limit 100 entries, 500 characters each; creating new entries locks the owner row before counting to prevent simultaneous writes from bypassing the limit.
- Editing replaces content; deletion removes the row; clear-all is owner scoped. The frontend asks for confirmation before bulk deletion. Updates do not leave an independent copy/history in this memory table.
- The model never receives credentials to query the memory store. The backend chooses at most three relevant memories under a strict character budget; the model receives them as **user-context data** before the current request, not as privileged system instructions.
- If retrieval has no match or fails, chat proceeds without those memories; no model call is made to classify or summarize memory before response generation. A user can opt out of injecting memories for their request.

## Product flow

1. Open **Memories** in the app (even while not connected to an AI key).
2. Save a plain-language fact: "My usual groceries are milk and oats." The API stores the explicit entry with timestamps and source.
3. Start a new chat and ask "What are my usual groceries?" A local selector may include that saved fact in the next model request.
4. Correct it in the manager; future requests see the updated entry. Delete it or clear all; future context retrieval no longer includes that memory.
5. Untick **Use saved memories** in chat to keep the saved entries out of that request. The setting is a browser-local preference, not a server-controlled memory read permission for external actors.

## Scope limitations and risks

Memory data is still plaintext in the existing DB; the 180-day cookie is not a persistent account identity. Deletion is **forward-looking**: a currently running model request may already have received old memory content. No revision history is retained after hard delete, and conversation messages and external provider logs require separate lifecycle policies. This is not a general-purpose secure personal-data vault.

Retrieval is lexical, not semantic, and may miss paraphrases. The `user_explicit` source is not proof of truth, freshness, or permission for external actions. See [research decision](m2-research.md) and [memory contract](design/memory-contract.md).

## Implementation dependencies

No new runtime libraries. Uses Spring Boot MVC/validation/JDBC/Flyway, Java 21 and React/TypeScript already in the project. The typed tool loop and consented real-world actions remain M3; external apps remain M4.

## Verification to perform

- Backend focused: `./mvnw -Dtest=MemorySelectorTest,MemoryServiceTest,MemoryContextIntegrationTest,ChatIdentityTest test`
- DB-backed: set `CHOMIII_DB_URL` (MySQL or PostgreSQL) and run `./mvnw -Dtest=MemoryDatabaseTest,ChatStreamDatabaseTest test`; also run migrations and relevant integration suites.
- Frontend: `npm ci && npm run lint && npm run typecheck && npm test && npm run build`; inspect UI and connected-browser behavior.
- Security: wrong-owner 404, invalid/missing cookie/Origin errors, bounded memory context, no provider keys in memory or logs, delete/clear correctness.
- Performance: measure memory lookup time and first-visible-token changes under matched provider conditions.

Tests are authored in the PR. **None should be reported as passing without executed output.**
