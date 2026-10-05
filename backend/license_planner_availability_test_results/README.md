# License Planner Availability Test Results

The checked-in traces were produced before availability checks were separated
from the old readiness tool flow. They are historical output and are not a
current verification of the planner.

To refresh these local scheduler cases, run
`python run_license_planner_availability_test_cases.py` from `backend/`.
The runner uses the deterministic scheduler and does not call Gemini.
