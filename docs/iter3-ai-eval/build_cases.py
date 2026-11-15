"""Builds the Iteration 3 AI evaluation set (cases.json).

Standard library only. Run from this folder:
    python build_cases.py ../../backend/assessment_questionnaire/questions.json
The question bank is read so that assessment payloads match what the app sends.
"""
import json
import hashlib
import sys
from pathlib import Path

TOPICS = ["스프레드시트 기본", "함수와 데이터 관리", "차트와 분석"]
TOPIC_MAP = {"1": "스프레드시트 기본", "2": "함수와 데이터 관리", "3": "함수와 데이터 관리",
             "4": "차트와 분석", "5": "스프레드시트 기본"}

START = "2026/11/09/00/00"   # Monday
EXAM = "2026/11/30/09/00"    # Monday morning, 3 weeks later

STD = {  # HE case availability (02_he_cases.md)
    "monday": [{"start": "19:00", "end": "20:30"}],
    "wednesday": [{"start": "19:00", "end": "20:30"}],
    "friday": [{"start": "19:00", "end": "20:30"}],
    "saturday": [{"start": "10:00", "end": "12:00"}],
}
DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]

# Answers per question id; "UNKNOWN" mirrors the app's '모르겠음' choice.
PATTERNS = {
    "all_correct":         ["B", "B", "A", "A", "C"],
    "all_wrong":           ["A", "A", "B", "B", "A"],
    "weak_charts":         ["B", "B", "A", "B", "C"],
    "weak_functions":      ["B", "A", "B", "A", "C"],
    "weak_basics":         ["A", "B", "A", "A", "A"],
    "weak_functions_charts": ["B", "A", "B", "B", "C"],
    "weak_basics_charts":  ["A", "B", "A", "B", "A"],
    "weak_basics_functions": ["A", "A", "B", "A", "A"],
    "he_case":             ["B", "B", "UNKNOWN", "B", "C"],
    "one_function_miss":   ["B", "A", "A", "A", "C"],
}

WEAK = {
    "weak_charts": {"차트와 분석"},
    "weak_functions": {"함수와 데이터 관리"},
    "weak_basics": {"스프레드시트 기본"},
    "weak_functions_charts": {"함수와 데이터 관리", "차트와 분석"},
    "weak_basics_charts": {"스프레드시트 기본", "차트와 분석"},
    "weak_basics_functions": {"스프레드시트 기본", "함수와 데이터 관리"},
}


def configure(bank):
    """Use the selected, reviewed bank, never silently truncate to five answers."""
    global TOPICS, TOPIC_MAP
    by_id = {q["id"]: q for q in bank["questions"]}
    selected = [by_id[i] for i in bank["onboarding_question_ids"]]
    if not selected or any(q.get("review_status") != "reviewed" for q in selected):
        raise ValueError("Evaluation requires nonempty, reviewed onboarding questions")
    bank = {**bank, "questions": selected}
    TOPICS = list(dict.fromkeys(q["topic"] for q in selected))
    TOPIC_MAP = {str(q["id"]): q["topic"] for q in selected}
    patterns = {**WEAK, **{"weak_topic_" + str(i): {t} for i, t in enumerate(TOPICS)}}
    for name in list(PATTERNS) + list(patterns):
        answers = []
        for q in selected:
            wrong = next(k for k in q["choices"] if k != q["correct_answer"])
            miss = name == "all_wrong" or q["topic"] in patterns.get(name, set())
            if name == "one_function_miss":
                miss = q["id"] == 2
            answer = wrong if miss else q["correct_answer"]
            if name == "he_case" and q["id"] in (3, 4):
                answer = "UNKNOWN" if q["id"] == 3 else wrong
            answers.append(answer)
        PATTERNS[name] = answers
    return bank


