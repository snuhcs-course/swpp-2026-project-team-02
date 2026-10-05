이 문서의 상대 경로와 명령은 `backend/` 디렉터리 기준입니다. 저장소 최상위의 구조 안내는 `../README.md`를 참고하세요.
# 개발 인계 안내

## 웹 API와 프론트엔드 연결

프론트엔드 담당자용 상세 요청·응답 예시, 브라우저 호출 코드, 오류별 화면
처리 안내는 [`FRONTEND_INTEGRATION_GUIDE.md`](FRONTEND_INTEGRATION_GUIDE.md)에
정리되어 있습니다.

HTTP 경로·CORS·입력 오류와 개인 가능 시간 프로필을 적용한 API 일정 조정을
한 번에 확인하려면 `python run_api_availability_integration_test.py`를 실행합니다.
임시 localhost 서버와 임시 프로필을 사용하므로 실제 `config/user_availability.json`
은 변경되지 않습니다. 조정은 Gemini 실호출이므로 API 키, 네트워크, 쿼터가
필요합니다. 입력은 `api_test_inputs/`, 요약·결과 CSV는 `api_test_results/`에
기록됩니다.

현재 HTTP 계층은 외부 서버 없이 개발할 수 있도록 Python 표준 라이브러리로
구현되어 있습니다. `backend/` 디렉터리에서 `python api_server.py`를 실행하면
기본 주소 `http://127.0.0.1:8000`에서 API가 열립니다. 프론트엔드는
`frontend/frontend_config.json`의 `api_base_url`을 읽어 호출합니다. 정적 파일 서버는
`frontend/`만 제공해 백엔드 원본 파일이 직접 노출되지 않도록 합니다. 서버가 준비되면
프론트엔드 코드는 그대로 두고 이 주소를 배포 API 주소로 바꿀 수 있습니다.

### 설정 위치

| 설정 | 파일/환경 변수 | 용도 |
| --- | --- | --- |
| 프론트엔드 API 주소 | `frontend_config.json`의 `api_base_url` | 프론트엔드가 요청할 백엔드 주소 |
| 바인딩 주소·포트 | `config/api_server.json`의 `host`, `port` | API 서버가 수신할 주소와 포트 |
| 배포 환경 덮어쓰기 | `APP_API_HOST`, `APP_API_PORT` | 실행 환경에서 서버 주소·포트 변경 |
| CORS 허용 주소 | `config/api_server.json`의 `allowed_origins` | 브라우저 프론트엔드 origin 허용 목록 |
| API 비밀 키 | `backend/.env`의 `GEMINI_API_KEY` | 서버 전용 Gemini 인증 정보. 프론트엔드에 넣지 않음 |

로컬 프론트엔드 개발 서버가 다른 포트를 사용하면 그 origin을
`allowed_origins`에 추가합니다. 실제 배포 시에는 HTTPS 주소를 사용하고,
환경별 설정 파일 또는 배포 환경 변수를 통해 서버 주소와 CORS origin을
지정합니다. `.env`에는 Gemini API 키만 둡니다.

### 엔드포인트

| Method / 경로 | 목적 | 요청 핵심 필드 |
| --- | --- | --- |
| `GET /api/v1/health` | 연결 상태 확인 | 없음 |
| `GET /api/v1/certifications` | 활성 자격증 목록 | 없음 |
| `GET /api/v1/certifications/{certification_id}/questions` | 더미 진단 질문·선택지·정답 | 없음 |
| `GET /api/v1/availability` | 저장된 반복 가능 시간 조회 | 없음 |
| `PUT /api/v1/availability` | 반복 가능 시간 저장 | 요일별 `start`, `end` 배열 |
| `POST /api/v1/study-plans` | 학습 계획 생성 | 자기평가, 로컬 채점 `assessment_results`, 준비/시험 시각, 외부 일정, 자격증 ID |
| `GET /api/v1/study-plans/{filename}` | 생성된 CSV 다운로드 | 서버가 반환한 파일명 |
| `POST /api/v1/schedules/adjust` | 공부 일정 이동 또는 취소 외부 일정 제거 | 대상 `schedule_id`, 전체 일정 |

일정 날짜·시간은 `YYYY/MM/DD/HH/MM`, 반복 시간은 `HH:MM`입니다. 학습 계획
요청 예시:

```json
{
  "self_assessment": "함수에 익숙하지 않습니다.",
  "assessment_results": {
    "certification_id": "computer_specialist_level_2",
    "results": [{"problem_id": 1, "question": "...", "choices": {"A": "...", "B": "..."},
      "correct_answer": "B", "user_answer": "A", "is_correct": false, "possible_score": 1}]
  },
  "preparation_start": "2026/10/05/18/00",
  "exam_date": "2026/11/30/23/00",
  "certification_id": "computer_specialist_level_2",
  "busy_schedules": [
    {"schedule_id": "12", "start_date": "2026/10/07/13/00",
     "end_date": "2026/10/07/15/00", "topic": ""}
  ],
  "weekly_availability": {
    "monday": [{"start": "18:00", "end": "21:00"}]
  }
}
```

일정 생성 응답에는 화면 표시에 쓸 `schedules` 배열, `schedule_csv` 문자열,
저장 CSV 다운로드 경로 `schedule_csv_url`이 포함됩니다. 진단 질문 API는
더미 문항의 정답도 제공하며, FE가 기본 비교 로직으로 `is_correct`를 계산합니다.
Planning API는 재채점하지 않고, Agent가 문항마다 자격증의 고정 Topic 하나 이상에 가중치로 매핑합니다.
정상 요청은 HTTP 200, 잘못된
입력은 HTTP 422, Gemini 인증/통신 실패는 각각 HTTP 503/502, 알 수 없는
내부 오류는 HTTP 500의 `{ "error": { "code", "message" } }` 형태입니다.
운영 로그나 응답에 API 키를 기록하지 않습니다.

