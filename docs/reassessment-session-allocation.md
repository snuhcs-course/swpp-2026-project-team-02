# 재평가 후 학습 시간 배분 보완 (2026-11-28)

> 이 문서는 `609486b`의 고정 배분 구현 및 JDK 전환 검증 이력이다. 후속 에이전트 전환의 현재 계약·검증 결과는 [agent-reassessment.md](agent-reassessment.md)에 있다.

## 작업 범위

- 기준: `origin/main`의 `c9d10d1` (기존 P11/P12 병합본).
- 작업 브랜치: `fix/reassessment-session-allocation`.
- 백엔드 `schedule_adjuster/reassessment.py`와 Demo용 Android `domain/Reassessment.kt`를 같은 정책으로 수정한다.
- 계획 생성용 `license_planner/topic_budget.py`, `license_planner/scheduler.py`, UI 화면과 API 계약은 변경하지 않는다. 남경민의 P9 개선 및 양진혁의 UI 이식과 변경 파일이 겹치지 않는다.

## 배분 규칙과 제약

기존 점수 기반 목표 분량(가중치 `2 - 정답률`, 약한 토픽 우선)을 먼저 계산한다. 그 분량을 그대로 배치해 모든 새 조각이 15분 이상이면 결과를 유지한다. 정상적인 40분/20분 배분을 불필요하게 45분/15분으로 바꾸지 않는다.

짧은 조각이 생길 때만 각 원본 시간대를 15분 이상의 균등한 단위로 나눈다. 예를 들어 40분은 20분+20분, 60분은 15분 단위 4개다. 모든 토픽에 단위 하나씩을 확보하고, 목표량 대비 부족한 비율이 가장 큰 토픽부터 남은 단위를 배정한다. 동률은 기존 약점 우선 정렬로 처리한다. 시간대 길이가 달라 실제 배정 분량에 역전이 생기면 큰 분량을 약한 토픽에 대응시킨다. 마지막으로 같은 원본 시간대 안에서 연속하는 동일 토픽 단위를 합친다.

다음 항목을 보존한다.

- 과거 및 진행 중인 공부, 클라이언트가 보고된 것으로 지정한 미래 공부, 외부 일정의 ID·시간·토픽.
- 각 원본 미래 공부 시간대의 시작·끝과 빈틈 없는 시간 범위. 날짜 간 시간을 옮기지 않는다.
- 남은 총학습 시간, 계획 식별자, 시험일·가용 시간 등 메타데이터.
- 재평가 후에도 보존된 공부 ID와 연결되는 완료·부분 완료 기록 (기존 ViewModel 테스트로 확인).

최소 길이를 지키기 위한 반올림 때문에 토픽별 실제 분량은 목표량과 다를 수 있다. 공통 계획 생성의 목표 계산식은 바뀌지 않는다. 이 방식은 정확한 분량 오차나 세션 수의 전역 최적화를 주장하지 않는다.

이미 존재하는 15분 미만 시간대는 이동하거나 늘릴 수 없으므로 더 쪼개지 않고 유지한다. 모든 토픽을 배정할 단위가 부족하면 토픽을 누락하거나 새 짧은 조각을 만들지 않고 `NOT_ENOUGH_TIME`을 반환한다. HTTP에서는 기존대로 `422 INVALID_INPUT`과 `NOT_ENOUGH_TIME`으로 시작하는 메시지를 사용하며 원본을 저장하지 않는다.

## 같은 원본 계획에 두 답안 적용

재현 명령 (backend 디렉터리):

```console
python scenarios/run_reassessment_allocation_check.py --output coverage-results/reassessment-allocation.json
```

실제 HTTP 핸들러와 파일 저장소를 임시 디렉터리에서 실행한다. 고정 시각은 2026-12-02 12:00이며, 검토된 재평가 문제 18개를 사용한다. 모델 제공자는 호출하지 않는다. 두 답안은 동일한 계획 식별자·원본 CSV로 각각 독립 실행하며 첫 결과를 두 번째의 입력으로 사용하지 않는다.

원본에는 과거 60분, 진행 중 40분, 보고된 미래 공부 60분, 외부 일정 60분이 있다. 재배분 대상은 120분 세션 두 개, 총 240분이다.

| 토픽 | 답안 A 정답률 | A 배분 | 답안 B 정답률 | B 배분 |
| --- | ---: | ---: | ---: | ---: |
| 매크로 활용 | 0% | 40분 | 100% | 20분 |
| 스프레드시트 기본 | 0% | 40분 | 100% | 20분 |
| 인터넷 자료 활용 | 0% | 40분 | 100% | 20분 |
| 차트와 분석 | 0% | 40분 | 100% | 20분 |
| 컴퓨터 시스템 보호 | 100% | 20분 | 0% | 40분 |
| 컴퓨터 시스템 활용 | 100% | 20분 | 0% | 40분 |
| 페이지 설정과 인쇄 | 100% | 20분 | 0% | 40분 |
| 함수와 데이터 관리 | 100% | 20분 | 0% | 40분 |
| 합계 | | 240분 | | 240분 |