def assessment(bank, answers):
    if len(bank["questions"]) != len(answers):
        raise ValueError("Every selected question must have an answer")
    results = []
    for q, answer in zip(bank["questions"], answers):
        choices = dict(q["choices"])
        choices["UNKNOWN"] = "모르겠음"
        results.append({
            "problem_id": q["id"], "question": q["prompt"], "choices": choices,
            "correct_answer": q["correct_answer"], "user_answer": answer,
            "is_correct": answer == q["correct_answer"], "possible_score": q["possible_score"],
        })
    return {"certification_id": "computer_specialist_level_2", "results": results}


def topic_scores(answers, bank):
    scores = {}
    for q, answer in zip(bank["questions"], answers):
        topic = TOPIC_MAP[str(q["id"])]
        earned, possible = scores.get(topic, (0, 0))
        scores[topic] = (earned + (answer == q["correct_answer"]), possible + 1)
    return {t: round(e / p * 100, 1) for t, (e, p) in scores.items()}


def expectations(answers, bank):
    scores = topic_scores(answers, bank)
    low, high = min(scores.values()), max(scores.values())
    weakest = sorted(t for t, s in scores.items() if s == low) if low != high else []
    return {"topic_scores": scores, "weakest_topics": weakest,
            "ordering_applies": low != high}


def request(bank, pattern="he_case", availability=STD, busy=None, start=START, exam=EXAM,
            self_assessment="기본 기능은 알지만 함수와 차트는 연습이 필요합니다.", overrides=None):
    body = {
        "certification_id": bank["certification_id"],
        "preparation_start": start, "exam_date": exam,
        "weekly_availability": availability,
        "busy_schedules": busy or [],
        "assessment_results": assessment(bank, PATTERNS[pattern]),
    }
    if self_assessment is not None:
        body["self_assessment"] = self_assessment
    if overrides is not None:
        body["availability_overrides"] = overrides
    return body


def busy(i, start, end, topic="개인 일정"):
    return {"schedule_id": str(i), "start_date": start, "end_date": end, "topic": topic}


def case(case_id, group, story, purpose, bank, pattern="he_case", expected="200", reps=3,
         adjust=None, **kwargs):
    item = {
        "case_id": case_id, "group": group, "uat_story": story, "purpose": purpose,
        "answer_pattern": pattern, "expected_status": expected, "repetitions": reps,
        "request": request(bank, pattern, **kwargs),
        "expect": expectations(PATTERNS[pattern], bank),
    }
    if adjust:
        item["adjust"] = {"target": adjust}
    return item


