# Schedule Adjuster

이 프로그램은 원본 일정 CSV와 변경 대상 `schedule_id`를 받아 실패한 공부 일정을 옮기거나 취소된 외부 일정을 제거합니다. 외부 일정은 취소된 한 행을 제외하고 모두 유지합니다. OpenRouter 모델 `openai/gpt-6-luna`를 쓰는 Agent는 도구를 선택하고, 일정 변경 계산과 CSV 변환은 Python 코드가 수행합니다.

## 입력과 출력

명령줄 입력은 변경 대상 ID와 원본 CSV 경로입니다. 새 CSV 열은 `schedule_id,start_date,end_date,topic`이며 시작·종료 값은 `YYYY/MM/DD/HH/MM`입니다. 기존 세 열 CSV도 읽을 수 있습니다. 일정 구간은 시작 포함·종료 제외입니다. ID는 이전 일정 생성 프로그램의 출력과 같이 `study-번호` 또는 `external-번호` 형식이어야 합니다.

- 실패한 공부 일정(`study-N`): 주제가 있으면 Agent가 같은 주제 세션을 먼저 조회합니다. 기존 기간 길이와 주제를 유지하고, 같은 주제의 다른 세션 중 가장 늦게 끝나는 시각 이후부터 충돌하지 않는 구간을 찾습니다. `config/user_availability.json`이 있으면 해당 요일·시간 안에서만 이동합니다. 주제가 없는 구형 행도 같은 가능 시간 규칙을 적용합니다. 다른 일정 행은 그대로 둡니다.
- 취소된 외부 일정(`external-N`): 취소 행만 제거하고 나머지 외부 일정과 공부 일정은 보존합니다.
- 출력: `schedule_id,start_date,end_date,topic` CSV. 주제는 이동 후에도 유지되며 외부 일정 주제는 비어 있을 수 있습니다. stdout은 결과 CSV, stderr는 조정 작업과 tool trace입니다.

예시:

```powershell
python -m schedule_adjuster.cli --schedule-id study-104 --schedule-csv schedule.csv `
  1> adjusted_schedule.csv 2> adjustment_trace.txt
