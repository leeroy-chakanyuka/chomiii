# Chomiii product definition — before agent complexity

**Decision (2026-11-04):** Chomiii is a **general-purpose personal assistant for everyday people**, not a coding agent. It may help with code when asked, but developer workflows do not define its identity, onboarding, or first feature set.

## Why we are building it

Everyday activities are scattered across messages, calendars, documents, lists, and people's own memories. Chomiii should lower the effort needed to understand a situation, decide what to do, remember the right context **with permission**, and eventually complete an authorized task. Useful outcomes matter more than a distinctive persona or a large set of prompt files.

## For whom

**Initial hypothesis — validate, do not assume:** a mobile-first individual who is comfortable using chat but does not know what an API, token, agent loop, or skill file is. They may be a student, worker, parent, or somebody managing a household. We should interview and observe real users before narrowing the first cohort.

The initial interface must work for someone with limited time, limited attention, and imperfect connectivity. The user should never have to choose a programming skill, learn a command grammar, or manage a model provider merely to ask for everyday help. BYOK is useful for development, **not** a finished consumer onboarding experience.

## Five jobs worth testing with people

| Job in plain language | An ideal outcome | First safe slice |
| --- | --- | --- |
| "Help me understand this." | A clear, accurate explanation tailored to what I know | Grounded answer, clarification when needed |
| "Help me make a decision." | Options, trade-offs, and a sensible recommendation I can revise | Text-only comparison |
| "Help me get organized." | A plan that takes real constraints and dates into account | Editable plan in chat |
| "Remember what matters to me." | Relevant preferences and ongoing plans carried forward, with easy correction/deletion | M2 consented, owner-scoped memories |
| "Take care of this." | The assistant performs the permitted steps and shows exactly what happened | M3 bounded tool action with review |

These are *jobs*, not a promise that all are already implemented.

## The ideal experience

A person says: "I have a dentist appointment Friday, two bills due, and family plans this weekend. Help me organize everything."

1. Chomiii understands the commitments and asks only for information essential to the next useful step.
2. It proposes a plan immediately; relevant memories or connected calendar data are used **only if authorized and available**.
3. It offers to create reminders when the action capability exists, displays the proposed details, and obtains approval when required.
4. The user can later say "Move that to Saturday" without repeating the whole context.
5. Every action is distinguishable from a suggestion: **prepared**, **awaiting approval**, **completed**, **failed**, and **cancelled** are different states.

The first version should deliver steps 1–2 well. Steps 3–5 are staged milestones.

## The ultimate experience: one request, many connected results

The short-term assistant should be great at ordinary conversations. The **long-term
agent ambition** goes further: a person who has to work late can ask Chomiii to
notify the right people about cancelled plans, rearrange dinner, check a
grocery service for their usual basket, and deal with the follow-up.

Doing this well depends on **context and memory** (who the people are and what
"usual" means), **actual service integrations** (email, calendar and groceries),
**permissions** (no unapproved sending or spending), and **durable task state**
(no duplicated requests or mysterious unfinished actions). This is a north-star
evaluation scenario across M2–M5, **not** something M1.5 already does.

A second ambition is "Free up Tuesday afternoon." With consented knowledge
of the user's stated priorities, Chomiii can propose which commitments are
flexible, distinguish personal tasks from obligations to other people, request
approval for changes, and report the result. It must not silently cancel
important commitments or promote guessed priorities into memories.

See [experience evaluation](design/experience-evaluation.md) for the full
multi-step scenarios and staged, testable failure cases.

## Non-negotiable product tests

- **Simple:** Can a new person understand the home screen and start chatting without a technical tutorial?
- **Fast:** Are typing, submission, and response feedback immediate? Record first *actual* token latency separately from local UI feedback.
- **Trustworthy:** Can users tell what Chomiii knows, assumes, remembers, and has actually done?
- **Respectful:** Is memory consensual and correctable? Can the user stop or revoke action permissions?
- **Useful:** Did the experience reduce effort or lead to a better result? Measure task completion, not number of agent steps.

## Things we are deliberately *not* building first

A coding-agent-first experience, generic `SKILL.md` for things a model already handles, a swarm of subagents, per-user cloud VMs, arbitrary shell access, silent autonomous purchases, massive permanent prompts, or a promise of unlimited free inference.

## Open questions for user research (M1.5 deliverable)

- Which daily problems make people return twice a week?
- Is a fast, reliable answer enough for the first paid/free pilot?
- How much manual setup is tolerable? Which trust explanations are necessary?
- Which information do people *want* remembered; what must never be retained?
- Which first authorized action actually saves time: reminders, tasks, calendar, or another workflow?
- How do we design beyond English and high-bandwidth desktop users?

**Success for M1.5:** a clear, testable product definition, a small identity, a context contract, and a ranked first-use-case backlog. Not a complete agent.