def build(bank):
    bank = configure(bank)
    c = []
    # A. Weak-topic allocation (US-B3, P31)
    a_cases = [
        ("A01", "all_correct", "모든 토픽 정답: 순서 기대 없음, 고른 배분 확인"),
        ("A02", "all_wrong", "모든 토픽 오답: 순서 기대 없음, 고른 배분 확인"),
        ("A03", "weak_charts", "차트만 약함"),
        ("A04", "weak_functions", "함수만 약함"),
        ("A05", "weak_basics", "스프레드시트 기본만 약함"),
        ("A06", "weak_functions_charts", "함수·차트 약함"),
        ("A07", "weak_basics_charts", "기본·차트 약함"),
        ("A08", "weak_basics_functions", "기본·함수 약함"),
        ("A09", "he_case", "HE 고정 사례 답안('모르겠음' 포함)"),
        ("A10", "one_function_miss", "함수 1문항만 틀림: 약한 차이에도 순서 반영"),
    ]
    for cid, pattern, purpose in a_cases:
        c.append(case(cid, "A_weak_topic", "US-B3", purpose, bank, pattern))
    extra_id = 11
    for i, topic in enumerate(TOPICS):
        if topic not in {"스프레드시트 기본", "함수와 데이터 관리", "차트와 분석"}:
            c.append(case(f"A{extra_id:02d}",
                          "A_weak_topic", "US-B3", topic + "만 약함", bank, "weak_topic_" + str(i)))
            extra_id += 1

    # B. Availability shapes (US-B5)
    every = lambda s, e, days=DAYS: {d: [{"start": s, "end": e}] for d in days}
    b_cases = [
        ("B01", "주말만", {"saturday": [{"start": "09:00", "end": "12:00"}],
                          "sunday": [{"start": "14:00", "end": "17:00"}]}),
        ("B02", "매일 30분", every("21:00", "21:30")),
        ("B03", "하루 두 구간", {"monday": [{"start": "07:00", "end": "08:00"}, {"start": "20:00", "end": "22:00"}],
                              "thursday": [{"start": "07:00", "end": "08:00"}, {"start": "20:00", "end": "22:00"}]}),
        ("B04", "주 1회 긴 구간", {"wednesday": [{"start": "13:00", "end": "18:00"}]}),
        ("B05", "자정 직전까지", {"tuesday": [{"start": "22:00", "end": "23:59"}],
                               "thursday": [{"start": "22:00", "end": "23:59"}]}),
        ("B06", "평일 새벽", every("06:00", "07:00", DAYS[:5])),
        ("B07", "요일별 길이 다름", {"monday": [{"start": "19:00", "end": "19:30"}],
                                  "tuesday": [{"start": "19:00", "end": "21:00"}],
                                  "saturday": [{"start": "09:00", "end": "13:00"}]}),
        ("B08", "매일 긴 가용 시간", every("09:00", "21:00")),
    ]
    for cid, purpose, availability in b_cases:
        c.append(case(cid, "B_availability", "US-B5", purpose, bank, availability=availability))

    # C. Existing busy schedules (US-C9 precursor; busy_schedules field)
    c_cases = [
        ("C01", "가용 시간 일부와 겹치는 약속", [busy(1, "2026/11/11/19/30", "2026/11/11/20/00")]),
        ("C02", "토요일 가용 시간 전체를 막는 약속", [busy(1, "2026/11/14/10/00", "2026/11/14/12/00")]),
        ("C03", "매주 월요일 1시간 약속", [busy(i + 1, f"2026/11/{d}/19/00", f"2026/11/{d}/20/00")
                                     for i, d in enumerate(["09", "16", "23"])]),
        ("C04", "가용 시간 밖 약속만 있음(영향 없음)", [busy(1, "2026/11/10/19/00", "2026/11/10/21/00"),
                                              busy(2, "2026/11/19/12/00", "2026/11/19/13/00")]),
        ("C05", "여러 날에 걸친 약속(금~토 차단)", [busy(1, "2026/11/20/00/00", "2026/11/22/23/00")]),
        ("C06", "연속 약속 사이 15분만 남음", [busy(1, "2026/11/13/19/00", "2026/11/13/19/45"),
                                         busy(2, "2026/11/13/19/45", "2026/11/13/20/15")]),
    ]
    for cid, purpose, items in c_cases:
        c.append(case(cid, "C_busy", "US-C9", purpose, bank, busy=items))

    # D. Capacity and input edges (US-B7)
    c.append(case("D01", "D_capacity", "US-B7", "기간 안에 가용 요일이 없음(가용 0분)", bank,
                  expected="422_NOT_ENOUGH_TIME", reps=1,
                  availability={"saturday": [{"start": "10:00", "end": "12:00"}]},
                  start="2026/11/09/00/00", exam="2026/11/12/09/00"))
    c.append(case("D02", "D_capacity", "US-B7", "가용 시간이 모두 약속으로 막힘(가용 0분)", bank,
                  expected="422_NOT_ENOUGH_TIME", reps=1,
                  availability={"monday": [{"start": "19:00", "end": "20:00"}]},
                  busy=[busy(1, "2026/11/09/18/00", "2026/11/09/21/00")],
                  start="2026/11/09/00/00", exam="2026/11/12/09/00"))
    c.append(case("D03", "D_capacity", "US-B7", "가용 2분(토픽 수보다 적음)", bank,
                  expected="200_or_422_NOT_ENOUGH_TIME",
                  availability={"saturday": [{"start": "10:00", "end": "10:02"}]},
                  start="2026/11/09/00/00", exam="2026/11/16/09/00"))
    c.append(case("D04", "D_capacity", "US-B7", "준비 기간 3일", bank,
                  start="2026/11/09/00/00", exam="2026/11/12/09/00"))
    c.append(case("D05", "D_capacity", "US-B7", "준비 기간 8주", bank,
                  start="2026/11/09/00/00", exam="2027/01/04/09/00"))
    c.append(case("D06", "D_capacity", "US-B7", "시험일이 준비 시작일보다 빠름", bank,
                  expected="422_INVALID_INPUT", reps=1,
                  start="2026/11/20/00/00", exam="2026/11/10/09/00"))

    # E. Adjustment (US-C3, US-C4): generate, then move one study session
    c.append(case("E01", "E_adjust", "US-C3·C4", "첫 공부 일정 조정", bank, adjust="first_study"))
    c.append(case("E02", "E_adjust", "US-C3·C4", "마지막 공부 일정 조정", bank, adjust="last_study"))
    c.append(case("E03", "E_adjust", "US-C3·C4", "중간 공부 일정 조정", bank, adjust="middle_study"))
    c.append(case("E04", "E_adjust", "US-C3·C4", "약속이 있는 계획에서 첫 공부 조정", bank,
                  adjust="first_study", busy=[busy(1, "2026/11/11/19/30", "2026/11/11/20/00")]))
    c.append(case("E05", "E_adjust", "US-C3·C4", "매일 30분 계획에서 첫 공부 조정", bank,
                  adjust="first_study", availability=every("21:00", "21:30")))

    # F. Self-assessment text
    c.append(case("F01", "F_self_assessment", "US-B3", "자기 평가 문장 없음", bank,
                  pattern="weak_charts", self_assessment=None))
    c.append(case("F02", "F_self_assessment", "US-B3", "자기 평가가 진단과 반대(차트 자신 있음)", bank,
                  pattern="weak_charts",
                  self_assessment="차트는 아주 자신 있고 함수만 걱정입니다. 차트 공부는 필요 없어요."))
    # P10 integration boundaries: same data is used for baseline and candidate.
    c.append(case("G01", "G_flexible_availability", "US-B5", "월요일 자정 넘는 90분", bank,
                  availability={"monday": [{"start": "23:00", "duration_minutes": 90}]}))
    c.append(case("G02", "G_flexible_availability", "US-B5", "첫 주는 공부하지 않음", bank,
                  overrides={"2026/11/09": {}}))
    c.append(case("G03", "G_flexible_availability", "US-B5", "둘째 주만 다른 요일·시간", bank,
                  overrides={"2026/11/16": {"tuesday": [{"start": "22:30", "duration_minutes": 120}]}}))
    c.append(case("G04", "G_flexible_availability", "US-C9", "자정 넘는 공부 중 약속", bank,
                  availability={"monday": [{"start": "23:00", "duration_minutes": 120}]},
                  busy=[busy(1, "2026/11/10/00/00", "2026/11/10/00/30")]))
    return c


def main():
    here = Path(__file__).resolve().parent
    bank_path = Path(sys.argv[1]) if len(sys.argv) > 1 else here.parents[1] / "backend/assessment_questionnaire/questions.json"
    bank = json.loads(bank_path.read_text(encoding="utf-8"))
    cases = build(bank)
    ids = [x["case_id"] for x in cases]
    assert len(ids) == len(set(ids)), "duplicate case_id"
    out = {"version": "p10-flexible-availability-v3", "baseline_commit": "cad66888200633bd6b378dc01894b2e67fbfb37d",
           "bank_sha256": hashlib.sha256(bank_path.read_text(encoding="utf-8").encode("utf-8")).hexdigest(),
           "topics": TOPICS, "topic_map": TOPIC_MAP, "cases": cases}
    (here / "cases.json").write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote cases.json with {len(cases)} cases")


if __name__ == "__main__":
    main()