```

실행할 때는 OS 환경 변수 또는 `schedule_adjuster/.env`에 `OPENROUTER_API_KEY`를 설정합니다. OS 환경 변수가 우선하며, 개발 워크스페이스에서는 `schedule_adjuster/.env`에 값이 없을 때 상위 폴더의 `.env`도 확인합니다. `GEMINI_API_KEY`는 더 이상 사용하지 않습니다. 키는 첫 모델 요청 직전에만 읽습니다. 배포 시에는 프로그램 전용 비밀 설정으로 분리하세요. 반복 횟수·추론 강도·출력 토큰 한도는 `settings.py`에서, 모델 ID·endpoint·timeout은 `backend/openrouter_client.py`에서 바꿉니다.

개인 공부 가능 시간은 License Planner와 공유하는 `config/user_availability.json`에서 자동으로 읽습니다. 형식은 다음과 같고, 입력에 포함되지 않은 요일은 공부 불가로 처리합니다.

```json
{
  "weekly_availability": {
    "monday": [{"start": "19:00", "end": "21:00"}],
    "saturday": [{"start": "09:00", "end": "12:00"}]
  }
}
```

다른 JSON 파일을 지정하려면 `--availability-file availability.json`을 추가합니다. 파일이 없으면 기존처럼 기존 일정과 주제 순서만 고려해 이동합니다. 설정된 요일에 세션 길이를 수용할 만큼 긴 시간 구간이 없으면 조정 오류를 반환합니다. 취소된 외부 일정 제거는 가능 시간 설정과 무관합니다.

## Agent 도구

Agent는 원본 일정을 프롬프트에 통째로 받지 않습니다. `inspect_schedule` 도구가 정확한 ID, 종류, 주제를 조회합니다. 주제가 있는 공부 일정이면 `inspect_topic_schedule`이 같은 주제의 세션을 찾아 주제 학습 순서를 반영하고, 그 다음 재배치 도구를 호출합니다.

1. `inspect_schedule(schedule_id)`: 대상이 CSV에 있는지, 일정 종류와 주제, 적용할 개인 시간표를 확인합니다.
2. `inspect_topic_schedule(topic)`: 주제가 있는 study 일정만 대상으로 같은 주제 일정을 확인합니다.
3. `reschedule_failed_study(schedule_id)`: study ID만 허용하고 같은 주제의 다른 학습 이후 사용자 가능 시간 안의 빈 슬롯으로 이동합니다.
4. `remove_cancelled_external_schedule(schedule_id)`: external ID만 허용하고 취소된 행을 제거합니다.

각 변경 도구는 사용자가 제출한 ID만 수정할 수 있고, 대상 종류가 잘못되거나 ID가 없으면 명확한 도구 오류를 반환합니다. Agent 반복은 제한되어 있고, 실행 기록에는 호출 도구와 인자가 순서대로 남습니다.

변경 도구가 요청한 대상에 맞는 결과를 기록하면 Agent는 그 자리에서 종료합니다. 같은 모델 응답에 남은 도구 호출은 실행하지 않고, 결과 설명을 받기 위한 추가 모델 요청도 보내지 않습니다. 따라서 반환되는 요약(`agent_summary`)은 모델이 쓴 문장이 아니라 도구가 기록한 검증된 결과 메시지입니다. 마지막 허용 응답에서 성공해도 반복 제한 오류가 나지 않으며, 마지막 외부 일정을 취소해 일정이 비어도 성공입니다. 조회만 했거나, 변경 도구가 오류를 반환했거나, 모델이 텍스트로만 완료를 주장한 경우는 성공이 아닙니다. API의 결과 검증, 충돌 확인, 같은 파일 저장 단계는 그대로 적용됩니다.

## 모델 요청과 도구 연결

Agent는 OpenRouter Responses API(`POST https://openrouter.ai/api/v1/responses`)에 비스트리밍 요청을 보냅니다. 요청마다 `reasoning.effort: none`, `max_output_tokens: 2048`, `store: false`, `parallel_tool_calls: false`와 현재 도구 선언(`type: function`, 소문자 JSON Schema 타입, `strict: false`)을 보내며, 요청은 최대 5번이고 자동 재시도나 다른 모델 전환은 없습니다. 서버에 대화를 저장하지 않으므로 Agent가 요청 안에서 대화 기록을 유지합니다. 직전 응답의 출력 항목을 그대로 다음 요청에 붙이고, 각 도구 결과는 원래 호출의 `call_id`를 가진 `function_call_output`으로 돌려줍니다. 응답 항목의 `id`는 `call_id`로 쓰지 않습니다.

도구는 호출 순서대로 실행하며, 실행 직전에 `call_id`가 비어 있지 않고 이번 요청에서 처음 나왔는지, 이름이 제공한 도구인지, `arguments`가 JSON 객체인지 확인합니다. 이 검사에 실패하거나 응답이 거절·미완료·오류이거나 형식이 잘못되면 상위 오류(API 502)로 끝나고 일정을 바꾸지 않습니다. 도구가 반환한 오류(예: `PREREQUISITE`, `WRONG_TARGET`)는 관찰 결과로 모델에 돌려줘 같은 요청 한도 안에서 고칠 수 있습니다.

## 폴더 구조

- `schedule_adjuster/`: 모델, CSV 입출력, 조정 로직, Agent tools, OpenRouter Agent 루프, CLI
- `tests/schedule_adjuster_tests/`: API 없이 실행되는 단위 테스트
- `scenarios/inputs/schedule_adjuster/`: 다중 케이스 설정 JSON과 테스트용 원본 CSV
- `scenarios/results/schedule_adjuster/`: 케이스별 결과 CSV, 도구 호출 trace, 통합 요약
- `scenarios/run_schedule_adjuster_test_cases.py`: 케이스 설정을 순회해 실제 OpenRouter Luna Agent를 호출하는 전용 테스트 러너

이 두 폴더가 이전 프로그램의 `license_planner/`와 기존 테스트 폴더와 구분되는 새 프로그램입니다.

## 로컬 테스트

