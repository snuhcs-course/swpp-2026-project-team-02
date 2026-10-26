# Certification Selector Test Inputs

`certification_selector_test_cases.json` defines offline cases for the
certificate catalog loader, interactive CLI selection and JSON listing. Each
case includes a stable `case_id`,
description, input data, and expected IDs or error/result.

Cases using `catalog_document` write their JSON into a temporary directory.
The `project_catalog` case reads the actual
`backend/certification_selector/certifications.json`. The runner does not call Gemini
or the KCCI website and does not modify input fixtures.