API를 실행하는 명령은 `python api_server.py`입니다. 기본 서버 설정은
`config/api_server.json`에서 바꾸고, 호스트·포트는 `APP_API_HOST`와
`APP_API_PORT` 환경 변수로 덮어쓸 수 있습니다. 현재 저장된 가능 시간은
개인용 단일 JSON 파일이므로 다중 사용자 배포 전에 사용자별 저장소가
필요한지 검토해야 합니다.

이 문서는 별도로 실행할 수 있는 License Planner와 Schedule Adjuster의 구현, 입력/출력, 실행 방법을 정리합니다. 프로그램과 테스트 자료는 서로 다른 디렉터리로 나뉘며, 사용자 주간 가능 시간 파일 `config/user_availability.json`은 두 프로그램이 공유합니다.

## License Planner

License Planner MVP는 자기평가, 문항별 시험 결과, 준비 기간, 기존 일정을 받아 취약 토픽을 먼저 배치한 학습 일정을 CSV로 반환합니다. 날짜·시간 형식은 `YYYY/MM/DD/HH/MM`입니다. Gemini Agent는 날짜별·주제별 공부 시간 배분을 제안하고, Python 코드가 점수 통계와 시간표·기존 일정 검증 및 실제 시간 배치를 수행합니다.

## 현재 구현 범위

- Gemini Agent가 자기평가 문장과 문항별·토픽별 점수를 직접 참고해 학습 분량을 정합니다. 수준을 3단계로 구간화하거나 배수를 적용하지 않습니다.
- 준비 시작 시각과 시험 시각 사이에 Agent가 정한 토픽별 학습 시간을 배치합니다.
- 날짜·시간 단위의 기존 일정 CSV와 겹치지 않는 슬롯을 찾고, 기존 일정도 결과에 포함합니다.
- CSV 출력의 일정 ID는 외부 일정 `external-번호`, 공부 일정 `study-번호` 형식입니다.
- Gemini function calling을 통해 자격증 조회, 수준 평가, 일정 생성 도구를 호출하고, 도구 호출 기록을 확인할 수 있습니다.

온라인 시험 자료 검색, 공식 시험 범위 자동 갱신, 실제 학습 콘텐츠 추천은 현재 범위에 포함하지 않습니다. 사용자별 반복 주간 공부 가능 시간은 JSON 프로필로 저장하고 일정 생성에 반영합니다.

기획서에는 전체 공부 기간을 2~3주로 고정한다는 기준이 없습니다. 계획은 전달된 준비 시작 시각과 목표 시험 시각 사이에서 수준·주제별 학습량·가능 시간을 반영합니다. 2~3주 제한 또는 권장 기간을 추가하려면 별도 제품 규칙을 먼저 정해야 합니다.

## 디렉터리와 파일

| 경로 | 역할 |
| --- | --- |
| `run_planner.py` | 단일 사용자 요청을 처리하는 CLI 진입점 |
| `api_server.py` | 로컬/배포 환경에서 HTTP API를 실행하는 표준 라이브러리 서버 |
| `api_service.py` | API JSON과 두 프로그램의 도메인 객체/Agent 사이 변환 |
| `config/api_server.json` | API bind 주소·포트, 허용 origin, 요청 크기 제한 |
| `frontend_config.json` | 프론트엔드가 읽는 API base URL 기본값 |
| `run_api_availability_integration_test.py` | 임시 프로필을 써서 HTTP·CORS·Agent 조정 전체 흐름 확인 |
| `api_test_inputs/`, `api_test_results/` | API 통합 테스트 입력 fixture와 요약/CSV 결과 |
| `run_license_planner_test_cases.py` | 여러 실제 Gemini Agent 테스트를 실행하고 케이스별 결과/trace를 저장 |
| `run_license_planner_availability_test_cases.py` | 개인 가능 시간 저장·갱신·계획 반영을 API 없이 검증하는 테스트 러너 |
| `license_planner/agent.py` | Gemini 모델 설정, ReAct 호출 루프, API 통신 |
| `license_planner/tools.py` | Agent의 자격증 조회·점수 분석·수준 평가·일정 생성 도구와 스키마 |
| `config/certifications.json` | 자격증 ID별 표시 이름과 학습 Topic 목록 |
| `license_planner/catalog.py` | JSON 자격증 설정을 읽고 검증하는 카탈로그 |
| `license_planner/scheduler.py` | 토픽 통계, 수준 판정, 시간 슬롯 배치 규칙 |
| `license_planner/csv_io.py` | 기존 일정 CSV 파싱 및 결과 CSV 생성 |
| `license_planner/models.py` | 사용자 요청, 일정 기간, 공부 블록 등 도메인 객체 |
| `license_planner/settings.py` | CSV/날짜·시간 형식과 `.env` API 키 읽기 |
| `config/license_planner.json` | 레거시 설정 파일. 고정 세션 길이·일일 횟수·토픽별 세션 정책은 사용하지 않음 |
| `license_planner/user_availability.py` | 개인용 주간 가능 시간 검증, JSON 저장 및 불러오기 |
| `config/user_availability.json` | 개인 앱의 주간 가능 시간 설정. 로컬 사용자 데이터라 Git에서 제외 |
| `.env` / `.env.example` | Gemini API 키 설정 및 설정 예시. 실제 키 파일은 Git에서 제외됩니다. |
| `license_planner_tests/test_license_planner.py` | API를 호출하지 않는 로컬 단위 테스트 |
| `license_planner_availability_test_inputs/` | 개인 가능 시간 저장/갱신/요일·시간 적용/기본값/입력 오류 케이스 JSON |
| `license_planner_availability_test_results/` | 케이스별 계획 CSV와 도구 trace, 통합 요약 JSON |
| `license_planner_test_inputs/` | Planning API 요청 모양의 JSON 통합 테스트 케이스 |
| `license_planner_test_results/` | 케이스별 결과 CSV, 개별 도구 trace JSON, 통합 요약 JSON |

