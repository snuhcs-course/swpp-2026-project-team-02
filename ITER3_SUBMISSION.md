# Iteration 3 제출 패키지

## APK

- 최신 signed release APK workflow: [run 41016125574](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/41016125574)
- Artifact: `live-release-apk-d8ba7688e533c06c66b00ad43aa41b6dde11a4a4`
- APK 파일: `app-live-release.apk`
- Package: `com.swpp.licenseplanner`
- 고정 인증서 SHA-256: `A4:E7:BF:2B:5A:84:A4:7C:C0:C4:1B:30:40:19:A2:FC:FA:28:C9:96:8A:64:9A:4F:FA:D5:59:71:56:83:86:69`

## 설치 방법

1. 위 GitHub Actions run의 **Artifacts**에서 최신 artifact를 내려받아 압축을 푼다.
2. Android 기기에서 `app-live-release.apk`를 설치한다.
3. 기존 release APK가 설치되어 있으면 같은 고정 서명으로 업데이트 설치할 수 있다.
4. debug 서명 앱과 충돌하면 기존 `com.swpp.licenseplanner` 앱을 제거한 뒤 설치한다. 이 경우 앱의 로컬 계획 데이터가 삭제된다.

## 평가 과제와 확인 결과

평가 과제: 외부 네트워크에서 컴활 2급 필기 계획을 생성하고 조회·조정한다.

- 공개 API: `http://swpp26t02.147-46-242-174.sslip.io/api/v1/health`
- 외부 health 응답: `{"status":"ok"}` 확인
- release APK 설치·실행 및 고정 서명 확인
- 계획 생성 → 조회 → 일정 조정 확인
- 앱 재실행 후 계획 유지 확인
- 서버 재시작 후 저장 CSV 및 계획 조회 유지 확인
- 일시적 서버 실패 뒤 서비스 복구·재시도 가능 확인

## 운영 구성

- CPU 서버는 `iteration-3-pr4-base`를 실행한다.
- `licenseplanner.service`가 자동 시작되며 Nginx가 80번 포트의 `/api/v1/`을 `127.0.0.1:8000`으로 프록시한다.
- OpenRouter 인증 정보와 모델 선택은 서버 `backend/.env`에만 둔다. APK·Git·CI에는 포함하지 않는다.
- PR #4 기반 검증에서는 OpenRouter Responses API와 `openai/gpt-6-luna` 모델로 실제 생성·조회·조정·재시작 후 조회를 확인했다.

## 알려진 제한

- 수업 공개 서버가 HTTP이므로 release APK는 `swpp26t02.147-46-242-174.sslip.io` 호스트에 대해서만 cleartext HTTP를 허용한다.
- OpenRouter 제공자 장애·쿼터·네트워크 오류로 생성 또는 조정이 실패할 수 있다. 앱은 종료하지 않고 오류를 표시하며 재시도할 수 있다.

## 보안

- OpenRouter API key, SSH private key, keystore 원본·비밀번호는 제출물, Git, Slack 메시지에 포함하지 않는다.
