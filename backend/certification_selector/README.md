# Certification Selector

An extensible backend-side certification picker. Add future certifications to
`certifications.json`; entries with `enabled: false` remain in the catalog but
are not selectable. The current enabled entry is Computer Specialist Level 2.

The selector only returns the selected certification. It does not call Gemini
or attempt to retrieve exam availability. Users should check the official
KCCI page and enter their chosen preparation start and exam date in the
Planning API request.

Run from `backend/`:

```powershell
python -m certification_selector.cli
```

List selectable certifications as JSON:

```powershell
python -m certification_selector.cli --list-json
```

The selected certification is printed as JSON with its `id`, `name`, and
official schedule URL. Use the official [KCCI test-center lookup](https://license.korcham.net/ex/dailyExamPlaceConf.do)
to find local exam information.

## Planning API date inputs

The existing `POST /api/v1/study-plans` request accepts `preparation_start` and
`exam_date` in `YYYY/MM/DD/HH/MM` format. These are manual user inputs; the
interval between them is the preparation period. For example:

```json
{
  "certification_id": "computer_specialist_level_2",
  "preparation_start": "2026/10/07/18/00",
  "exam_date": "2026/11/30/09/00",
  "self_assessment": "beginner",
  "weekly_availability": {
    "monday": [{"start": "18:00", "end": "20:00"}],
    "wednesday": [{"start": "18:00", "end": "20:00"}],
    "saturday": [{"start": "10:00", "end": "13:00"}]
  },
  "assessment_results": "질문 CLI/화면에서 생성한 assessment_results JSON 문서"
}
```

## Offline test cases

From `backend/`, run:

```powershell
python run_certification_selector_test_cases.py
```

Inputs and generated results are stored separately in
`certification_selector_test_inputs/` and `certification_selector_test_results/`.
The cases cover selectable catalog entries, invalid catalog data, CLI selection,
and JSON listing. They do not call external APIs.