## 실행 흐름

```text
CLI 또는 앱 호출
  → JSON 입력 배열, 날짜, 점수, 주간 가능 시간 검증
  → Gemini Agent에 입력과 도구 스키마 전달
  → get_certification_profile
  → analyze_exam_results
  → build_study_schedule
  → 기존 일정 + 공부 일정을 CSV로 합쳐 반환
```

Agent에는 파일 접근이나 웹 검색 도구를 주지 않습니다. 모델은 사용자의 자기평가와 실제 점수 통계를 해석해 날짜별 공부 시간을 제안합니다. Python 도구는 입력 검증, 충돌 검사, 가용 시간 안에 일정 행 배치를 담당합니다.

## 입력과 검증 규칙

| 입력 | 형식 | 규칙 |
| --- | --- | --- |
| `self_assessment` | 문장 또는 `None` | Agent가 문장 내용을 직접 학습 분량 산정에 참고합니다. 고정된 수준 구간으로 변환하지 않습니다. |
| `problem_results` | 문제 결과 객체 튜플 또는 `None` | 각 행은 문제 ID, 토픽명, 배점, 획득 점수입니다. 토픽명은 데이터에서 받아들이며 고정 enum을 쓰지 않습니다. |
| `preparation_start` | datetime 또는 `None` | 형식은 `YYYY/MM/DD/HH/MM`; 날짜 일정 생성 시 필수입니다. |
| `exam_date` | datetime 또는 `None` | 같은 형식이며 시작 시각보다 뒤여야 합니다. |
| `busy_periods` | 기존 일정 객체 튜플 또는 `None` | API JSON의 `busy_schedules` 배열에서 변환됩니다. 시작·종료는 `YYYY/MM/DD/HH/MM`이며 ID는 양의 정수입니다. |
| `certification_id` | 카탈로그 ID | 기본값은 `computer_specialist_level_2`입니다. |
| `study_days_per_week` | 정수 | 구버전 요청 호환 필드이며 실제 공부 요일은 `weekly_availability`로만 결정합니다. |
| `weekly_availability` | 주간 시간표 또는 `None` | 최소 한 개의 요일별 `HH:MM` 시작·종료 구간이 필요합니다. 저장 시간표도 없으면 요청 오류를 반환하며 공통 시간대를 대신 쓰지 않습니다. |

문항별 결과가 있으면 토픽별 획득 점수·배점 비율을 계산해 Agent에 제공합니다. Agent는 이를 자기평가 문장과 함께 연속적인 입력 근거로 사용하며, 초급·중급·고급 분류나 수준별 시간 배수를 적용하지 않습니다. 결과가 없으면 자격증 설정의 기본 토픽을 사용합니다.

## 입력·출력 및 오류 형식

이 절은 License Planner의 입력·출력 필드와 표현 규칙을 정리합니다. HTTP API와 로컬 CLI는 `assessment_results` JSON을 받습니다. 질문 CLI/FE가 정답 여부를 계산하며 API는 다시 채점하지 않습니다. Agent는 문제 내용으로 고정 Topic 복수개와 기여 비중을 판단합니다. HTTP 경로와 응답 형식은 앞의 `웹 API와 프론트엔드 연결` 절 및 `FRONTEND_INTEGRATION_GUIDE.md`를 기준으로 합니다.

### 입력 데이터

| 필드 | 자료형 | 필수 여부와 규칙 |
| --- | --- | --- |
| `self_assessment` | 문자열 또는 `null` | 선택. 사용자가 스스로의 수준을 설명하는 문장입니다. |
| `assessment_results` | 객체 | HTTP API 필수. `certification_id`와 `results` 배열을 포함합니다. 각 결과는 `problem_id`, `question`, `choices`, `correct_answer`, `user_answer`, `is_correct`, `possible_score`입니다. |
| `preparation_start` | 날짜·시간 문자열 | 필수. 형식은 `YYYY/MM/DD/HH/MM`입니다. |
| `exam_date` | 날짜·시간 문자열 | 필수이며 준비 시작보다 늦어야 합니다. 형식은 `YYYY/MM/DD/HH/MM`입니다. |
| `busy_schedules` | 일정 객체 배열 또는 `null` | 선택. 각 일정은 숫자형 문자열 ID와 시작·종료 시각을 가집니다. 일정 구간은 시작 포함·종료 제외입니다. |
| `certification_id` | 문자열 | 선택. 기본값은 `computer_specialist_level_2`입니다. |
| `study_days_per_week` | 정수 | 구버전 요청 호환 필드입니다. 실제 계획 요일은 `weekly_availability`에서 고르며 이 값은 세션 수나 일일 공부량을 제한하지 않습니다. |
| `weekly_availability` | 요일별 객체 또는 `null` | 최소 한 개의 시간 구간이 필요합니다. 제공한 시간표를 요청에 적용하며 저장하지 않습니다. 생략하거나 `null`이면 저장 프로필을 사용하고, 저장 프로필도 없으면 HTTP 422 `INVALID_INPUT`을 반환합니다. 지속 저장은 `PUT /api/v1/availability`로 수행합니다. |

예시:

