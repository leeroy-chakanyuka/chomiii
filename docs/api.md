# Chat and M2 memory API reference

The browser calls `/api` on its own origin. JSON endpoints return JSON; errors
before streaming use RFC 7807 `application/problem+json`. The health endpoint
remains `GET /api/health` and returns
`{"status":"UP","service":"chomiii"}`.

## Sessions and keys

Call `GET /api/conversations` before modifying conversations. The backend
issues a random `chomiii_session` cookie in local HTTP mode or a Secure,
HttpOnly `__Host-chomiii_session` cookie in the HTTPS production profile.
Both use `SameSite=Lax` and `Path=/`. Only a hash of the session token is
stored. Browsers send the cookie on later same-origin requests. Every
conversation read and write checks its owner; a foreign ID returns 404.

Mutations require an `Origin` matching `CHOMIII_PUBLIC_ORIGIN`. Local
development also accepts `localhost` and `127.0.0.1` interchangeably on the
same HTTP port. Production requires HTTPS and a trusted reverse proxy that
replaces client-supplied forwarding headers. The backend adds
`Cache-Control: no-store` to conversation responses.

For message streaming, supply the Gemini API key in
`X-Gemini-Api-Key: <your-key>`. Do not put the key in the JSON body or URL.
The backend passes the key to a request-specific Gemini client and does not
persist or log it. By default the frontend keeps it only in memory. When the
user opts in, the browser stores an encrypted copy and non-extractable Web
Crypto key in IndexedDB. The same origin can still decrypt it. Removing the
key deletes that record. Google account limits and charges are determined by Google.

## Endpoints

| Method and path | Response | Purpose |
| --- | --- | --- |
| `GET /api/chat/providers` | `200` array | Configured provider, default model, and supported model IDs. |
| `POST /api/conversations` | `201` detail | Create an empty owned conversation. Requires a session cookie and allowed Origin. |
| `GET /api/conversations` | `200` array | List the owner's conversations by latest update. Creates a session if necessary. |
| `GET /api/conversations/{id}` | `200` detail | Read messages in sequence order, including interrupted and failed turns. |
| `DELETE /api/conversations/{id}` | `204` | Delete the conversation and its messages. |
| `POST /api/conversations/{id}/messages/stream` | `200 text/event-stream` | Persist a user message, then stream the assistant reply. |
| `POST /api/conversations/{id}/messages/{requestId}/cancel` | `202` | Stop an active generation for the owner. |

A conversation detail is shaped like:

```json
{
  "id": "f7cfe429-38b5-46d3-9065-4d88f68799fa",
  "title": "A short title from the first user message",
  "createdAt": "2026-11-04T10:00:00Z",
  "updatedAt": "2026-11-04T10:01:00Z",
  "messages": [
    {
      "id": "0b5b8a7f-76b2-4cce-bff4-5486320a21ba",
      "role": "user",
      "content": "Hello",
      "status": "complete",
      "createdAt": "2026-11-04T10:00:00Z"
    }
  ]
}
```

The list endpoint returns the same `id`, `title`, `createdAt`, and `updatedAt`
fields without `messages`. The provider list contains entries such as
`{"id":"gemini","name":"Gemini","defaultModel":"gemini-3.5-flash-lite","models":[{"id":"gemini-3.5-flash-lite","name":"gemini-3.5-flash-lite"}]}`.
The configured list does not guarantee a model is available to every key.
When Google rejects a selected model, the backend returns an error rather
than switching models.

## M2 review PR: explicit personal memory endpoints

A person may save a memory manually **or opt in once to learn from chats**.
Eligible completed turns can be sent to the selected model in a separate
post-response extraction request. The app does not secretly save every message.
Tentative inferences are suggestions until approved. The browser-session
cookie identifies the owner. A foreign memory ID returns `404`.

| Method and path | Response | Purpose |
| --- | --- | --- |
| `GET /api/memories` | `200` array | List up to 100 memories saved by this browser-session owner. |
| `POST /api/memories` | `201` memory | Save explicit `{"content":"My usual groceries are oats"}`. |
| `PUT /api/memories/{id}` | `200` memory | Replace owned memory content. |
| `POST /api/memories/{id}/approve` | `200` memory | Approve a tentative inferred memory for future recall. |
| `DELETE /api/memories/{id}` | `204` | Hard-delete an owned memory record. |
| `DELETE /api/memories` | `204` | Delete all memories for the current owner. |

Entries have `id`, `content`, `source` (`user_explicit`, `conversation_fact`, or `inferred`),
`status` (`active` or `suggested`), `confidence` (0–1), optional
`sourceConversationId`, `createdAt` and `updatedAt`.
The confidence is an uncalibrated model estimate; suggested memories are
not used as chat context before approval. Content is trimmed and must contain
**1–500 Java characters**; the owner is limited to **100 records**.
The same Origin and cookie restrictions apply as for conversation mutations.

