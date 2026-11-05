package com.chomiii.evaluation;

import java.util.List;

interface JudgeAdapter {
    record Match(String expectedId, String actualId, String evidence, String reason) {}
    record Issue(String actualId, String category, String reason) {}
    record AnswerVerdict(String retrievalId, String verdict, String evidence, String reason) {}
    record Judgment(String fixtureId, String model, String family, List<Match> matches,
            List<Issue> issues, List<String> missingExpected, List<String> needsHumanReview,
            List<AnswerVerdict> answers, String summary) {}

    Judgment judge(EvaluationFixture fixture, List<MemoryEvaluationRunner.StoredMemory> actual,
            List<MemoryEvaluationRunner.RetrievalObservation> retrievals) throws Exception;
}
