# 확인한 실험 환경

| 항목 | Live 원본 manifest 기록 |
| --- | --- |
| 저장소 브랜치 | iteration-1-demo |
| commit | d706ad2ea66aaed6faeebea1c21dc358ace0ecf2 |
| Python | 3.14.7, Anaconda, MSC v.1942, AMD64 |
| OS | Windows-11-10.0.26200-SP0 |
| CPU | Intel64 Family 6 Model 170 Stepping 4, GenuineIntel |
| 모델 | gemini-3.5-flash-lite |
| seed | 20261014 |
| 실행 구성 | live, 폴더별 1케이스 × 5회, 17개 폴더 |
| 폴더 시작 시각 | 2026-10-15 02:10:55.080972~03:37:05.658248 KST |
| 입력 SHA-256 | 27ff8db8a18c18d606a300f8f0ed210dcdefe902feee9041d2c3492c7a1e0e23 |
| 추적 파일 변경 | 모든 repository_check.json에서 빈 문자열 |
| Health | 모든 live 폴더에서 HTTP 200 |
| 시간 범위 | 서버 시작 제외 로컬 HTTP 왕복, 모델·도구·CSV 저장 포함 |

위 시각 범위는 각 폴더의 시작 시각 범위이며 전체 마지막 실행 종료 시각이 아닙니다. PowerShell 화면은 첨부 대화에서 별도 확인한 정보입니다.

과거 offline manifest는 2026-10-14 14:09:20 KST, Python 3.13.15, key_present=false입니다. live와 환경·시점을 혼동하지 않습니다. 이번 로그 재계산과 산술 테스트 검증에는 번들 Python 3.12.14를 사용했습니다. live 재호출과 backend 테스트 재실행은 하지 않았습니다.
