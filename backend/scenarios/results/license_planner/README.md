# License Planner Test Results

`python -m scenarios.run_license_planner_test_cases`, run from `backend/`, writes one
`<case_id>_result.csv` and `<case_id>_tool_trace.json` per case and
`license_planner_test_summary.json` here. The runner calls Gemini, so results
vary between runs. Files in this folder are local output and are ignored by git.
