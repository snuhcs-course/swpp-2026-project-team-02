# License Planner Task 3 최종 제출 패키지

## 결과와 파일

실제 live 로그 17개 폴더의 85행을 재집계했습니다. 성공 77, 실패 8이며 성공 77회 모두 constraint_valid=True입니다. 원시 응답으로 77회 지표를 다시 계산해 저장된 지표 및 runs.csv와 대조했고 차이는 없었습니다. 모델 재호출 실험은 이번 보완 작업에서 수행하지 않았습니다.

- `License_Planner_Task3_Report_KO.pdf`, `report_ko.md`: 최종 보고서 및 수정용 본문
- `original/`: 업로드 ZIP의 624개 파일 원본. 이전 offline 보고서도 이 안에 보존했습니다.
- `task3/`: 바로 실행할 수 있도록 모은 원본 측정기, 입력, 산술 테스트, scripted 검사, 15문항 초안. 원본과 바이트가 같습니다.
- `case_manifest.csv`: 17개 폴더와 case_id의 정확한 대응
- `analysis/`: 재집계 CSV, 오류 상세, 고정 분류와 모델 분류 상관 차이, 검증 JSON
- `aggregate_results.py`: API 없이 원본 결과를 재집계·검증하는 표준 라이브러리 도구
- `reproduce.ps1`: 고정 커밋을 검사하고 원 실험처럼 케이스별 별도 프로세스로 실행하는 도우미
- `requirements.txt`: 공급된 측정기·분석기는 표준 라이브러리만 사용하므로 pip 의존성 없음
- `MISSING_FILES.md`, `SUBMISSION_CHECKLIST.md`, `ENVIRONMENT.md`, `CHANGELOG.md`, `SHA256SUMS.txt`

**원본의 보고서.md·실행가이드.md에는 live 0회라는 과거 상태가 남아 있습니다.** 이는 10월 14일 offline 검사 시점의 기록입니다. 최종 결과는 루트의 이 README, 최종 보고서 및 analysis를 기준으로 보세요. 원본 기록은 덮어쓰지 않았습니다.

## 1 로그 재집계부터 실행

Python 3.14.7을 사용하면 live 실험의 Python 버전과 맞습니다. 이번 보완 검증은 번들 Python 3.12.14에서 수행했습니다. 공급된 산술 테스트는 9개 모두 통과했습니다.

```powershell
# 이 README가 있는 폴더에서
python --version
python task3/test_metrics.py
python aggregate_results.py --out recheck-01
```

`recheck-01/verification.json`의 row_count=85, outcomes=success 77/failure 8, recomputed_success=77, issues=[]를 확인하세요. 기존 출력 경로는 덮어쓰지 않습니다. `case_summary.csv`는 성공 실행만 평균내며 `*_n`에 유효 표본 수가 있습니다. `all_attempt_latency_mean`은 실패를 포함합니다. p95는 nearest-rank 방식입니다.

## 2 실행 대상 소스 확보

**ZIP에는 backend 소스가 없습니다.** 원본 실행가이드에 적힌 아래 저장소를 받을 권한과 고정 커밋이 필요합니다. 이번 보완 작업에서는 원격 저장소를 내려받거나 접근 권한을 확인하지 않았습니다. 저장소 접근이 안 되면 팀원이 해당 커밋의 소스 사본을 전달해야 합니다.

```powershell
git clone https://github.com/snuhcs-course/swpp-2026-project-team-02.git source
git -C source switch --detach d706ad2ea66aaed6faeebea1c21dc358ace0ecf2
git -C source rev-parse HEAD
git -C source status --short
```

필요한 backend 파일과 기존 테스트 디렉터리는 `MISSING_FILES.md`에 정리했습니다. 기존 작업 폴더 대신 별도 사본을 사용하세요. 원 측정기가 API의 자동 저장 동작을 실행하므로 소스 사본에 캐시·출력 파일이 생길 수 있습니다.

## 3 Python과 의존성

live manifest는 Anaconda Python 3.14.7 AMD64, Windows 11 빌드 26200을 기록합니다. Git도 PATH에서 사용할 수 있어야 합니다. 측정기와 분석기는 Python 표준 라이브러리만 사용합니다. 원본 실행가이드는 backend도 외부 패키지 없이 실행했다고 기록하지만 backend 소스가 이번 ZIP에 없어 독립 확인하지 못했습니다. 소스를 확보한 뒤 해당 커밋의 설치 문서를 대조하세요. pip 설치가 필요한 것으로 확인되면 별도 기록합니다.

```powershell
# 원 실험과 같은 Python 3.14.7을 선택한 상태에서
python -m venv --without-pip .venv
.\.venv\Scripts\python.exe --version
.\.venv\Scripts\python.exe task3/task3_measure.py --help
.\.venv\Scripts\python.exe task3/test_metrics.py
```

requirements.txt에 추정 SDK나 버전을 추가하지 않았습니다. 생성기는 GEMINI_API_KEY를 사용하고 모델은 `gemini-3.5-flash-lite`입니다. 별도 api_server 실행은 필요 없습니다. 측정기가 127.0.0.1의 임의 포트에서 로컬 HTTP 서버를 시작하고 종료합니다.