```powershell
python -m unittest discover -s tests/schedule_adjuster_tests -v
```

이 테스트는 실제 모델 API를 호출하지 않고(모델 응답은 스크립트로 대체) ID 종류 판정, 외부 일정 보존/취소, 공부 일정 이동, 기간 충돌 회피, CSV 형식을 검사합니다.

## OpenRouter Agent 통합 테스트

`OPENROUTER_API_KEY`를 설정한 뒤 `backend/` 디렉터리에서 테스트 러너만 실행합니다. 이 테스트는 케이스마다 OpenRouter `openai/gpt-6-luna`를 호출하므로 네트워크 연결과 사용 가능한 크레딧이 필요합니다.

```powershell
python -m scenarios.run_schedule_adjuster_test_cases
python -m scenarios.run_schedule_adjuster_test_cases --case-id schedule_adjuster_case_05_reschedule_with_personal_availability
```

케이스 추가는 `scenarios/inputs/schedule_adjuster/schedule_adjuster_test_cases.json`에 `case_id`, 대상 `schedule_id`, 입력 CSV, 기대 작업, 기대 변경 도구를 추가하면 됩니다. 개인 가능 시간 파일을 쓸 때는 `availability_file`도 지정합니다. CSV/JSON 입력은 같은 입력 폴더에 둡니다. 다섯 번째 케이스는 수요일 20:00–22:00 프로필에 공부 일정을 이동하는 실호출 테스트입니다. `--case-id` 옵션은 해당 케이스만 실행해 모델 요청 횟수를 줄이고, 결과 요약을 `<case_id>_summary.json`에 별도로 기록하여 전체 실행 요약을 덮어쓰지 않습니다. 러너는 `scenarios/results/schedule_adjuster/`에 결과 CSV와 도구 호출 이력을 기록합니다.

성공 여부는 세 가지로 확인합니다. 터미널에 모든 케이스가 `PASS`로 표시되고 프로세스 종료 코드가 0이어야 합니다. 각 `*_tool_trace.json`에서 `inference.model_request_count`가 1 이상이고 `called_tools`가 `inspect_schedule` 다음에 기대한 변경 도구 순서로 기록되어야 합니다. 마지막으로 결과 CSV에서 공부 일정 이동 또는 취소 외부 일정 제거가 반영되고 다른 일정 행이 유지됐는지 확인합니다. `schedule_adjuster_test_summary.json`에는 전체 케이스의 판정이 모입니다.

`inference`에는 선택 모델(`selected_model`), 모델 요청 수, 경과 시간(초), 응답별 반환 모델과 토큰·비용 사용량이 기록되며, 제공자가 반환하지 않은 값은 0이 아니라 `null`입니다. 이 trace는 요청이 실제 OpenRouter 호출 경로를 거쳐 Agent의 도구 응답까지 돌아왔다는 점을 보여줍니다. 이전 결과 파일의 `gemini_api_request_count`는 Gemini 시절 기록입니다. API 키 자체나 원시 HTTP 응답 본문은 저장하지 않습니다. 인증, 네트워크, 모델 응답 오류가 발생하면 해당 케이스는 `FAIL`이며 요약에 오류가 기록됩니다.

## 현재 동작 가정과 필요한 제품 결정

시험일, 공휴일, 휴일 예외 목록은 아직 지원하지 않습니다. 개인 반복 시간표가 설정되면 공부 일정은 그 요일·시간 구간으로 제한되고, 미설정이면 원래 종료 시각부터 기존 충돌이 없는 동일 길이 구간을 찾습니다. 외부 일정 취소는 그 행을 출력에서 제거합니다.

현재 남은 제품 결정은 실패 일정 이동 후 뒤따르는 공부 일정을 연쇄 이동할지, 취소된 외부 행을 삭제할지 취소 상태로 보존할지, 시험 시각·공휴일을 조정 가능 시간에 반영할지입니다. 개인 반복 주간 가능 시간은 `config/user_availability.json`에서 이미 반영합니다. 일정 CSV에 상태 열이 없으므로 외부 취소 여부는 사용자가 대상 ID를 선택했다는 사실로 판단합니다.


