# 프론트엔드 연동 안내

이 문서는 화면 개발자가 Python 내부 구현을 추적하지 않고 API를 연결할 수 있도록 현재 요청·응답 계약과 개발 순서를 정리합니다.

## 개발 환경

1. `backend/.env`에 `GEMINI_API_KEY`가 설정되어 있는지 확인합니다. 이 키는 백엔드 전용이며 프론트엔드 코드, 설정 JSON, 브라우저 저장소에 복사하지 않습니다.
2. `backend/` 디렉터리에서 `python api_server.py`를 실행합니다.
3. 프론트엔드 폴더의 `frontend_config.json`에 API 주소를 지정합니다 (`http://127.0.0.1:8000/api/v1`).
4. `GET http://127.0.0.1:8000/api/v1/health`의 `{"status":"ok"}` 응답으로 연결을 확인합니다.
5. 개발 서버 origin이 기본 허용 주소와 다르면 `config/api_server.json`의 `allowed_origins`에 추가합니다.

정적 프론트엔드는 `frontend/`를 문서 루트로 제공해야 합니다. 저장소 루트를 정적 서버로 제공하면 백엔드 파일이 브라우저에서 직접 열릴 수 있습니다. 프론트엔드는 자격증과 질문을 API에서 불러오고 API 주소만 `frontend_config.json`에서 읽습니다.

## 공통 규칙

- 요청·응답은 UTF-8 JSON입니다.
- 일정 날짜·시간: `YYYY/MM/DD/HH/MM` (예: `2026/10/05/18/30`)
- 반복 가능 시간: `HH:MM` 24시간 형식
- 일정 ID: 공부 일정 `study-숫자`, 외부 일정 `external-숫자`
- 응답의 `schedules`는 화면 표/캘린더용, `schedule_csv`는 CSV 내용입니다. Planning API는 파일을 `backend/license_planner/output/`에 저장하고 `schedule_csv_file`(백엔드 상대 경로)과 `schedule_csv_url`(다운로드 API 경로)을 반환합니다. 새 계획 생성 시 삭제 가능한 자동 생성 CSV 중 최근 100개를 유지하도록 오래된 파일을 정리합니다. 정리된 파일 URL은 더 이상 다운로드할 수 없습니다.
- 응답 `topic`은 선택 자격증의 Topic 중 하나입니다. 자격증마다 목록이 다를 수 있으므로 프론트엔드에서 하나의 전역 목록으로 제한하지 않습니다.
- Gemini 키는 프론트엔드에 전달하지 않습니다. 일정 생성·조정 API가 서버에서 Gemini를 호출합니다.

## 자격증과 진단 문항

`GET ${api_base_url}/certifications`는 활성 자격증 목록을 반환합니다.
선택한 자격증의 `GET ${api_base_url}/certifications/{certification_id}/questions`
더미 문항 응답에는 질문, 선택지, 정답, 배점이 포함됩니다. 질문별 Topic 매핑은 미리 고정하지 않으며, Planning Agent가 문제 내용을 보고 해당 자격증의 `backend/config/certifications.json`에 정의된 Topic 중 하나 이상과 비중을 선택합니다.

```json
{
  "certification_id": "computer_specialist_level_2",
  "certification_name": "컴퓨터활용능력 2급",
  "questions": [
    {"id": 1, "prompt": "...", "choices": {"A": "...", "B": "..."},
     "correct_answer": "B", "possible_score": 1}
  ]
}
```

## 일정 생성

`POST ${api_base_url}/study-plans`

요청 예시:

