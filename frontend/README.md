# Temporary License Planner Frontend

이 폴더는 기존 Backend API에 연결되는 임시 브라우저 UI입니다. 빌드 도구나 npm 패키지 없이 정적 파일로 실행합니다.

## 실행

PowerShell 창 1에서 API를 시작합니다.

```powershell
cd C:\SNU_Program_file\SWPP\TEAM_PROJECT_2\repo\backend
python api_server.py
```

PowerShell 창 2에서 프론트엔드 폴더만 정적 파일 서버로 제공합니다.

```powershell
cd C:\SNU_Program_file\SWPP\TEAM_PROJECT_2\repo
python -m http.server 5173 --directory frontend
```

브라우저에서 [http://localhost:5173/](http://localhost:5173/)를 엽니다. 화면 위에 Planning API 연결 상태가 표시됩니다. 계획 생성은 백엔드 `.env`의 Gemini API 키와 네트워크 연결을 사용합니다. Gemini 키는 브라우저에 전달하지 않습니다. 현재 더미 진단 문항 API는 로컬 채점을 위해 정답도 반환합니다.

## 흐름

1. `GET /api/v1/certifications`에서 활성화된 자격증 목록을 가져옵니다.
2. 선택한 자격증 ID로 `GET /api/v1/certifications/{id}/questions`를 호출합니다. 더미 문항·선택지·정답을 가져옵니다.
3. 질문, 선택지, 정답, 사용자 답, 로컬 정오 판정이 포함된 `assessment_results` JSON과 시험 날짜, 준비 시작, 자유형 자기평가, 요일별 가능 시간을 `POST /api/v1/study-plans`로 보냅니다. 채점은 프론트엔드의 기본 비교 로직이 수행하며, Planning Agent는 문제마다 복수의 학습 Topic 비중을 판단합니다.
4. 응답의 `schedules`를 준비 시작일 기준 주차와 날짜별로 표시하고, `schedule_csv_url`로 저장된 CSV를 내려받습니다.

현재 질문 세트는 컴퓨터활용능력 2급 더미 문항입니다. `backend/assessment_questionnaire/question_banks.json`에서 자격증 ID와 백엔드 질문 파일을 연결합니다.