```json
{
  "self_assessment": "엑셀 함수는 처음입니다.",
  "assessment_results": {
    "certification_id": "computer_specialist_level_2",
    "results": [{"problem_id": 1, "question": "...", "choices": {"A": "...", "B": "..."},
      "correct_answer": "B", "user_answer": "A", "is_correct": false, "possible_score": 1}]
  },
  "preparation_start": "2026/10/05/17/00",
  "exam_date": "2026/11/30/23/00",
  "busy_schedules": [
    {
      "schedule_id": "101",
      "start_date": "2026/10/12/18/00",
      "end_date": "2026/10/12/20/00"
    }
  ],
  "certification_id": "computer_specialist_level_2",
  "study_days_per_week": 5,
  "weekly_availability": {
    "monday": [{"start": "18:00", "end": "21:00"}],
    "wednesday": [{"start": "09:00", "end": "12:00"}],
    "saturday": [{"start": "10:00", "end": "14:00"}]
  }
}
```

가능 시간 JSON의 요일 키는 `monday`부터 `sunday`까지이며, 하루 안의 각 구간은 `{"start":"HH:MM","end":"HH:MM"}` 형식입니다. 같은 요일의 구간은 서로 겹칠 수 없습니다. 빠진 요일은 공부 불가로 취급합니다. 모든 요일이 비어 있거나 요청과 저장 프로필 모두에 시간이 없으면 `weekly_availability is required. Enter at least one weekday and available time window.` 오류를 반환합니다.

진단은 HTTP 요청의 `assessment_results` JSON으로 제출합니다. 입력 구조 오류, 중복 문제 ID, 빈 질문/답변, 잘못된 Topic 매핑은 HTTP 422를 반환합니다. Planning Agent는 문제마다 여러 Topic을 지정할 수 있고 각 문제의 비중 합은 1이어야 합니다. 바쁜 일정은 JSON 배열이며 생략하거나 비우면 기존 일정이 없는 것으로 처리합니다. 기존 일정은 출력 CSV에서 `external-` 접두어가 붙습니다.

### 성공 출력 데이터

일정 데이터의 CSV 표현은 UTF-8이며 헤더는 `schedule_id,start_date,end_date,topic`입니다. 시작·종료 시각은 `YYYY/MM/DD/HH/MM`, 공부 ID는 `study-숫자`, 외부 ID는 `external-숫자`입니다. 네 번째 `topic` 열에는 공부 세션 주제를 기록하며 외부 일정은 빈 값일 수 있습니다. 외부 일정은 결과에 포함되며 시작 시각 순으로 정렬됩니다.

```csv
schedule_id,start_date,end_date,topic
study-102,2026/10/05/18/00,2026/10/05/19/00,Charts
external-101,2026/10/12/18/00,2026/10/12/20/00,
```

CLI 성공 응답은 CSV를 stdout에, Agent 요약과 도구 호출명을 stderr에 기록합니다. HTTP `POST /api/v1/study-plans`는 `schedules` 배열, CSV 문자열 `schedule_csv`, 저장 경로 `schedule_csv_file`, 다운로드 API 경로 `schedule_csv_url`, `agent_summary`, `tool_calls`를 반환합니다. CSV 파일은 `backend/license_planner/output/`에 요청별 고유 이름으로 저장됩니다. 이 API 응답에는 별도 진단 점수 보고 객체는 포함하지 않습니다. 조정 API 응답에는 추가로 `action`과 `target_schedule_id`가 있습니다.

### 오류 데이터

HTTP API 오류는 `error.code`와 `error.message`를 반환합니다. 현재 실제 API 오류 형식은 다음과 같습니다.

```json
{
  "error": {
    "code": "INVALID_INPUT",
    "message": "busy_schedules must be arrays or null"
  }
}
```

| HTTP 상태 | 코드 | 발생 조건 |
| --- | --- | --- |
| 404 | `NOT_FOUND` | API 경로가 존재하지 않음 |
| 422 | `INVALID_INPUT` | 요청 JSON의 필드·형식·날짜·점수가 유효하지 않음 |
| 502 | `UPSTREAM_ERROR` | Gemini 등 외부 서비스 통신 실패 |
| 503 | `UPSTREAM_ERROR` | Gemini API 키 등 서버 측 upstream 설정이 누락됨 |
| 500 | `INTERNAL_ERROR` | 예측하지 못한 서버 오류 |

계획 생성 불가나 일정 배치 실패는 도구/Agent 실행 오류를 거쳐 현재 구현에서 HTTP 502로 반환될 수 있습니다. CLI는 HTTP 형식과 별도로 stderr에 `ERROR: 메시지`, 종료 코드 2를 사용합니다.

## 점수와 일정 계산

현재는 점수 경계나 자기평가 키워드로 준비 수준을 3단계 분류하지 않습니다. Gemini Agent가 원문 자기평가와 토픽별 점수 통계를 직접 참고해 학습량을 정합니다.

문항 결과의 토픽별 획득 점수/배점 비율을 계산해 낮은 토픽을 Agent에 전달합니다. Gemini Agent는 시험일까지의 기간, 현재 수준, 토픽별 점수와 사용자가 입력한 실제 가능 시간·기존 일정을 참고해 날짜별·주제별 총 학습 시간(분)을 정합니다. 문제 토픽 이름은 입력에서 읽으며 고정 enum에 제한되지 않습니다.

