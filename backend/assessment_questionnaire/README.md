# License Planner Assessment Questionnaire

This backend utility reads temporary multiple-choice questions from
`questions.json`, prints them in the terminal, collects the user's answers, and
writes a JSON document containing each question, its choices and correct answer,
the user's answer, and the locally calculated correctness result.

Run from `backend/`:

```powershell
python -m assessment_questionnaire.cli
```

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
the Planning API does not grade the answers. It uses the question text and
answers to determine which certification topics are needed for each question.

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
key so the FE can compute `is_correct` locally. The agent maps each question to
one or more fixed certification topics and assigns contribution weights that
sum to 1 per question. Treat the exposed key as temporary MVP-only behavior;
replace it with a trusted assessment/grading boundary before using protected
or high-stakes questions.