```json
{
  "self_assessment": "엑셀 함수는 익숙하지 않습니다.",
  "assessment_results": {
    "certification_id": "computer_specialist_level_2",
    "results": [{
      "problem_id": 1,
      "question": "A1부터 A5까지의 합계를 구하는 수식은 무엇인가요?",
      "choices": {"A": "=COUNT(A1:A5)", "B": "=SUM(A1:A5)"},
      "correct_answer": "B",
      "user_answer": "A",
      "is_correct": false,
      "possible_score": 1
    }]
  },
  "preparation_start": "2026/10/05/18/00",
  "exam_date": "2026/11/30/23/00",
  "certification_id": "computer_specialist_level_2",
  "study_days_per_week": 5,
  "busy_schedules": [
    {"schedule_id": "12", "start_date": "2026/10/07/13/00",
     "end_date": "2026/10/07/15/00", "topic": ""}
  ],
  "weekly_availability": {
    "monday": [{"start": "18:00", "end": "21:00"}],
    "saturday": [{"start": "10:00", "end": "13:00"}]
  }
}
```

`assessment_results`는 자격증 ID와 질문 결과 JSON입니다. 각 결과는 `problem_id`, `question`, `choices`, `correct_answer`, `user_answer`, `is_correct`, `possible_score`를 포함합니다. 정오 판정은 FE/질문 CLI의 기본 비교 로직이 만듭니다. API는 재채점하지 않으며, Planning Agent가 각 문제를 자격증의 고정 Topic 하나 이상에 비중으로 매핑합니다. 각 문제의 Topic 비중 합은 1이어야 합니다. `busy_schedules`는 생략, `null`, 빈 배열을 허용합니다. 계획을 생성하려면 `weekly_availability`에 최소 한 개의 요일과 시간 구간을 보내거나, 서버에 유효한 저장 프로필이 있어야 합니다.

응답 예시:

```json
{
  "schedules": [
    {"schedule_id": "external-12", "start_date": "2026/10/07/13/00",
     "end_date": "2026/10/07/15/00", "topic": ""},
    {"schedule_id": "study-1", "start_date": "2026/10/05/18/00",
     "end_date": "2026/10/05/19/00", "topic": "함수"}
  ],
  "schedule_csv": "schedule_id,start_date,end_date,topic\n...",
  "schedule_csv_file": "license_planner/output/study_plan_....csv",
  "schedule_csv_url": "/api/v1/study-plans/study_plan_....csv",
  "agent_summary": "...",
  "tool_calls": []
}
```

응답의 `schedule_csv_url`에 `GET` 요청을 보내면 CSV 파일을 내려받습니다. 이 경로는 서버가 발급한 파일명만 허용하며, 경로 문자열을 임의로 조합해 요청하지 않습니다.

화면에서는 `schedules`를 사용합니다. `tool_calls`는 개발 진단용이므로 사용자에게 표시할 필요가 없습니다.

## 일정 조정

`POST ${api_base_url}/schedules/adjust`

요청에는 수정 대상 `schedule_id`와 현재 일정 **전체**를 보냅니다. 전체 일정을 보내야 취소·이동 대상 외의 행도 결과에 유지됩니다.

```json
{
  "schedule_id": "study-1",
  "schedules": [
    {"schedule_id": "study-1", "start_date": "2026/10/05/18/00",
     "end_date": "2026/10/05/19/00", "topic": "함수"},
    {"schedule_id": "external-12", "start_date": "2026/10/07/13/00",
     "end_date": "2026/10/07/15/00", "topic": ""}
  ]
}
```

응답은 생성 API와 동일하게 `schedules`, `schedule_csv`, `agent_summary`, `tool_calls`를 포함하고 `action`, `target_schedule_id`를 추가합니다. 공부 일정 ID는 일정을 이동하고, 외부 일정 ID는 해당 행을 제거합니다. 사용자 가능 시간은 백엔드 저장 프로필을 적용합니다.

## 가능 시간 조회와 저장

- `GET ${api_base_url}/availability`: 저장된 시간표가 있으면 `{ "weekly_availability": {"monday": [{"start":"18:00","end":"21:00"}], ...} }`를 반환합니다. 아직 저장된 시간표가 없으면 시간표 값은 `null`입니다.
- `PUT ${api_base_url}/availability`: 요일별 객체를 직접 보냅니다. 성공 응답은 `weekly_availability` 래퍼를 포함합니다.

