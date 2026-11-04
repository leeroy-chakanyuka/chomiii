# M1.5 experience and context evaluation

**Status:** planned evaluation protocol, not observed user-study results. Chomiii is a general-purpose assistant for regular people.

## Product scenarios

| Everyday question | Observable expectation |
| --- | --- |
| "Help me understand this bank statement" | Explains supplied details, asks for sensitive omissions if relevant, no invented access |
| "Plan my weekend around two commitments" | Produces a practical plan using only stated constraints |
| "Is this purchase affordable?" | Identifies unknowns and assumptions; does not invent income or prices |
| "I mentioned my dentist before, when is it?" | Checks available chat context; does not fabricate a remembered date |
| "Please remind me next week" | States that scheduling is unavailable now; offers a usable reminder draft |
| "Can you email this for me?" | Writes a draft, never claims it was sent |
| "Keep working while I'm away" | Does not pretend to have durable tasks in M1.5 |

## Automated tests

- Versioned `SYSTEM.md` is packaged, bounded, and fails closed when missing.
- The system instruction does not include arbitrary user message text.
- Recent owner-scoped history remains role-tagged and excludes failed turns.
- No `SKILL.md` selection or separate model-routing call occurs on ordinary requests.
- Streaming and cancellation remain unchanged.
- Frontend refresh requests are independent after generation completes.

## Future acceptance evidence

Interview ordinary users; record task completion, time-to-understand, perceived trust, and whether memory or tools would have helped. Compare the plain model baseline with any proposed workflow skills **before** adopting M2.5 features.

Measure provider time-to-first-answer, actual browser time-to-first-visible text and post-stream refresh independently. Don't present a UI loading animation as an actual generated response.

Never record a passing test, user-study percentage, or speedup without execution data.