Agent는 날짜별·주제별 공부 시간(분)을 직접 제안합니다. 예를 들어 총 120분이고 용량이 비슷한 공부 가능 날짜가 2일이면 날짜별 약 60분씩 배정하도록 안내합니다. 하루 블록 수나 블록 길이를 1시간으로 제한하지 않습니다. Python 배치기는 제안된 시간을 사용자의 요일별 가능 시간 안에 놓고, 기존 일정과 겹치거나 시험 시각을 넘는 경우 및 하루 가용 시간을 초과하는 요청은 거부합니다. 배치할 수 없으면 `NOT_ENOUGH_TIME` 오류입니다.

## 출력 형식과 일정 ID

결과는 `schedule_id,start_date,end_date,topic` 네 열 CSV입니다. 시작·종료 값은 `YYYY/MM/DD/HH/MM` 형식이고, 입력 외부 일정 ID에는 접두어를 붙입니다. 공부 일정에는 해당 세션의 주제를 넣습니다.

```csv
schedule_id,start_date,end_date,topic
study-104,2026/10/05/18/00,2026/10/05/19/00,Functions
external-101,2026/10/12/18/00,2026/10/12/20/00,
```

외부 입력 CSV의 ID는 숫자로 유지합니다. 새 공부 일정 번호는 기존 숫자 ID 최댓값 다음부터 배정하고, 출력에서 `study-`를 붙입니다. 외부 행은 `external-`를 붙입니다. 결과는 시작 시각 기준으로 정렬됩니다. 공부 일정의 `topic` 열은 assessment 문제를 JSON 자격증 카탈로그에 맞춰 Agent가 분류한 Topic입니다.

## 설정과 실행

`.env`에는 `GEMINI_API_KEY`만 설정합니다. 모델명(`gemini-3.5-flash-lite`), endpoint, 요청 시간 제한, Agent 반복 횟수 및 토픽별 학습 시간 산정 지시는 [agent.py](license_planner/agent.py)에 있습니다. 자격증별 표시 이름과 Topic 목록은 [config/certifications.json](config/certifications.json)에 있습니다. 자격증을 추가할 때 이 JSON에 고유 ID, 표시 이름, Topic 배열을 추가하면 프로필과 Agent 분류 스키마가 그 자격증의 Topic 목록을 사용합니다. 개인용 주간 가능 시간은 [config/user_availability.json](config/user_availability.json)에 저장됩니다. 주간 가능 시간이 없는 상태에서 공통 시간대로 계획을 생성하지 않습니다.

Python 3.10 이상을 사용하고, 이 프로젝트는 현재 Python 표준 라이브러리만 사용합니다. 단일 요청 실행 예시:

```powershell
python -m license_planner.cli `
  --request-json assessment_questionnaire/plan_request.example.json `
  --assessment-results-json assessment_questionnaire/output/assessment_results.json `
  1> license_planner_test_results/manual_result.csv `
  2> license_planner_test_results/manual_agent_log.txt
```

`manual_request.json`은 위 API 예시와 같은 JSON 객체입니다. 주간 가능 시간은 `weekly_availability` 객체의 요일별 `start`/`end` 구간으로 지정합니다. 프론트엔드는 로컬 채점된 `assessment_results`를 API에 보내며, 주간 시간표 저장은 `PUT /api/v1/availability`를 사용합니다. CLI는 `--assessment-results-json` 옵션으로 진단 결과 JSON 파일을 받을 수 있습니다.

계획 CSV 결과는 표준 출력으로도 제공되며, API 서비스는 생성 결과를 `license_planner/output/`에 저장합니다. HTTP 응답의 `schedule_csv_url`은 그 파일을 내려받는 GET 경로입니다. CLI에서 stdout 결과를 파일로 저장하려면 PowerShell 리디렉션(`python -m license_planner.cli ... > plan.csv`)을 사용할 수 있습니다. Agent 요약과 도구 호출명은 표준 오류로 나옵니다.

## 테스트와 도구 호출 기록

로컬 단위 테스트는 네트워크 요청 없이 실행합니다.

```powershell
python -m unittest discover -s license_planner_tests -p "test_license_planner.py" -v
python run_license_planner_availability_test_cases.py
```

개인 가능 시간 테스트 러너는 Gemini API 키 없이 동작하며 각 케이스에서 임시 JSON 파일을 만들어 설정 저장·불러오기·갱신을 검증합니다. 정상 계획은 케이스별 CSV, 첫 시작 시각과 호출한 로컬 도구는 trace JSON에 기록합니다. 입력 정의는 `license_planner_availability_test_inputs/license_planner_availability_test_cases.json`, 출력은 `license_planner_availability_test_results/`에서 확인합니다.

실제 모델과 도구 호출을 확인하려면 API 키가 설정된 상태에서 다음을 실행합니다. Gemini API 요청이 케이스마다 발생합니다.

```powershell
python run_license_planner_test_cases.py --case-id license_planner_case_04_personal_availability_api
```

이 통합 러너는 임시 로컬 HTTP 서버를 띄우고 각 JSON 본문을 실제 `POST /api/v1/study-plans` 경로로 보냅니다. 각 계획 요청은 Gemini에 실제 요청을 하며, API의 HTTP 200 응답, 필수 도구 호출 순서, 첫 주제·시작 시각, 요일별 가능 시간 준수, 바쁜 일정 충돌 여부를 검사합니다. 결과 CSV와 상세 trace는 `license_planner_test_results/`에 기록됩니다. 요약 파일은 마지막 실행 결과로 덮어쓰므로, 이전 통과 기록이 현재 API 쿼터나 네트워크 상태를 보장하지 않습니다. `--case-id`를 생략하면 전체 Gemini 케이스를 실행합니다. HTTP 429가 발생하면 AI Studio에서 프로젝트별 모델 한도와 사용량을 확인하고 한도 회복 후 재실행합니다.

