# Iteration 3 제출 증빙 (준우)

> 보안 원칙: 이 문서에는 OpenRouter API 키, SSH 개인키, keystore 비밀번호 및 keystore Base64 데이터를 기록하지 않는다.

## 1. 10/30 18:00 — 제출 차단 결함 수정 · 회귀 테스트 · 서버 실행 환경 지원

### 완료 기준과 증빙

| 완료 기준 | 결과 | 증빙 |
| --- | --- | --- |
| 생성한 계획이 앱 재실행 후 남음 | 통과 | `34a5322 fix(android): persist generated plans across restarts`; release APK에서 앱 종료/재실행 후 계획 유지 확인 |
| 실제 모델 호출로 계획 생성 | 통과 | `f43b6c0 feat(backend): use OpenRouter for planning agents`; CPU 서버의 release APK에서 컴활 2급 계획 생성 확인 |
| 생성 · 조회 · 조정 정상 흐름 | 통과 | 실제 release APK에서 생성 → 조회 → 조정 확인 |
| 저장된 계획이 서버 재시작 후 남음 | 통과 | `license_planner/output/` CSV 저장 확인 후 `sudo systemctl restart licenseplanner` 및 계획 재조회 확인 |
| 오류 후 재시도 가능 | 통과 | `sudo systemctl stop licenseplanner` 상태에서 앱 오류 처리 확인 후 서비스 시작 및 재시도 성공 |
| 외부 네트워크 API 연결 | 통과 | `curl.exe http://swpp26t02.147-46-242-174.sslip.io/api/v1/health` → `{"status":"ok"}` |

### 서버 실행 환경

- CPU 서버에 `iteration-3` 브랜치를 배포했다.
- `licenseplanner.service`를 systemd에 `enabled`로 등록했다.
- 서비스는 `127.0.0.1:8000`에서 실행하고, Nginx가 80번 포트의 `/api/v1/`을 프록시한다.
- 서비스 재시작 후 health endpoint가 정상 응답함을 확인했다.
- 모델 키는 CPU 서버 `backend/.env`에만 보관하며 APK, Git, CI secret에는 포함하지 않는다.

## 2. 10/30 밤 — 배포 서버 주소 포함 1차 APK 전달

### APK 정보

- Flavor: `liveRelease`
- Application ID: `com.swpp.licenseplanner`
- API base URL: `http://swpp26t02.147-46-242-174.sslip.io/api/v1`
- CI artifact 안의 파일명: `app-live-release.apk`
- 설치 검증: Android 에뮬레이터에 signed release APK 설치 및 실행 성공

### 평가자 설치 방법

1. GitHub Actions의 성공한 `prod-apk.yml` 실행에서 `live-release-apk-<commit>` artifact를 내려받아 압축을 푼다.
2. `app-live-release.apk`를 Android 기기에 설치한다.
3. 앱에서 컴활 2급 필기 계획을 생성하고, 목록에서 조회한 뒤 일정 하나를 조정한다.

### 알려진 제한

- 수업 제공 공개 주소가 HTTP이므로, release APK는 위 `.174` 수업 호스트에 대해서만 cleartext HTTP를 허용한다.
- OpenRouter 키는 앱에 포함되지 않으며, 서버에 키가 없거나 모델 제공자가 실패하면 생성/조정이 실패할 수 있다. 앱은 종료하지 않고 오류를 표시하며 재시도할 수 있다.

## 3. 10/31 18:00 — CI prod APK 자동 빌드 · 다운로드 · 서명 고정

### 구현 커밋

| 커밋 | 내용 |
| --- | --- |
| `ae1cd7e` | fixed release keystore로 signed live release APK를 빌드하는 workflow 추가 |
| `f6b0d6b` | `iteration-3`, `main`, `v*` tag push 및 수동 실행 trigger 추가 |
| `e2ac4d0` | GitHub Actions runner 임시 keystore 경로 해석 수정 |

### CI 완료 기준

- Workflow: `.github/workflows/prod-apk.yml`
- Java 17에서 `assembleLiveRelease` 실행
- Base64 keystore를 runner 임시 경로에만 복원
- `apksigner verify --print-certs`로 서명 검증
- `app-live-release.apk` artifact를 30일 보관
- 성공 run의 artifact 1개 생성 확인
- 내려받은 `live-release-apk-e2ac4d0.../app-live-release.apk`를 로컬 `apksigner verify --verbose --print-certs`로 재검증: v2 서명 유효, signer 1개, 인증서 DN `CN=SWPP_TEAM_02, OU=SWPP, O=SNU_CSE, L=Seoul, ST=Seoul, C=KR`

### 고정 서명 지문

```text
SHA-256: A4:E7:BF:2B:5A:84:A4:7C:C0:C4:1B:30:40:19:A2:FC:FA:28:C9:96:8A:64:9A:4F:FA:D5:59:71:56:83:86:69
```

다음 CI 실행의 `Verify APK signature` 로그에 같은 SHA-256이 나오면 서명 고정이 재확인된다.

## 제출 전달 체크리스트

- [ ] 성공한 GitHub Actions run 링크 및 artifact 링크를 팀원2에게 전달
- [ ] 이 문서의 평가자 설치 방법과 HE 결과를 제출 패키지에 포함
- [ ] 실제 기기 또는 외부망에서의 최종 HE 실행 화면/결과를 기록
- [ ] 스케줄 시트 Iter3 본인 행에 실제 투입 시간 및 완료 상태 입력