## 4 offline 확인

키를 설정하기 전에 실행하세요. 측정기의 기본 모드도 offline입니다.

```powershell
.\reproduce.ps1 -Repo .\source -OutRoot .\results-offline-01 -Mode offline
.\.venv\Scripts\python.exe task3/offline_probes.py --repo source --out results-scripted-01
```

원본 offline 로그는 license_planner_tests 13개 중 12개 통과·1개 실패, schedule_adjuster_tests 15개 통과, 입력 17개 유효로 기록합니다. 실패는 가용 요일 저장 정규화와 테스트 기대값 차이이며 이를 해결한 증거는 없습니다. harness 종료 코드만 보고 모든 테스트가 통과했다고 판단하면 안 됩니다. `offline_summary.json`의 suite exit_code와 각 로그를 확인하세요. scripted 검사는 사람이 지정한 도구 인자이며 live Gemini 성능과 합산하지 않습니다.

## 5 각자의 키 설정 후 live 재현

키는 각자가 발급하고 이 PowerShell 세션에서만 설정합니다. 키를 코드·채팅·ZIP에 기록하지 마세요.

```powershell
$task3SecureKey = Read-Host 'Gemini API 키' -AsSecureString
$task3Credential = [pscredential]::new('task3', $task3SecureKey)
$env:GEMINI_API_KEY = $task3Credential.GetNetworkCredential().Password
```

먼저 1회 확인이 필요하면 아래 명령을 사용합니다. smoke 결과는 본 85회와 합산하지 않습니다.

```powershell
.\.venv\Scripts\python.exe task3/task3_measure.py --repo source --cases task3/cases.json --out results-smoke-01 --mode live --repeats 1 --seed 20261014 --case-id license_planner_case_01_beginner_weak_charts
```

원 자료처럼 **각 케이스를 따로 시작하고 5회씩** 실행하는 권장 명령입니다. 출력은 17개 하위 폴더로 나뉩니다.

```powershell
.\reproduce.ps1 -Repo .\source -OutRoot .\results-live-01 -Mode live
python aggregate_results.py --logs results-live-01 --out analysis-live-01
```

단일 케이스만 실행하려면:

```powershell
.\reproduce.ps1 -Repo .\source -OutRoot .\results-tiny-01 -Mode live -CaseId availability_tiny_capacity
```

단일 케이스 결과는 전체 17개를 요구하는 aggregate_results.py에 바로 넣을 수 없습니다. 해당 runs.csv·summary.csv를 확인하세요. 원 측정기 자체는 `--case-id` 생략 시 17개 전체를 1프로세스에서 섞어 실행할 수도 있으나, 이 경우 원 자료의 케이스별 실행 순서와 달라집니다. seed=20261014는 실행 순서만 고정하며 Gemini 응답을 고정하지 않습니다. 날짜 입력도 원본 그대로 유지해야 동일 조건 비교가 가능합니다.

이번 원본에서 85건의 계획 생성에 모델 요청 387회가 관측됐습니다. 재실행의 요청 수와 사용량은 달라질 수 있습니다. 도우미는 원 측정기의 인증·모델·쿼터 stop_reason이 있으면 다음 케이스를 시작하지 않습니다. 개별 HTTP 502는 로그에 실패로 남기고 나머지 계획된 반복을 진행합니다.

끝나면:

```powershell
Remove-Item Env:GEMINI_API_KEY
Remove-Variable task3SecureKey,task3Credential
git -C source status --short
```

## 결과를 해석할 때

- 원 실험 live 폴더 시작 시각은 2026-10-15 02:10:55~03:37:05 KST입니다. 10월 14일 offline 자료와 구분하세요.
- 성공은 HTTP 200에 측정 계산까지 완료한 상태입니다. 유효 계획 여부는 constraint_valid로 별도 확인합니다.
- time_ratio_population_std는 한 계획 안에서 가용 시간창별 이용 비율의 표준편차입니다. 반복 실행 지연의 표준편차와 다릅니다.
- Pearson은 채택된 모델 분류 기준과 고정 문항 분류 기준을 함께 봅니다. 전부 정답·오답인 20회는 분산이 없어 N/A이며 0으로 대체하지 않습니다.
- live 모델은 비결정적이며 현재 서비스 접근 가능 여부는 팀원 계정에서 확인해야 합니다. 모델을 바꾸면 별도 실험으로 기록합니다.

## 무결성과 제출

SHA256SUMS.txt는 자기 자신을 제외한 패키지 파일 해시입니다. `original`은 업로드 파일 내용이 그대로이며 source 소스 사본·새 실험 로그·개인 키는 포함하지 않습니다. 로그에는 테스트용 답안·학습 일정이 포함되어 있습니다. 외부 공개 전 공개 범위를 확인하세요. 최종 제출 상태는 SUBMISSION_CHECKLIST.md와 MISSING_FILES.md를 확인합니다.
