# Certification Selector Test Inputs

`certification_selector_test_cases.json` defines offline cases for the
certificate catalog loader, interactive CLI, agent tool schema, and mocked
grounded schedule response filtering. Each case includes a stable `case_id`,
description, input data, and expected IDs or error/result.

Cases using `catalog_document` write their JSON into a temporary directory.
The `project_catalog` case reads the actual
`certification_selector/certifications.json`. The mocked schedule response
does not access the network or consume API quota. No input fixture is modified
by the runner.