답안에 따라 취약 토픽이 바뀌면 해당 토픽에 더 많은 시간이 배정된다. 두 경우 모두 정확한 목표가 최소 길이를 만족하므로 분량을 반올림할 필요가 없다.

각 답안마다 미리보기의 파일 무변경, 보호 행 보존, 새 조각 15분 이상, 원본 시간대 전체 보존을 확인한다. 미리보기의 전체 `schedules`를 `previewed_schedules`로 보내 `commit=true` 저장한 뒤, 인증된 `GET /study-plans?schedule_csv_file=...`로 다시 읽어 미리보기·저장 응답·재조회·실제 저장 행의 일치를 확인한다. 결과 JSON에는 두 요청과 응답, 주제별 분량, 검증 항목을 포함한다.

## 짧은 세션 회귀 사례

기존 코드 `c9d10d1`과 동일 입력으로 비교했다. 두 사례는 Python과 Android가 공유하는 고정 기대 결과로도 검증한다.

| 사례 | 남은 시간 | 수정 전 15분 미만 | 수정 후 15분 미만 | 최소 세션 전 → 후 |
| --- | ---: | ---: | ---: | ---: |
| 3개 토픽, 보호된 보고 행 포함 | 150분 | 1개 | 0개 | 6분 → 15분 |
| 8개 토픽, 1/3 정답률·진행 중 행 포함 | 240분 | 2개 | 0개 | 5분 → 15분 |

추가 검증은 길이가 불규칙한 200개 계획의 시간대 보존·최소 길이·약점 순서·답안 순서 독립성, 29분 시간대에 2개 토픽을 넣을 수 없는 경우, 기존 7분 시간대 유지, 불필요한 반올림 방지를 포함한다. 공유 fixture는 6개에서 9개로 확장했다.

## 테스트 결과

- Windows Python 3.14: 계획 생성 149개, 일정 조정 86개, API 268개 실행. 실패 없음, 권한 관련 API 7개 생략.
- Ubuntu Python 3.12.3, 일반 사용자, Linux 임시 파일시스템: 동일한 503개 모두 통과, 생략 없음.
- Windows Gradle 재평가 관련 테스트: Demo 51개·Live 46개 모두 통과. 공유 fixture 9개와 기존 완료 기록 보존·미리보기·저장 상태 테스트를 포함한다.
- JDK 17 설정 당시 Windows Gradle 전체 단위 테스트에서는 각 모드의 `AtomicFilePlanStoreTest` 9개가 기존 파일을 교체하는 `AtomicFile.rename` 경로에서 실패했다 (`android.util.Log.e not mocked`로 드러남). 해당 저장소 구현과 테스트는 이번 변경에 포함하지 않았다.

### Windows 실패 원인 추가 확인

수정 전 `frontend/gradle/gradle-daemon-jvm.properties`는 `toolchainVersion=17`로 고정되어 있어 `JAVA_HOME`에 Android Studio JBR 25를 지정해도 Gradle은 별도로 내려받은 JDK 17을 사용했다. 실제 Windows JDK 17.0.20.1에서 최소 Java 예제와 동일 AndroidX Core 1.18.0으로 원인을 재현했다.

1. 첫 저장은 대상 파일이 없으므로 성공한다.
2. 다음 저장은 `.new` 파일을 기존 파일로 교체해야 한다. 이 환경에서 `File.renameTo(existingTarget)`가 `false`를 반환하고 기존 내용이 남는다.
3. AndroidX `AtomicFile.finishWrite()` → `rename()`이 실패를 기록하려고 `android.util.Log.e()`를 호출한다.
4. 로컬 JVM 테스트의 Android Log는 실제 구현이 없는 stub이므로 `Method e in android.util.Log not mocked` 예외가 발생한다. 따라서 Log 예외는 파일 교체 실패에 뒤따르는 증상이다.

| 최소 재현 환경 | 기존 대상에 renameTo | 동일 AtomicFile 반복 저장 |
| --- | --- | --- |
| Windows JDK 17.0.20.1 (변경 전 Gradle 고정 JVM) | false, 기존 내용 유지 | 두 번째 저장에서 동일 예외 |
| Windows Android Studio JBR 25.0.4 | true, 새 내용 반영 | 3회 연속 성공 |
| Linux OpenJDK 17 | true, 새 내용 반영 | 전체 저장소 테스트 통과 |

