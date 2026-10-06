package com.swpp.licenseplanner.data

/** Demo flavor: returns the bundled fixture without any network call. */
class DemoQuestionRepository : QuestionRepository {
    override suspend fun load(certificationId: String): QuestionOutcome =
        QuestionOutcome.Loaded(DemoQuestionFixture.questions)
}
