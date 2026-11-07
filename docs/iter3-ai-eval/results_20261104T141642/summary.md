# AI 평가 결과 (20261104T141642)

- 대상: `http://swpp26t02.147-46-242-174.sslip.io/api/v1`
- 서버 commit: `main-09d66ef(서버 미확인)`
- 모델: unknown
- 판정: **FAIL**

| 기준 | 값 | 통과 |
| --- | --- | --- |
| T1 | 0.97 | O |
| T2 | 0 | O |
| T3 | det 3/3, D03 5xx/timeout 0 | O |
| T4 | timeouts 0, p95 22.17s | O |
| T5 | 0.484 | X |
| T6 | 0.857 | O |
| T7 | 1.0 | O |
| T8 | J1 14/15, J2-J5 14/14 | O |

| 보고 지표 | 값 |
| --- | --- |
| Q4_utilization_median | 0.526 |
| Q5_short_share_mean | 0.208 |
| Q6_share_range_max | 0.267 |
| Q6_share_range_median | 0.107 |
| latency_p50 | 9.6 |
| latency_p95 | 22.17 |
| latency_max | 30.26 |

| 결과 분류 | 횟수 |
| --- | --- |
| OK | 116 |
| HTTP_5XX | 3 |
