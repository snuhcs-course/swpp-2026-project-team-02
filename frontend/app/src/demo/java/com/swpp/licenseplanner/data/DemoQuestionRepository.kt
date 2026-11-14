package com.swpp.licenseplanner.data

/** Demo flavor: returns the bundled fixture without any network call. */
class DemoQuestionRepository : QuestionRepository {
    override suspend fun load(certificationId: String): QuestionOutcome {
        val injected = DemoFailureInjector.take(DemoRequestOperation.QUESTIONS)
        return if (injected != null) QuestionOutcome.Failure(injected)
        else QuestionOutcome.Loaded(DemoQuestionFixture.questions)
    }
}