각 케이스는 `license_planner_test_inputs/license_planner_test_cases.json`에서 `request` 객체에 API 요청 필드 전체를 JSON으로 지정합니다. `assessment_results`, `busy_schedules`, `weekly_availability`를 포함해 HTTP API 계약을 확인합니다. 실제 API 응답과 일정은 통합 테스트에서 검증합니다. 실행 결과는 다음처럼 저장됩니다.

- `<case_id>_result.csv`: 외부 일정과 공부 일정
- `<case_id>_tool_trace.json`: HTTP 상태, 실제 호출 순서/인자, 예상 및 실제 일정, 통과 여부
- `license_planner_test_summary.json`: 모든 케이스의 요약

통합 테스트 통과 기준은 HTTP 200, 프로필 조회 → 시험 결과 분석 → 수준 평가 → 일정 생성 순서, 예상 취약 토픽·시작 시각, 요일별 가능 시간 준수, 바쁜 일정과의 충돌 없음입니다. 케이스에는 초급/고급 사용자와 카탈로그 밖의 시험 토픽을 넣어 확장 가능성을 확인합니다.

## 자격증 추가와 확장

새 시험을 추가하려면 `config/certifications.json`의 `certifications` 배열에 고유 ID, 표시 이름, Topic 목록을 추가합니다. 문항 결과는 Agent가 해당 자격증 목록의 Topic 여러 개에 비중으로 연결합니다. 도구 스키마의 자격증 ID와 Topic enum은 JSON 설정에서 자동으로 생성됩니다. 새 시험의 assessment JSON 및 테스트 케이스도 추가합니다.

향후 점수 기준을 확정하려면 진단 문제와 문제 난이도 자료가 필요합니다. 공식 시험 범위나 학습 자료를 온라인에서 가져오려면 출처가 검증되는 별도 데이터 도구를 추가해야 하며, 현재 코드는 온라인 검색을 수행하지 않습니다.

## Schedule Adjuster

Schedule Adjuster는 기존 일정 CSV와 변경 대상 `schedule_id`를 받아 실패한 자격증 공부 일정을 옮기거나 취소된 외부 일정을 제거합니다. Gemini Agent는 적절한 변경 도구를 선택하고, 일정 검증·이동·삭제·CSV 직렬화는 Python 코드가 수행합니다. 이 앱은 License Planner와 독립적으로 실행할 수 있습니다.

### 기능과 동작 규칙

- `study-번호`를 받으면 공부 일정으로 취급합니다. 기존 기간 길이와 주제를 유지하고 같은 주제의 기존 세션 이후로 이동합니다. 공유 개인 시간표가 있으면 그 요일·시간 안에서 다른 일정과 겹치지 않는 첫 구간을 선택합니다.
- `external-번호`를 받으면 취소된 외부 일정으로 취급하고 해당 행만 결과에서 제거합니다.
- 변경 대상이 아닌 일정 행은 원래 시작 시각과 종료 시각을 유지합니다. 결과 CSV는 시작 시각과 ID 기준으로 정렬됩니다.
- 일정 구간은 시작 포함·종료 제외로 처리합니다. 개인 시간표는 `config/user_availability.json`에서 자동으로 읽으며 `--availability-file`로 다른 파일을 지정할 수 있습니다. 시간표 파일이 없으면 기존 충돌 회피 규칙만 적용하고, 공휴일과 시험일은 현재 반영하지 않습니다.
- 대상 ID가 입력 CSV에 없거나 ID 접두어/번호 형식이 잘못되면 오류를 반환합니다. 일정 ID는 중복될 수 없습니다.
- 현재 Agent는 `inspect_schedule`을 먼저 호출합니다. 주제가 있는 공부 일정이면 `inspect_topic_schedule`로 같은 주제의 세션을 확인한 뒤, 일정 유형에 맞는 변경 도구를 호출합니다.

### 입력 및 출력

| 항목 | 형식 | 규칙 |
| --- | --- | --- |
| `schedule_id` | 문자열 | `study-양의정수` 또는 `external-양의정수`; CSV에 있는 대상 ID여야 합니다. |
| `schedule_csv` | 파일 경로 또는 CSV 문자열 | CLI에서는 CSV 경로를 받고, 새 열 순서는 `schedule_id,start_date,end_date,topic`입니다. 기존 3열 CSV도 읽으며 영어 또는 한국어 헤더를 허용합니다. |
| 개인 가능 시간 | `config/user_availability.json` 또는 `--availability-file` JSON | 선택. `weekly_availability` 아래 요일별 `start`/`end` 시각을 지정합니다. 세션 길이를 수용할 수 있는 구간이 없으면 조정 오류를 반환합니다. |
| 날짜·시간 | `YYYY/MM/DD/HH/MM` | 시작 시각은 종료 시각보다 빨라야 합니다. 구간은 시작 포함·종료 제외입니다. |
| 출력 CSV | `schedule_id,start_date,end_date,topic` | 공부 주제를 보존한 조정 후 전체 일정을 반환합니다. 외부 일정은 주제가 없으면 빈 값입니다. |

예시 입력:

```csv
schedule_id,start_date,end_date,topic
external-101,2026/10/06/18/00,2026/10/06/20/00,
study-201,2026/10/05/19/00,2026/10/05/20/00,Charts
study-202,2026/10/08/18/00,2026/10/08/20/00,Functions
```

`study-201`을 이동하면 ID·주제·기간 길이는 유지됩니다. 주제가 있는 경우 같은 주제의 다른 세션 중 가장 늦게 끝나는 시각 이후부터 빈 구간을 찾고, 개인 시간표가 있으면 해당 시간표 안으로 제한합니다. `external-101`이 취소되면 해당 행만 제거되고 나머지는 보존됩니다. CLI는 결과 CSV를 stdout에, 작업 요약과 도구 호출명을 stderr에 씁니다.

