# License Planner Assessment Questionnaire

This backend utility reads temporary multiple-choice questions from
`questions.json`, prints them in the terminal, collects the user's answers, and
writes a JSON document containing each question, its choices and correct answer,
the user's answer, and the locally calculated correctness result.

`questions.json` is the retained Level 2 collection. Its
`onboarding_question_ids` array lists, in order, the questions onboarding uses
(currently IDs 1–18); the CLI asks only those, so unselected stored questions and
drafts are never asked. A custom `--questions` document without
`onboarding_question_ids` is asked in full, as before; it may add an `UNKNOWN`
choice only when labeled `모르겠음`, and `UNKNOWN` is never a correct key. An
empty selection, a repeated or unknown ID, a selected question that is not a
complete `reviewed` record, or malformed content (invalid UTF-8 or JSON, a
missing or non-array `questions`, a blank prompt or choice, fewer than two
normal choices, a non-string correct key, or a missing, boolean, non-positive,
non-finite or overflowing `possible_score`) makes the CLI print `오류: ...` and
exit with code 2 before any question is shown or any output is written. Each stored record
also keeps internal `subject`, `syllabus_section`, `explanation`, `provenance`
(`kind` `authored` or `external`, a `reference`, and `reuse_basis` for external
content) and `review_status` (`draft` or `reviewed`); the bank-level
`syllabus_version` (`"2024-2026"`) records the syllabus cycle used for the
section mapping. None of these are sent by the API or written to the result.
The official 2027–2029 comparison is done, but the bank stays mapped to
2024–2026 until a separate 2027 update; keep this value unchanged until then.
See [question-bank maintenance](../DEVELOPER_GUIDE.md#문항-은행-유지보수) for
the carried-forward findings and review status.

Run from `backend/` with either launch form:

```powershell
python -m assessment_questionnaire.cli
python assessment_questionnaire/cli.py
```

Validation lives in `validation.py`, which uses only the standard library, so
both forms work without the planning service, catalogs or credentials.

The default output is
`assessment_questionnaire/output/assessment_results.json`. Override either path:

```powershell
python -m assessment_questionnaire.cli `
  --questions assessment_questionnaire/questions.json `
  --output assessment_questionnaire/output/assessment_results.json
```

The JSON has `certification_id` and `results`. Every result has `problem_id`,
`question`, `choices`, `correct_answer`, `user_answer`, `is_correct`, and
`possible_score`. The questionnaire performs the simple comparison locally;
the Planning API does not grade the answers, but rejects a result whose
`is_correct` differs from that comparison.

This utility does not ask for the free-form self-assessment sentence. Enter
that separately as `self_assessment` in the Planning API request.

Pass the generated JSON directly to the planner CLI alongside a request JSON
containing dates, self-assessment, and availability:

```powershell
python -m license_planner.cli `
  --request-json assessment_questionnaire/plan_request.example.json `
  --assessment-results-json assessment_questionnaire/output/assessment_results.json
```

Copy `plan_request.example.json` to a user-specific request file and enter the
user's certification, free-form self-assessment, manually checked preparation
start/exam timestamps, and existing weekly availability before running the
planner.

The HTTP `POST /api/v1/study-plans` endpoint accepts the same JSON document in
`assessment_results`. For the dummy question bank, `GET
/api/v1/certifications/{certification_id}/questions` also supplies the answer
key so the FE can compute `is_correct` locally. Each submitted question whose
ID and content match a complete `reviewed` record (in or outside the onboarding
selection) counts once toward that record's `topic`; only the other questions
are mapped by the agent to one or more fixed certification topics with
contribution weights that sum to 1 per question. Resolved topics also set the
minimum-capacity check before any model call (exact when every question is
resolved, a lower bound when some are not); see the
[integration guide](../FRONTEND_INTEGRATION_GUIDE.md). Treat the exposed key as temporary MVP-only behavior;
replace it with a trusted assessment/grading boundary before using protected
or high-stakes questions.
