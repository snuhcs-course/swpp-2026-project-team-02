# Certification Selector Test Results

Run `python run_certification_selector_test_cases.py` from `backend/` to write
one `<case_id>_result.json` per case and
`certification_selector_test_summary.json` in this folder. Existing result
files may reflect an earlier version of the input cases; the runner replaces
them when explicitly executed.

Some existing result files belong to the earlier schedule-search version and
are stale against the current input cases. Regenerate them by explicitly running
the command above. Current cases verify that the selector only emits catalog
choices and does not fetch exam dates. The runner is offline and does not call
Gemini or the KCCI website.
