# Assessment Questionnaire Test Results

이 폴더에는 `run_assessment_questionnaire_test_cases.py` 실행 결과가 저장됩니다.
테스트 입력 케이스는 별도 폴더인 `../assessment_questionnaire_test_inputs/`에서 관리합니다.
모든 케이스는 로컬 JSON 처리만 확인하며 외부 API를 호출하지 않습니다.

기존 결과 파일은 질문 결과를 CSV로 저장하던 이전 구현에서 생성된 기록입니다.
현재 구현은 결과 JSON을 사용하므로 이 기록을 현재 코드의 검증 결과로 간주하지 마세요.
최신 결과가 필요하면 `backend/`에서 `python run_assessment_questionnaire_test_cases.py`를 실행하세요.

실행할 때마다 케이스별 JSON 결과와 전체 `assessment_questionnaire_test_summary.json`이 갱신됩니다.