### 입력·출력 및 오류 형식

이 절은 Schedule Adjuster의 논리 입력 및 CSV 형식을 설명합니다. 웹 API 경로, HTTP 상태와 프론트엔드 연동 방식은 이 문서 앞의 `웹 API와 프론트엔드 연결` 절 및 `FRONTEND_INTEGRATION_GUIDE.md`를 기준으로 합니다. 아래 요청 예시는 도메인 수준 입력 형태입니다. 실제 HTTP 요청에서는 일정 배열 키가 `schedule`이 아니라 `schedules`이며 JSON 응답을 반환합니다. CLI는 `schedule_id`와 CSV 파일 경로를 인자로 받아 CSV 내용을 `AdjustmentRequest`로 변환합니다.

입력은 변경 대상 ID 하나와 원본 일정 전체입니다. 일정 ID는 `study-` 또는 `external-` 접두어 뒤에 양의 정수가 와야 하며, 그 ID가 CSV에 존재해야 합니다. 새 CSV는 네 열(`schedule_id,start_date,end_date,topic`)을 사용하며 기존 세 열 CSV도 입력으로 허용합니다. 시간 값은 `YYYY/MM/DD/HH/MM` 형식입니다. 영어 헤더와 대응하는 한국어 헤더를 허용합니다.

```json
{
  "schedule_id": "study-201",
  "schedule": [
    {
      "schedule_id": "external-101",
      "start_date": "2026/10/06/18/00",
      "end_date": "2026/10/06/20/00",
      "topic": ""
    },
    {
      "schedule_id": "study-201",
      "start_date": "2026/10/05/19/00",
      "end_date": "2026/10/05/20/00",
      "topic": "Charts"
    }
  ]
}
```

성공 출력은 네 열의 CSV입니다. 공부 일정 이동은 ID, 주제, 기존 기간 길이를 유지하면서 날짜를 바꾸고, 취소된 외부 일정은 해당 행만 결과에서 제거합니다. 나머지 행은 그대로 출력합니다.

```csv
schedule_id,start_date,end_date,topic
study-201,2026/10/08/20/00,2026/10/08/21/00,Charts
external-101,2026/10/06/18/00,2026/10/06/20/00,
```

논리 입력 오류에는 ID 오류, 일정 미존재, CSV·날짜 형식 오류, 잘못된 일정 유형, 가능 시간 부족 등이 있습니다. CLI는 이를 `ERROR: 메시지`와 종료 코드 2로 표시하며, 웹 API는 `INVALID_INPUT`, `UPSTREAM_ERROR`, `NOT_FOUND`, `INTERNAL_ERROR` 코드를 가진 JSON 오류로 변환합니다. 프론트엔드에서 사용하는 실제 오류 응답은 아래 예시 형식입니다.

```json
{
  "error": {
    "code": "INVALID_INPUT",
    "message": "start_date: datetime must use exact YYYY/MM/DD/HH/MM format"
  }
}
```

| 오류 코드 | 발생 조건 |
| --- | --- |
| `INVALID_SCHEDULE_ID` | ID 접두어가 `study-`/`external-`가 아니거나 뒤에 양의 정수가 없음 |
| `SCHEDULE_NOT_FOUND` | 대상 ID가 원본 CSV에 없음 |
| `INVALID_CSV` | CSV가 비었거나 행의 열 수가 세 개가 아님 |
| `DUPLICATE_SCHEDULE_ID` | 입력 CSV에 동일 ID가 두 번 이상 있음 |
| `INVALID_DATE` | 날짜·시간이 `YYYY/MM/DD/HH/MM` 형식이 아니거나 실제 시각이 아님 |
| `INVALID_DATE_RANGE` | 시작 시각이 종료 시각 이상임 |
| `WRONG_SCHEDULE_TYPE` | 공부 일정에 외부 일정 취소 도구를 호출하거나 반대로 호출함 |
| `TOOL_PREREQUISITE_FAILED` | 대상 조회 전에 변경 도구를 호출함 |
| `NO_AVAILABILITY_WINDOW` | 사용자 주간 시간표에 세션 길이를 수용할 수 있는 구간이 없음 |
| `AGENT_ERROR` | Gemini 통신 실패, 최대 반복 초과, 변경 도구 미호출 등 Agent 실행 실패 |

CLI는 오류를 stderr에 `ERROR: 메시지` 형식으로 출력하고 종료 코드 2를 반환합니다. 웹 API는 입력 검증 오류를 HTTP 422, Gemini 인증·통신 오류를 HTTP 503/502로 반환하며 응답 본문에 `error.code`와 `error.message`를 담습니다. 실제 API 응답 계약은 `FRONTEND_INTEGRATION_GUIDE.md`를 참고합니다.

### 폴더 및 파일