이 문제를 Windows 전체의 파일 교체 불가로 일반화하면 부정확하다. 위에서 재현한 JDK·OS 조합의 차이다. Log를 mock하거나 먼저 기존 파일을 삭제해서 통과시키면 교체 실패를 숨기거나 원자성을 훼손할 수 있으므로 해결책으로 사용하지 않았다. 최소 재현 소스는 로컬 생성물 `backend/coverage-results/RenameProbe.java`, `AtomicProbe.java`에 있다.

- 동일한 최종 컴파일 클래스와 Gradle에서 내보낸 의존성을 Linux Java 17의 JUnitCore로 직접 실행: Demo 680개·Live 672개 모두 통과. Windows에서 실패·생략된 파일 교체 및 권한 테스트도 실행했다. Linux Gradle 빌드나 Android 기기 테스트는 아니며 JaCoCo 증빙에도 사용하지 않는다. 사용한 클래스는 각각 63개·60개이며, 테스트가 아닌 `ClearCalendarForTest` 헬퍼는 수집 대상에서 제외한다. 로그의 OkHttp Android Log 초기화 경고는 JUnit 실패가 아니며 두 실행 모두 종료 코드 0이다.

로컬 상세 증빙은 `backend/coverage-results/`의 `reassessment-allocation.json`, `reassessment-short-session-regression.json`, `windows-*-tests.log`, `linux-*_tests.log`에 있다. 결과 JSON과 로그는 생성물로 Git 추적 대상이 아니다.

이 검증은 로컬 단위·HTTP 통합 검증이며 새 CI 성공이나 에뮬레이터 실행 결과로 간주하지 않는다.

## Windows JDK 25 전환 후 재검증

사용자 요청에 따라 `updateDaemonJvm --jvm-version=25`로 데몬 버전과 모든 플랫폼 다운로드 URL을 다시 생성했다. CI의 Java 25와 일치하도록 서명 릴리스 워크플로도 Java 25로 맞췄다. 앱 저장 구현, Log mock, 테스트 생략 조건은 변경하지 않았다.

Windows 11에서 전체 Demo·Live 단위 테스트를 필터 없이 새로 실행했다. 일회성 검증 init script로 두 `Test` 태스크의 `javaLauncher`가 모두 Android Studio JBR `25.0.4+-16339643-b508.27`임을 확인했다. 전체 테스트 재현 명령은 `frontend/`에서 다음과 같다. 실제 증빙 실행에서는 여기에 `--no-configuration-cache -I ../backend/coverage-results/verify-test-jvm.gradle`을 추가했으며, 이 로컬 init script는 JVM 검증과 메타데이터 출력만 수행했다.

```powershell
./gradlew.bat --no-daemon `
  :app:testDemoDebugUnitTest --rerun :app:testLiveDebugUnitTest --rerun --console=plain
```

| 모드 | 발견된 테스트 | 통과 | 실패·오류 | 생략 |
| --- | ---: | ---: | ---: | ---: |
| Demo | 680 | 679 | 0 | 1 |
| Live | 672 | 671 | 0 | 1 |

이전에 실패했던 파일 교체 테스트 9개씩은 전부 통과했다. `AtomicFilePlanStoreTest` 자체는 각 모드 15개 중 14개 통과·1개 생략이다. 생략된 `existingSnapshotThatCannotBeOpenedFailsAndIsNotOverwritten`는 Windows에서 파일 읽기 권한 차단이 적용되지 않으면 기존 `assumeFalse(file.canRead())` 조건으로 생략된다. 앞선 Linux Java 17 전체 실행에서는 이 항목도 실행·통과했다.

`BUILD SUCCESSFUL`을 확인했으며, 상세 결과는 `backend/coverage-results/windows-android-jdk25.log`, `windows-android-jdk25-summary.json`, `test{Demo,Live}DebugUnitTest-jdk25.json`과 `frontend/app/build/reports/tests/test{Demo,Live}DebugUnitTest/`에 있다. 새 Windows 결과는 위의 JDK 17 실패 이력에 대한 후속 검증이며, Android 기기 테스트 실행을 의미하지 않는다.

추가로 같은 Windows JDK 25 설정에서 `assembleDemoDebug assembleLiveDebug lintDemoDebug lintLiveDebug`도 `BUILD SUCCESSFUL`로 완료했다. 로그는 `backend/coverage-results/windows-android-jdk25-build-lint.log`에 있다. 새로 생성된 요청에 대한 `frontend/contract_checks/verify_request.py --backend-ref HEAD --request frontend/app/build/contract-check/study-plan-request.json` 검사도 기준 백엔드 `c9d10d1`에 대해 `PASS`했다.