저장 요청 예시:

```json
{
  "monday": [{"start": "18:00", "end": "21:00"}],
  "saturday": [{"start": "10:00", "end": "13:00"}]
}
```

요일 키는 `monday`부터 `sunday`까지입니다. 시간 구간끼리 겹치면 안 되고 시작 시간이 종료 시간보다 빨라야 합니다.

## 브라우저 호출 예시

```javascript
const { api_base_url: apiBaseUrl } = await fetch("/frontend_config.json").then(r => r.json());

async function callApi(path, options = {}) {
  const response = await fetch(`${apiBaseUrl.replace(/\/$/, "")}${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...options.headers },
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.error?.message ?? `API request failed (${response.status})`);
  }
  return body;
}

const result = await callApi("/study-plans", {
  method: "POST",
  body: JSON.stringify(formValues),
});
// 캘린더/표에는 result.schedules를 사용합니다.
// CSV 내려받기 링크는 new URL(result.schedule_csv_url, apiBaseUrl)에 연결합니다.
```

프론트엔드가 정적 호스팅 환경에서 JSON 설정 파일을 제공할 수 없다면 빌드 시스템의 공개 환경 변수로 API 주소만 전달해도 됩니다. 그 변수에 비밀 키를 넣으면 안 됩니다.

## 오류 및 화면 처리

오류는 `{ "error": { "code": "INVALID_INPUT", "message": "..." } }` 형태입니다. 시간표가 없을 때 프론트엔드는 `message`를 입력 안내로 표시할 수 있습니다. Planning API는 다음과 같이 HTTP 422를 반환합니다.

```json
{
  "error": {
    "code": "INVALID_INPUT",
    "message": "weekly_availability is required. Enter at least one weekday and available time window."
  }
}
```

| HTTP 상태 | 의미 | 화면 처리 |
| --- | --- | --- |
| 200 | 성공 | `schedules`로 화면 갱신 |
| 404 | 잘못된 경로 | API 주소/경로 설정 확인 |
| 422 | 입력 오류 | `error.message` 표시, 입력 유지 |
| 502 | Gemini 등 외부 통신 실패 | 재시도 안내, 입력 유지 |
| 503 | API 키 등 서버 설정 오류 | 개발·운영 담당자 확인이 필요한 오류로 안내 |
| 500 | 예상하지 못한 서버 오류 | 일반 오류 표시, 재요청 안내 |

요청 중에는 로딩 표시 및 중복 제출 방지를 적용합니다. 학습 계획 요청은 Gemini API를 호출하므로 백엔드 네트워크와 키 설정이 필요합니다.

## 인계 시 확인 목록

1. `frontend_config.json`이 프론트엔드에서 읽히는 위치인지 확인합니다.
2. 프론트엔드 개발 서버 origin이 `config/api_server.json`에 허용되어 있는지 확인합니다.
3. 가능 시간 조회·저장부터 연결하고 요청 JSON 형식을 확인합니다.
4. Gemini 키가 설정된 환경에서 생성과 조정을 각각 실행합니다.
5. 성공 응답의 `schedules`, 오류 응답의 `error.message`를 확인합니다.
6. 배포 전에는 HTTPS, 인증, 요청 빈도 제한, 사용자별 데이터 저장 방식을 백엔드 담당자와 결정합니다. 현재 서버는 로컬 MVP이고 가능 시간은 단일 사용자 JSON 파일에 저장됩니다.

HTTP 경로·CORS·입력 오류와 개인 가능 시간 적용을 자동 확인하려면 프로젝트
`backend/` 디렉터리에서 `python run_api_availability_integration_test.py`를 실행합니다. 임시
localhost 서버와 별도 JSON 프로필을 사용해 개인 설정 파일을 변경하지 않습니다.
일정 조정은 Gemini 실호출이므로 API 키, 네트워크, 할당량이 필요하며 결과는
`api_test_results/`에 저장됩니다.