| 경로 | 역할 |
| --- | --- |
| `schedule_adjuster/cli.py` | `--schedule-id`, `--schedule-csv`, 선택적 `--availability-file` 인자를 받는 CLI 진입점 |
| `schedule_adjuster/models.py` | 일정 행, 조정 요청, 결과, 도구 trace 데이터 객체 |
| `schedule_adjuster/csv_io.py` | CSV 파싱/검증 및 출력 CSV 생성 |
| `schedule_adjuster/schedule_logic.py` | ID 판정, 기간 충돌 검사, 사용자 가능 시간 안에서 공부 일정 이동, 외부 일정 제거 규칙 |
| `schedule_adjuster/availability.py` | 공유 개인 시간 JSON 로드 및 형식 검증 |
| `schedule_adjuster/tools.py` | Agent 도구 함수, 입력 검증, 도구 스키마 및 호출 이력 |
| `schedule_adjuster/agent.py` | Gemini function calling ReAct 루프와 API 통신 |
| `schedule_adjuster/settings.py` | API 키 로딩, 모델/API/Agent 설정, CSV 및 날짜 설정 |
| `schedule_adjuster/README.md` | Schedule Adjuster 단독 실행 및 테스트 설명 |
| `schedule_adjuster_tests/test_schedule_adjuster.py` | API 없이 실행하는 로컬 단위 테스트 |
| `schedule_adjuster_test_inputs/` | 여러 통합 테스트 설정과 입력 CSV |
| `schedule_adjuster_test_results/` | 테스트 결과 CSV, 케이스별 도구 trace, 마지막 통합 실행 요약 |
| `run_schedule_adjuster_test_cases.py` | 설정에 정의된 케이스들을 실제 Gemini Agent로 실행하는 통합 테스트 러너 |

### Agent 도구 흐름

1. `inspect_schedule(schedule_id)`는 정확한 ID가 원본 일정에 있는지, 외부/공부 유형, 주제 및 적용 가능한 개인 주간 시간표를 조회합니다.
2. 주제가 있는 공부 일정은 `inspect_topic_schedule(topic)`로 실패 대상 외에 같은 주제의 공부 일정을 가져옵니다.
3. `reschedule_failed_study(schedule_id)`는 같은 주제의 다른 일정 이후 개인 시간표 안의 충돌 없는 구간으로 옮기며, 시간표 파일이 없을 때는 기존 규칙으로 처리합니다.
4. 외부 일정이면 `remove_cancelled_external_schedule(schedule_id)`가 취소 행을 제거합니다.

모델은 CSV를 직접 편집하거나 파일을 읽지 않습니다. 원본 일정은 Python 도구 상태 객체가 보유하며, 도구는 요청에서 지정한 대상 ID만 변경합니다. Agent 설정 모델은 현재 `gemini-3.5-flash-lite`이며, timeout·최대 반복 횟수·tool calling 모드는 `schedule_adjuster/settings.py` 상단에서 변경합니다.

### 실행 및 테스트

`backend/.env` 또는 OS 환경 변수에 `GEMINI_API_KEY`를 설정합니다. 설정 로더는 먼저 `schedule_adjuster/.env`를 확인하고, 값이 없으면 `backend/.env`를 확인합니다. CLI 실행 예시:

```powershell
python -m schedule_adjuster.cli --schedule-id study-201 `
  --schedule-csv schedule_adjuster_test_inputs/schedule_adjuster_case_01_study_move.csv `
  --availability-file availability.json `
  1> schedule_adjuster_test_results/manual_adjusted.csv `
  2> schedule_adjuster_test_results/manual_trace.txt
```

Gemini API 없이 로직과 CSV 변환 단위 테스트를 실행하려면:

```powershell
python -m unittest discover -s schedule_adjuster_tests -v
```

실제 Gemini API 호출과 도구 선택 순서를 검사하려면 `backend/`에서 통합 테스트 러너를 실행합니다. API 요청이 케이스마다 발생합니다.

```powershell
python run_schedule_adjuster_test_cases.py
python run_schedule_adjuster_test_cases.py --case-id schedule_adjuster_case_05_reschedule_with_personal_availability
```

케이스는 `schedule_adjuster_test_inputs/schedule_adjuster_test_cases.json`에 추가합니다. 각 객체는 `case_id`, `schedule_id`, `schedule_csv`, `expected_action`, `expected_change_tool`을 지정하고, 개인 시간표를 별도 파일로 시험할 때 `availability_file`을 지정합니다. 이를 생략하면 기본 공유 프로필을 사용합니다. 다섯 번째 케이스는 수요일 20:00–22:00의 테스트 프로필을 불러와 실제 Gemini Agent가 고른 일정을 해당 시간 안에 옮기는지 검사합니다. `--case-id` 옵션으로 지정 케이스 하나만 실행할 수 있고 요약은 `<case_id>_summary.json`에 별도 저장됩니다. 전체 실행 결과는 `schedule_adjuster_test_summary.json`에 기록되므로 단일 케이스 실행이 전체 요약을 덮어쓰지 않습니다. 각 실행은 결과 CSV와 `<case_id>_tool_trace.json`을 생성합니다. trace에는 요청 횟수, 개인 시간표 적용·준수 여부, 대상 일정 충돌 여부, 기대/실제 작업 및 도구 순서, 다른 일정 행 보존 여부가 기록됩니다. 전체 실행은 모든 케이스가 `PASS`이고 종료 코드가 0인지 요약 JSON에서 확인합니다. 저장된 통과 결과는 최신 소스 변경을 자동 검증하지 않으므로 필요 시 러너를 다시 실행합니다.

### 현재 한계와 후속 개발 결정

입력에 실패 사유나 취소 상태 열은 없으므로 호출자가 선택한 ID를 실패/취소 대상으로 간주합니다. 실패 일정은 같은 ID, 주제, 기간 길이로 이동됩니다. `config/user_availability.json`이 있으면 같은 주제의 다른 일정 이후부터 해당 요일·시간표 안에서만 충돌 없는 슬롯을 찾습니다. 이 파일은 License Planner와 공유하며, CLI에서는 `--availability-file`로 대체 경로를 지정할 수 있습니다. 파일이 없으면 기존 동작을 유지합니다. 가능 구간이 없거나 세션 길이를 수용할 수 없으면 `NO_AVAILABILITY_WINDOW` 오류를 반환합니다. 이후 일정의 연쇄 이동, 공휴일, 시험 시각은 아직 지원하지 않습니다.




