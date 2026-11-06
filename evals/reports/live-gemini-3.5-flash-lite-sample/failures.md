# Evaluation findings


## m2-correction-commute (repetition 1)

- Execution: completed
- Expected facts: [I usually take the bus to work, I now cycle to work instead of taking the bus]
- Forbidden facts: [I never use buses]
- Stored memories: [I cycle to work instead of taking the bus., I usually take the bus to work.]
- False positives: {outdated_or_deleted=1}
- Extraction issues: {}, missing 1
- Missing expected facts: 1
- Unreviewed memories: 1
- Judge disagreements: []
- Eligibility: [eligible, eligible]
- Validation: [accepted_or_empty, accepted_or_empty]
- Retrieval: [RetrievalObservation[id=retrieval-1, query=How do I travel to work?, selectedMemoryIds=[371ef693-c2de-4c9f-8e16-3606c2e01586, 9db5ad3b-4865-421f-843d-5cd4bd5628d6], expectedMemoryIds=[cycle], retrievalMillis=3, answer=Based on your saved memories, there are two conflicting notes: one says you usually take the bus, and another says you cycle to work instead of taking the bus. Which method is correct?, contextAsUser=true, memoryInSystem=false, learnedAfterAnswer=[], answerTruncated=false, failure=null]]
- Lifecycle: []

## m2-inference-uncertain-walks (repetition 1)

- Execution: completed
- Expected facts: [I might like early morning walks]
- Forbidden facts: [I always walk early every morning]
- Stored memories: []
- False positives: {}
- Extraction issues: {}, missing 1
- Missing expected facts: 1
- Unreviewed memories: 0
- Judge disagreements: []
- Eligibility: [eligible]
- Validation: [accepted_or_empty]
- Retrieval: [RetrievalObservation[id=retrieval-1, query=When do I like morning walks?, selectedMemoryIds=[], expectedMemoryIds=[], retrievalMillis=3, answer=I don't have access to your personal memories or past conversations unless they're stored in your Memories panel, so I don't know when you like morning walks. 

If you'd like, you can check your Memories panel to see what's been saved, or you can tell me about your routine so I can help you organize your schedule!, contextAsUser=true, memoryInSystem=false, learnedAfterAnswer=[], answerTruncated=false, failure=null]]
- Lifecycle: []

## m2-lifecycle-clear (repetition 1)

- Execution: completed
- Expected facts: [I usually eat oats for breakfast, I volunteer on Tuesdays]
- Forbidden facts: [I never eat anything else]
- Stored memories: []
- False positives: {}
- Extraction issues: {}, missing 1
- Missing expected facts: 0
- Unreviewed memories: 0
- Judge disagreements: []
- Eligibility: [eligible]
- Validation: [accepted_or_empty]
- Retrieval: [RetrievalObservation[id=retrieval-1, query=What do you remember about me?, selectedMemoryIds=[], expectedMemoryIds=[], retrievalMillis=1, answer=I don't have access to your saved memories directly from here, but you can view, edit, or delete everything I know about you by checking the **Memories panel** in your interface. 

If there's something specific you've told me before that you'd like to bring up, just let me know!, contextAsUser=true, memoryInSystem=false, learnedAfterAnswer=[], answerTruncated=false, failure=null]]
- Lifecycle: [LifecycleObservation[operation=clear, memoryId=null, outcome=passed]]