Chat normally retrieves up to three relevant saved memories (bounded
total context), added as *user-role context*, not as instructions.
Set `X-Chomiii-Use-Memory: false` on a chat-stream request to skip
memory retrieval for that turn. This is controlled by a visible checkbox
in the PR frontend. Set `X-Chomiii-Learn-Memory: true` only after the user enables
**Learn from chats**. It may schedule a second, bounded Gemini request after
a successful completed reply. The header is intentionally absent unless
opted in. Learning and recall are independently controlled.
The backend performs lexical relevance checks;
it does not promise semantic search or successful recall of every fact.

**Privacy:** relevant saved memory content is sent to the configured Gemini
provider as part of the chat request. Deleting a memory removes its primary
database row and prevents future retrieval, but does not erase previously
sent requests, chat history, backups or an in-flight request.

## Send and stream a message

Send JSON with an unpredictable client-generated UUID for each new submission.
Retain the same `requestId` only when retrying that submission.

```http
POST /api/conversations/f7cfe429-38b5-46d3-9065-4d88f68799fa/messages/stream
Content-Type: application/json
Origin: http://localhost:5173
X-Gemini-Api-Key: <your-key>

{"requestId":"76097449-604b-4f8a-bceb-667df0684597","provider":"gemini","model":"gemini-3.5-flash-lite","content":"Hello"}
```

`content` must contain non-whitespace text and be at most 8,000 Java string
characters. `provider`, `model`, and `requestId` are required. The backend
accepts only its configured model IDs. A duplicate `requestId` returns 409
without creating another message. A second generation in the same
conversation also returns 409 until the first ends. The server limits active
generations to eight and response text to 16,000 characters.

Each event is an SSE frame with an `event` name and JSON `data`. The stream
uses four event types:

```text
event:start
data:{"requestId":"76097449-604b-4f8a-bceb-667df0684597","messageId":"5a681c07-027a-4af9-8bee-a4e0c0a87264"}

event:delta
data:{"text":"Hello"}

event:complete
data:{"messageId":"5a681c07-027a-4af9-8bee-a4e0c0a87264","status":"complete"}
```

Each `delta.text` adds text to the same response. On provider failure or
cancellation, the terminal event is instead:

```text
event:error
data:{"code":"RATE_LIMITED","message":"Gemini's rate limit was reached. Try again later.","status":"failed"}
```

Cancellation uses `status:"interrupted"` and `code:"INTERRUPTED"`.
The browser should display failed or interrupted partial content separately
from a completed assistant answer. Closing the browser stream stops upstream
work when a write detects the disconnect. The explicit cancel endpoint also
stops a provider request blocked waiting for another chunk. On restart,
pending generations become interrupted. M1 supports one backend instance.
The frontend waits briefly for the terminal interruption event after the
cancel request completes. If no event arrives within six seconds, it closes
the browser stream.

## Context and errors

The backend passes the newest 20 complete conversation messages, including
the current user message, to Gemini in chronological order, subject to a
16,000-character total. It excludes failed and interrupted assistant messages
and never includes another conversation. A separate fixed system instruction
defines Chomi as an assistant without tools, live browsing, or long-term
memory. This is chat history, not M2 memory.

Validation failures return 400. An absent session on a mutation returns 401;
a wrong Origin returns 403. A foreign or missing conversation returns 404.
A duplicate request or busy conversation returns 409. The in-process request
limit returns 429 with `Retry-After: 60`; it allows 60 requests per minute per
established anonymous session and limits new sessions by IP. The eight-stream
limit also returns 429. Provider errors after streaming begins use `error`
events with `INVALID_KEY`, `INVALID_REQUEST`, `UNSUPPORTED_MODEL`,
`RATE_LIMITED`, `TIMEOUT`, `UNAVAILABLE`, or `EMPTY_RESPONSE`. A storage failure
can produce `STORAGE_UNAVAILABLE`. Raw upstream messages and keys are never
included in these errors.

## M3 first typed action (stacked on M2)

These endpoints act on the browser-session owner and share the Origin/CSRF
and error policies described above. All titles are trimmed and limited to
1–200 Java characters. Creating a proposal does **not** create a task.

| Method and path | Response | Semantics |
| --- | --- | --- |
| `POST /api/actions/tasks/proposals` | `201` | Draft a task with `{"title":"Call the dentist"}`. |
| `GET /api/actions/tasks/proposals` | `200` | List the owner's pending proposals. |
| `POST /api/actions/tasks/proposals/{id}/approve` | `200` | Create a task exactly once; retry returns the same ID. |
| `DELETE /api/actions/tasks/proposals/{id}` | `204` | Reject a pending proposal without execution. |
| `GET /api/actions/tasks` | `200` | List the owner's actual tasks. |
| `POST /api/actions/tasks/{id}/complete` | `200` | Mark an owned task completed, idempotently. |

A foreign/unknown proposal ID returns 404. A rejected proposal cannot
be approved and yields 409. Neither a proposal nor an open task is a
scheduled alert, email or external action. No Gemini tool calling
is connected to these endpoints yet. See [M3 design](m3-action-design.md).
