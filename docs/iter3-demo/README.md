# Iteration 3 데모 재현 절차 (P13, 남경민)

Iteration 3 데모(컴활 2급 필기 계획 생성 → 조회 → 조정)의 기준 버전, 설치 방법, 입력값, 확인할 화면입니다.

## 1. 데모 기준 버전

| 항목 | 값 |
| --- | --- |
| 기준 브랜치·commit | `iteration-3-scheduler` [`5216f41`](https://github.com/snuhcs-course/swpp-2026-project-team-02/commit/5216f41) (11/6 배포 서버 반영 버전) |
| APK | signed `app-live-release.apk`, [Prod APK run #34](https://github.com/snuhcs-course/swpp-2026-project-team-02/actions/runs/42028937748) |
| 서명 SHA-256 | `A4:E7:BF:2B:5A:84:A4:7C:C0:C4:1B:30:40:19:A2:FC:FA:28:C9:96:8A:64:9A:4F:FA:D5:59:71:56:83:86:69` |
| 서버 | `http://swpp26t02.147-46-242-174.sslip.io/api/v1` |
| 모델 | OpenRouter `openai/gpt-6-luna` (backend 기본값) |
| 녹화 기기 | Galaxy S26 Ultra (SM-S948N), Android 17, 5G (실제 기기) |

## 2. 재현 방법

### A. 배포 서버로 재현 (평가자와 같은 환경)

1. 위 Actions run의 **Artifacts**에서 `live-release-apk-…`를 내려받아 압축을 풉니다.
2. Android 기기에 `app-live-release.apk`를 설치합니다. HE APK와 서명이 같아 업데이트 설치가 가능하며, 서명이 다른 앱(debug 빌드 등)이 있으면 삭제 후 설치합니다(기기에 저장된 계획이 사라집니다).
3. 브라우저에서 `http://swpp26t02.147-46-242-174.sslip.io/api/v1/health`가 `{"status":"ok"}`인지 확인합니다.
4. 아래 3절의 입력값으로 데모 흐름을 진행합니다.

### B. 로컬 서버로 재현 (배포 서버 없이)

```bash
# backend: OpenRouter 키는 backend/.env 의 OPENROUTER_API_KEY 에만 둡니다
cd backend
python api_server.py

# frontend: 에뮬레이터에서 로컬 서버(10.0.2.2)에 연결하는 live APK
cd frontend
./gradlew assembleLiveDebug -PlicensePlanner.apiBaseUrl=http://10.0.2.2:8000/api/v1
adb install -r app/build/outputs/apk/live/debug/app-live-debug.apk
```

### C. 서버 없이 화면 흐름만 재현

```bash
cd frontend
./gradlew installDemoDebug   # 기기 안의 데모 계산 사용, AI·서버 호출 없음
```

demo 빌드는 화면 흐름 확인용이며 AI 계획 결과가 아닙니다.

## 3. 데모 시나리오와 입력값

입력값은 HE 고정 사례(`docs/iter3-HE/02_he_cases.md`)와 같습니다.

| 항목 | 값 |
| --- | --- |
| 자격증 | 컴퓨터활용능력 2급 필기 |
| 날짜 | 준비 시작일 11/9, 시험일 11/30 |
| 실력 | 기본 기능은 알아요 |
| 예시 문항 답 | 1번 B, 2번 B, 3번 모르겠음, 4번 B, 5번 C |
| 공부 시간 | 월·수·금 19:00부터 1시간 30분, 토 10:00부터 2시간, 나머지 0시간 |

| 순서 | 동작 | 확인할 것 | UAT |
| --- | --- | --- | --- |
| 1 | 홈 → 계획 생성, 위 값 입력 | 문항 결과 "5문항 중 3문항 정답", 단원 점수 2/2 · 1/2 · 0/1 | US-B3 |
| 2 | 계획 만들기 | 생성 중 안내 표시 후 계획 화면. 월·수·금·토의 입력한 시간 안에만 일정, 겹침 없음 | US-B5 |
| 3 | 주 이동 | 날짜별 공부 토픽과 시간 확인 | US-B6 |
| 4 | 앱 완전 종료 후 재실행 → 계획 조정 | "이 기기에 저장한 계획이에요…"와 계획 1이 그대로 있음 | US-B6 |
| 5 | 첫 공부 일정 선택 → 조정 | "계획이 조정됐어요", 옮긴 일정이 가능 시간 안·겹침 없음·시험 전 | US-C3·C4 |
| 6 | 시험일 11/11, 토요일만 30분으로 새 계획 | "시험일 전까지 공부할 시간이 부족해요" 안내, 입력 수정하기 가능 | US-B7 |

AI가 계획을 만들기 때문에 같은 입력이라도 토픽별 시간은 실행마다 조금 다를 수 있습니다(`docs/iter3-ai-eval/RESULTS.md`).

## 4. 데모 영상

| 항목 | 값 |
| --- | --- |
| 파일 | [Iteration 3 데모 영상 (Google Drive)](https://drive.google.com/file/d/1iN3XWwX0RAyXgFX-ZcGzoq40OhipBL-8/view?usp=sharing) |
| 녹화 일시 | 2026-11-06 18:11 (KST) |
| 길이 | 약 2분 |
| 내용 | 3절의 순서 1~6 (계획 생성 → 주 이동 → 앱 재실행 후 재조회 → 일정 조정 → 시간 부족 안내 → 입력 수정) |

## 5. 알려진 제한

- 사전 진단 문항은 스프레드시트 일반 범위만 다루며 '컴퓨터 일반' 과목은 포함하지 않습니다.
- 배포 서버가 HTTP라서, 네트워크 환경에 따라 조정 요청이 간헐적으로 실패할 수 있습니다(HTTP 307). '다시 시도'로 복구됩니다.
- 캘린더 내보내기(US-C8)와 캘린더 일정 읽기(US-C9)는 별도 브랜치에서 개발 중이라 이 데모 기준 버전에는 포함되지 않습니다.
- 계획은 기기에 저장되므로 앱을 삭제하거나 데이터를 지우면 사라집니다.
- AI 평가에서 진단과 무관하게 '함수와 데이터 관리'에 시간이 몰리는 경향이 확인되었으며, P31에서 개선합니다.
