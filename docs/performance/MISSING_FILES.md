# 누락 및 추가 확인 항목

## 재실행에 필수인 누락

1. **고정 커밋의 실행 대상 저장소 소스 또는 접근 권한**
   - URL은 원본 가이드에 있음: https://github.com/snuhcs-course/swpp-2026-project-team-02
   - commit: d706ad2ea66aaed6faeebea1c21dc358ace0ecf2
   - 측정기가 import하는 `backend/api_server.py`, `backend/api_service.py`, `backend/license_planner/` (settings, agent, tools 등과 내부 의존 파일)이 ZIP에 없습니다.
   - offline 및 scripted 검사에는 `backend/license_planner_tests/`, `backend/schedule_adjuster_tests/`, `backend/schedule_adjuster/`와 관련 fixture가 필요합니다. 개별 파일을 추정 복원하지 말고 해당 커밋의 전체 저장소 사본을 확보하세요.
   - 저장소를 받을 수 있으면 소스 ZIP을 추가 제출할 필요는 없지만 팀원 접근이 가능해야 합니다.

2. **팀원 개인의 GEMINI_API_KEY와 모델 접근 권한**
   - 키는 의도적으로 미포함이며 작성자의 키를 추가 제출하면 안 됩니다.
   - 각 팀원이 모델 접근·쿼터를 준비해야 live 테스트를 실행할 수 있습니다.

## 현재 분석을 막지 않는 추가 확인

- backend 설치 문서와 의존성: 공급 측정기는 표준 라이브러리 전용입니다. 원 가이드도 backend pip 의존성 없음으로 기록하지만 실제 backend는 이번에 확인하지 못했습니다.
- 2개 harness 합성 테스트의 소스: `harness_synthetic_tests.log`는 있으나 대응 테스트 코드가 ZIP에 없습니다. 과거 2개 검사 자체를 재현하려면 해당 소스가 필요합니다. 일반 측정 실행과 공급된 9개 산술 검사는 이 소스가 없어도 가능합니다.
- 정확한 RAM, 하드웨어 제품명과 Gemini temperature·요청 타임아웃 등 backend 설정 전문은 manifest에 없습니다. 설정은 고정 커밋의 소스와 대조해야 합니다.
- 15문항 문제은행은 draft입니다. 문항의 타당도·난이도 검수 증거는 없습니다.
- 토큰 사용량·청구 금액은 로그에 없어 비용을 산출하지 않았습니다.

## 이제 확보되어 누락이 아닌 항목

- 원본 task3_measure.py, cases.json, test_metrics.py, offline_probes.py
- 17개 case_id 및 각 케이스의 cases_used.json
- 85행 runs.csv, 85개 raw.json, 성공 77개의 metrics·schedule·windows·topics·fixed-topics 파일
- manifest, health, execution_summary, repository_check, 과거 offline/scripted 로그

실패 8회에는 성공 계획용 metrics·schedule 파일이 없는 것이 정상입니다. 이를 파일 누락으로 간주하거나 가짜 결과로 채우지 않았습니다.
