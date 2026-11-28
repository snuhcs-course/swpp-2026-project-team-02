# 실제 생성 계획의 재평가 검증 (2026-11-28)

> 아래 표는 에이전트 전환 전 고정 배분 방식의 검증 이력이다. 현재 에이전트 구현과 새 검증 결과는 [agent-reassessment.md](agent-reassessment.md)를 참고한다.

## 생성 경로와 원본

검증용 입력을 실제 `POST /api/v1/study-plans`에 보내 원본 계획을 한 번 생성했다. `PlanningAgent.run`, 모델 응답, 계획 생성 도구 및 스케줄러를 대체하지 않았다. 설정된 `google/gemini-2.5-flash`의 실제 모델 요청 3회가 모두 성공했고 `get_certification_profile` → `analyze_exam_results` → `build_study_schedule` 도구를 호출했다.

기준 소스는 `609486b`다. 추가 변경은 재현 시나리오·캡처 fixture·회귀 테스트·이 보고서이며, 배분·생성·저장 제품 로직을 추가로 변경하지 않았다. 고정 시각과 격리된 임시 저장소를 사용했다. 실제 사용자 계획이나 가용 시간 설정에는 쓰지 않았다. 기존 백엔드 자격 증명은 정상 설정 로더로만 사용했으며 증빙에 포함하지 않았다.

- 자격증: 컴퓨터활용능력 2급.
- 준비 기간: 2026-11-30부터 2026-12-08 00:00 전까지.
- 공부 가능 시간: 매일 18:00–20:00, 8일간 960분.
- 외부 일정: 12/3 16:00–17:00 (공부 시간과 겹치지 않음).
- 최초 진단: 아래 표에서 원본 전체 160분인 토픽은 0%, 80분인 토픽은 100%.
- 재평가 기준 시각: 12/2 18:10. 과거 240분과 진행 중인 120분은 보존하고, 12/3 이후 600분만 변경한다.

| 날짜 | 실제 생성된 원본 공부 일정 |
| --- | --- |
| 11/30 | 18:00–20:00 매크로 활용 |
| 12/1 | 18:00–18:40 매크로 활용, 18:40–20:00 스프레드시트 기본 |
| 12/2 | 18:00–20:00 인터넷 자료 활용 (기준 시각에 진행 중) |
| 12/3 | 18:00–18:40 인터넷 자료 활용, 18:40–20:00 차트와 분석 |
| 12/4 | 18:00–20:00 컴퓨터 시스템 보호 |
| 12/5 | 18:00–18:40 컴퓨터 시스템 보호, 18:40–20:00 컴퓨터 시스템 활용 |
| 12/6 | 18:00–20:00 페이지 설정과 인쇄 |
| 12/7 | 18:00–18:40 페이지 설정과 인쇄, 18:40–20:00 함수와 데이터 관리 |

## 두 답안 비교

실제 재평가 문제 조회 API의 18문항을 사용했다. 답안 A는 표의 앞 4개 토픽에서 모르겠음(0%), 뒤 4개는 정답(100%)이다. 답안 B는 반대다. 동일한 계획 식별자와 생성 당시 CSV를 복원한 뒤 각각 독립 실행했으며, A의 결과를 B의 원본으로 사용하지 않았다.

모든 분량은 분 단위다. 비교 대상인 남은 분량에는 과거·진행 중으로 고정한 360분이 포함되지 않는다.

| 토픽 | 원본 전체 | 원본 남은 분량 | A 재평가 후 남은 분량 | B 재평가 후 남은 분량 |
| --- | ---: | ---: | ---: | ---: |
| 매크로 활용 | 160 | 0 | 100 | 50 |
| 스프레드시트 기본 | 80 | 0 | 100 | 50 |
| 인터넷 자료 활용 | 160 | 40 | 100 | 50 |
| 차트와 분석 | 80 | 80 | 100 | 50 |
| 컴퓨터 시스템 보호 | 160 | 160 | 50 | 100 |
| 컴퓨터 시스템 활용 | 80 | 80 | 50 | 100 |
| 페이지 설정과 인쇄 | 160 | 160 | 50 | 100 |
| 함수와 데이터 관리 | 80 | 80 | 50 | 100 |
| 합계 | 960 | 600 | 600 | 600 |

약한 토픽은 강한 토픽의 두 배를 받는다. 이미 앞부분에서 공부한 토픽도 재평가에서 약하면 남은 시간에 다시 배정된다. 이번 두 답안은 기존 점수 기반 목표량 100분/50분으로 배치해도 최소 세션이 20분이므로, 15분 제약을 위한 반올림은 필요하지 않았다. 짧은 조각을 만드는 다른 고정 사례와 불규칙한 200개 계획의 회귀 검증은 기존 테스트에서 별도로 유지한다.

## 검증 결과

| 항목 | A | B |
| --- | --- | --- |
| 새로운 15분 미만 세션 | 0개 | 0개 |
| 남은 세션 최소 길이 | 20분 | 20분 |
| 남은 총시간 | 600분 유지 | 600분 유지 |
| 전체 공부 시간 | 960분 유지 | 960분 유지 |
| 과거·진행 중 공부 및 외부 일정 | ID·토픽·시작·끝 동일 | ID·토픽·시작·끝 동일 |
| 변경 대상의 기존 시간대 | 각 원본 구간을 빈틈·겹침 없이 덮음 | 각 원본 구간을 빈틈·겹침 없이 덮음 |
| 미리보기 | CSV와 메타데이터 쓰기 없음 | CSV와 메타데이터 쓰기 없음 |
| 저장 | 미리보기 전체 행을 `previewed_schedules`로 전송 | 동일 |
| 저장 후 실제 HTTP GET 재조회 | 미리보기·저장 응답·CSV와 동일 | 동일 |
| 계획 식별자·시험일·가용 시간 메타데이터 | 유지 | 유지 |

완료 기록은 서버 API가 아니라 앱에 저장되는 데이터다. 따라서 서버의 행 보존 검사와 별도로, 위 실제 생성·재평가 응답을 캡처해 앱의 `LiveReassessmentRepository`에 HTTP로 재생했다. 실제 `PlanFormViewModel`, `BytesPlanStore`, `AtomicFilePlanStore.AtomicFileBytes`를 실행했다.

- 원본 `study-2`에 완료 120분, `study-3`에 부분 완료 20분의 검증용 기록을 저장했다.
- 각 답안마다 미리보기에서 앱 파일이 바뀌지 않고, 저장 요청의 `previewed_schedules`가 실제 미리보기와 같은지 확인했다.
- 저장 후 두 기록의 ID·상태·실제 학습 시간이 그대로인지 확인했다.
- 기존 ViewModel을 종료하고 새 저장소 객체와 ViewModel을 만들어 디스크에서 다시 읽었다. 변경된 계획과 두 기록이 모두 동일하고, 복구 대기나 재평가 진행 상태도 남지 않았다.

앱 단계는 캡처 응답 재생 테스트이며 Android 에뮬레이터 UI를 조작한 검증은 아니다. 실제 모델 생성 및 서버 미리보기·저장·GET 재조회는 캡처 전에 실제 API로 실행했다.

## 재현 및 증빙

실제 생성부터 다시 실행 (모델 API를 호출하므로 설정된 자격 증명 필요):

```powershell
# backend 디렉터리. --credential-backend를 생략하면 이 backend의 정상 설정을 사용한다.
python scenarios/run_generated_plan_reassessment.py `
  --output coverage-results/generated-plan-reassessment.json `
  --credential-backend ../../repo/backend
```

필요할 때만 `--fixture-output tests/schedule_adjuster_tests/fixtures/generated_plan_reassessment.json`을 추가해 새 실제 실행 결과로 고정 캡처를 갱신할 수 있다.

이번 결과를 모델 호출 없이 회귀 검증:

```powershell
# backend: 일정 조정 전체 87개 통과
python -m unittest discover -s tests/schedule_adjuster_tests -v

# frontend: 각 모드 1개 테스트가 A/B 두 프로필을 모두 검증, 실패·오류·생략 0
./gradlew.bat --no-daemon `
  :app:testDemoDebugUnitTest --tests '*GeneratedPlanReassessmentTest' `
  :app:testLiveDebugUnitTest --tests '*GeneratedPlanReassessmentTest' --console=plain
```

고정 캡처는 [generated_plan_reassessment.json](../backend/tests/schedule_adjuster_tests/fixtures/generated_plan_reassessment.json)에 있다. 로컬 생성물 `backend/coverage-results/generated-plan-reassessment.json`에는 실제 생성 요청·원본·두 재평가 요청과 응답·GET 재조회·주제별 분량·검사 결과를 보관했다. `generated-plan-android-summary.json`, `generated-plan-android.log`, `generated-plan-adjuster-tests.log` 및 Gradle XML 보고서에 앱·회귀 테스트 결과가 있다.
