"""Two-subject written bank content and planning from its onboarding selection's reviewed topics."""
import copy
import itertools
import json
import unittest
from unittest.mock import patch

from api_service import build_plan_request
from assessment_service import _load_question_bank, _onboarding_selection, get_public_questions
from license_planner.agent import PlanningAgent
from license_planner.catalog import get_certification
from license_planner.models import InsufficientCapacityError
from license_planner.tools import PlannerTools, check_initial_capacity
from test_support.question_banks import larger_level_2_bank, level_2_bank, temporary_question_bank

CERT_ID = "computer_specialist_level_2"
COMPUTER_GENERAL = {"컴퓨터 시스템 활용", "인터넷 자료 활용", "컴퓨터 시스템 보호"}
ORIGINAL_LABELS = {1: "스프레드시트 기본", 2: "함수와 데이터 관리", 3: "함수와 데이터 관리",
                   4: "차트와 분석", 5: "스프레드시트 기본"}
ORIGINAL_KEYS = {1: "B", 2: "B", 3: "A", 4: "A", 5: "C"}
SUBJECTS = {"컴퓨터 일반", "스프레드시트 일반"}
SECTION_IDS = {
    ("컴퓨터 일반", "컴퓨터 시스템 활용"): [6, 7, 8],
    ("컴퓨터 일반", "인터넷 자료 활용"): [9, 10, 11, 12],
    ("컴퓨터 일반", "컴퓨터 시스템 보호"): [13, 14],
    ("스프레드시트 일반", "응용 프로그램 준비"): [15],
    ("스프레드시트 일반", "데이터 입력"): [16],
    ("스프레드시트 일반", "데이터 계산"): [1, 2, 3],
    ("스프레드시트 일반", "데이터 관리"): [5],
    ("스프레드시트 일반", "차트 활용"): [4],
    ("스프레드시트 일반", "출력 작업"): [17],
    ("스프레드시트 일반", "매크로 활용"): [18],
}
CALL_IDS = itertools.count(1)


def bank_questions():
    """The configured onboarding selection, in order."""
    return _onboarding_selection(CERT_ID)[1]


def results_for(questions, correct):
    """Locally-scored results answering ``correct(question)`` questions with the key, others UNKNOWN."""
    rows = []
    for question in questions:
        right = correct(question)
        rows.append({"problem_id": question["id"], "question": question["prompt"],
                     "choices": {**question["choices"], "UNKNOWN": "모르겠음"},
                     "correct_answer": question["correct_answer"],
                     "user_answer": question["correct_answer"] if right else "UNKNOWN",
                     "is_correct": right, "possible_score": question["possible_score"]})
    return {"certification_id": CERT_ID, "results": rows}


def payload(assessment, *, exam_date="2026/11/02/18/00"):
    return {"certification_id": CERT_ID,
            "self_assessment": "스프레드시트는 익숙하지만 컴퓨터 일반은 처음입니다.",
            "preparation_start": "2026/10/19/18/00", "exam_date": exam_date,
            "busy_schedules": [], "assessment_results": assessment,
            "weekly_availability": {day: [{"start": "18:00", "end": "20:00"}]
                                    for day in ("monday", "wednesday", "friday")}}


def computer_general_missed(question):
    return question["topic"] not in COMPUTER_GENERAL


def model_reply(name, args):
    return {"status": "completed", "output": [{"type": "function_call", "call_id": f"call_{next(CALL_IDS)}",
                                               "name": name, "arguments": json.dumps(args, ensure_ascii=False)}]}


def build(rows):
    return model_reply("build_study_schedule", {"daily_topic_minutes": [
        {"date": day, "topic": topic, "minutes": minutes} for day, topic, minutes in rows]})


def run_recording_agent(request, replies):
    sent = []

    def ask_model(input_items, tools):
        sent.append(copy.deepcopy(input_items))
        return replies[len(sent) - 1]

    with patch.object(PlanningAgent, "_ask_model", side_effect=ask_model):
        return PlanningAgent(api_key="test-key").run(request), sent


def custom_result(problem_id, *, correct=True, possible_score=1):
    """A consistently scored custom question that matches no bank record."""
    return {"problem_id": problem_id, "question": f"사용자 정의 문항 {problem_id}",
            "choices": {"A": "가", "B": "나", "UNKNOWN": "모르겠음"}, "correct_answer": "A",
            "user_answer": "A" if correct else "B", "is_correct": correct, "possible_score": possible_score}


def mixed_request(reviewed_ids, custom_rows):
    """Reviewed selection items answered correctly, followed by custom results."""
    by_id = {question["id"]: question for question in bank_questions()}
    assessment = results_for([by_id[question_id] for question_id in reviewed_ids], lambda question: True)
    assessment["results"] += custom_rows
    return build_plan_request(payload(assessment), require_assessment=True)


def topic_mapping(problem_id, topic):
    return {"problem_id": problem_id, "topics": [{"topic": topic, "weight": 1}]}


def input_metadata(sent):
    return json.loads(sent[0][0]["content"].split("Input metadata: ", 1)[1])


class WrittenBankContentTests(unittest.TestCase):
    def test_bank_records_its_syllabus_cycle_and_onboarding_selection(self):
        document = _load_question_bank(CERT_ID)
        retained = [question["id"] for question in document["questions"]]

        self.assertEqual(document["syllabus_version"], "2024-2026")
        self.assertEqual(document["onboarding_question_ids"], list(range(1, 19)))
        self.assertEqual(len(retained), len(set(retained)))
        self.assertTrue(all(isinstance(question_id, int) and question_id > 0 for question_id in retained))
        for question in document["questions"]:
            with self.subTest(question["id"]):
                self.assertIn(question["review_status"], {"draft", "reviewed"})
                self.assertIn(question["provenance"]["kind"], {"authored", "external"})
                self.assertTrue(question["provenance"]["reference"].strip())

    def test_selection_has_nine_valid_one_point_items_per_subject(self):
        questions = bank_questions()
        topics = get_certification(CERT_ID).topics

        self.assertEqual([question["id"] for question in questions], list(range(1, 19)))
        for question in questions:
            with self.subTest(question["id"]):
                self.assertEqual(list(question["choices"]), ["A", "B", "C", "D"])
                self.assertTrue(all(isinstance(text, str) and text.strip()
                                    for text in question["choices"].values()))
                self.assertIn(question["correct_answer"], question["choices"])
                self.assertEqual(question["possible_score"], 1)
                self.assertIn(question["topic"], topics)
                self.assertEqual(question["review_status"], "reviewed")
                self.assertIn(question["subject"], SUBJECTS)
                self.assertTrue(question["explanation"].strip())
                self.assertEqual(question["provenance"]["kind"], "authored")
        computer = [question["id"] for question in questions if question["subject"] == "컴퓨터 일반"]
        self.assertEqual(computer, list(range(6, 15)))
        self.assertEqual(len(questions) - len(computer), 9)
        self.assertEqual({question["topic"] for question in questions}, set(topics))
        self.assertTrue(all((question["topic"] in COMPUTER_GENERAL) == (question["subject"] == "컴퓨터 일반")
                            for question in questions))

    def test_selection_covers_every_major_syllabus_section(self):
        coverage = {}
        for question in bank_questions():
            coverage.setdefault((question["subject"], question["syllabus_section"]), []).append(question["id"])

        self.assertEqual(coverage, SECTION_IDS)

    def test_original_items_keep_their_labels_and_keys(self):
        original = {question["id"]: question for question in bank_questions()[:5]}

        self.assertEqual({key: item["topic"] for key, item in original.items()}, ORIGINAL_LABELS)
        self.assertEqual({key: item["correct_answer"] for key, item in original.items()}, ORIGINAL_KEYS)
        self.assertEqual(original[1]["prompt"], "셀 주소 A1을 수식에서 고정 참조로 사용하려면 어떻게 표시하나요?")

    def test_public_questions_keep_the_existing_fields_without_internal_metadata(self):
        public = get_public_questions(CERT_ID)

        self.assertEqual(set(public), {"certification_id", "certification_name", "questions"})
        self.assertEqual(len(public["questions"]), 18)
        for question in public["questions"]:
            self.assertEqual(set(question), {"id", "prompt", "choices", "correct_answer", "possible_score"})


class ReviewedTopicPlanningTests(unittest.TestCase):
    def test_complete_selection_uses_reviewed_topics_for_mixed_scores(self):
        request = build_plan_request(payload(results_for(bank_questions(), computer_general_missed)),
                                     require_assessment=True)
        tools = PlannerTools(request)

        result = tools.call("analyze_exam_results", {})

        self.assertEqual(request.assessment_items, ())
        self.assertEqual(len(request.problem_results), 18)
        self.assertEqual((result["total_possible_score"], result["total_earned_score"]), (18, 9))
        scores = {row["topic"]: row["score_percent"] for row in result["topic_statistics"]}
        self.assertEqual(set(scores), set(get_certification(CERT_ID).topics))
        self.assertEqual({topic for topic, percent in scores.items() if percent == 0}, COMPUTER_GENERAL)
        self.assertTrue(all(scores[topic] == 100 for topic in set(scores) - COMPUTER_GENERAL))
        self.assertEqual([row["topic"] for row in result["topic_statistics"][:3]],
                         sorted(COMPUTER_GENERAL, key=str.casefold))

    def test_schedule_omitting_a_required_topic_is_rejected_and_corrected(self):
        request = build_plan_request(payload(results_for(bank_questions(), computer_general_missed)),
                                     require_assessment=True)
        topics = get_certification(CERT_ID).topics
        days = ("2026/10/19", "2026/10/21", "2026/10/23", "2026/10/26")
        complete = [(days[index % len(days)], topic, 30 if topic in COMPUTER_GENERAL else 20)
                    for index, topic in enumerate(topics)]
        omitted = [row for row in complete if row[1] != "컴퓨터 시스템 보호"]
        replies = [model_reply("analyze_exam_results", {}), build(omitted), build(complete)]

        (tools, _), sent = run_recording_agent(request, replies)

        self.assertIn("Topic classification is already supplied", sent[0][0]["content"])
        rejection = json.loads(sent[2][-1]["output"])["result"]
        self.assertRegex(rejection, r"^SCHEDULE_ERROR: .*missing=\['컴퓨터 시스템 보호'\]")
        self.assertEqual({block.topic for block in tools.schedule}, set(topics))
        self.assertEqual([call["tool"] for call in tools.trace.calls],
                         ["analyze_exam_results", "build_study_schedule", "build_study_schedule"])

    def test_capacity_for_fewer_minutes_than_reviewed_topics_stops_before_the_model(self):
        request = build_plan_request(payload(results_for(bank_questions(), computer_general_missed),
                                             exam_date="2026/10/19/18/07"), require_assessment=True)

        with self.assertRaisesRegex(InsufficientCapacityError, "시간이 7분이라 진단된 학습 주제 8개에"):
            check_initial_capacity(request)

    def test_original_five_question_request_uses_reviewed_topics(self):
        request = build_plan_request(payload(results_for(bank_questions()[:5], lambda question: True)),
                                     require_assessment=True)

        result = PlannerTools(request).call("analyze_exam_results", {})

        self.assertEqual(request.assessment_items, ())
        self.assertEqual({row.problem_id: row.topic for row in request.problem_results}, ORIGINAL_LABELS)
        self.assertEqual((result["total_possible_score"], result["total_earned_score"]), (5, 5))
        self.assertEqual({row["topic"] for row in result["topic_statistics"]}, set(ORIGINAL_LABELS.values()))

    def test_reviewed_subsets_keep_each_stored_topic_in_any_order(self):
        questions = bank_questions()
        subsets = {"one item": [questions[12]], "seventeen items": questions[:17],
                   "reordered subset": [questions[9], questions[2], questions[15]]}
        for label, subset in subsets.items():
            with self.subTest(label):
                request = build_plan_request(payload(results_for(subset, computer_general_missed)),
                                             require_assessment=True)
                self.assertEqual(request.assessment_items, ())
                self.assertEqual([(row.problem_id, row.topic) for row in request.problem_results],
                                 [(question["id"], question["topic"]) for question in subset])
                self.assertEqual(sum(row.earned_score for row in request.problem_results),
                                 sum(computer_general_missed(question) for question in subset))
    def test_reordered_selection_with_unknown_answers_uses_reviewed_topics(self):
        assessment = results_for(bank_questions(), computer_general_missed)
        assessment["results"].reverse()

        request = build_plan_request(payload(assessment), require_assessment=True)

        self.assertEqual(request.assessment_items, ())
        self.assertEqual([result.problem_id for result in request.problem_results], list(range(18, 0, -1)))
        self.assertEqual(sum(result.earned_score for result in request.problem_results), 9)

    def test_unselected_items_do_not_change_the_selected_analysis(self):
        assessment = results_for(bank_questions(), computer_general_missed)
        baseline = PlannerTools(build_plan_request(payload(assessment), require_assessment=True)).call(
            "analyze_exam_results", {})

        with temporary_question_bank(larger_level_2_bank()):
            request = build_plan_request(payload(assessment), require_assessment=True)
            result = PlannerTools(request).call("analyze_exam_results", {})

        self.assertEqual(request.assessment_items, ())
        self.assertEqual(len(request.problem_results), 18)
        self.assertEqual(result, baseline)

    def test_unasked_item_topic_adds_no_points_or_required_topic(self):
        document = level_2_bank()
        document["onboarding_question_ids"] = list(range(1, 18))
        with temporary_question_bank(document):
            selected = bank_questions()
            request = build_plan_request(payload(results_for(selected, computer_general_missed)),
                                         require_assessment=True)
            tools = PlannerTools(request)
            result = tools.call("analyze_exam_results", {})

        self.assertEqual(len(request.problem_results), 17)
        self.assertEqual(result["total_possible_score"], 17)
        self.assertNotIn("매크로 활용", {row["topic"] for row in result["topic_statistics"]})
        self.assertNotIn("매크로 활용", tools.build_topics())
        self.assertEqual(len(tools.build_topics()), 7)

    def test_changed_content_leaves_only_that_question_unresolved(self):
        def changed(index, **fields):
            questions = copy.deepcopy(bank_questions())
            questions[index].update(fields)
            return questions

        cases = {"reused id with different text": (7, changed(7, prompt="같은 ID를 다시 쓴 사용자 정의 문항입니다.")),
                 "reused id with different choices": (8, changed(
                     8, choices={**bank_questions()[8]["choices"], "D": "바뀐 선택지"})),
                 "reused id with a different key": (9, changed(9, correct_answer="A")),
                 "reused id with a different score": (10, changed(10, possible_score=2)),
                 "id outside the bank": (18, bank_questions() + [{**bank_questions()[0], "id": 19}])}
        for label, (index, questions) in cases.items():
            with self.subTest(label):
                request = build_plan_request(payload(results_for(questions, lambda question: True)),
                                             require_assessment=True)
                changed_id = questions[index]["id"]
                self.assertEqual([item.problem_id for item in request.assessment_items], [changed_id])
                self.assertEqual(len(request.problem_results), len(questions) - 1)
                self.assertNotIn(changed_id, {row.problem_id for row in request.problem_results})
    def test_submitted_reviewed_item_outside_onboarding_uses_its_stored_topic(self):
        with temporary_question_bank(larger_level_2_bank()):
            stored = {question["id"]: question for question in _load_question_bank(CERT_ID)["questions"]}
            request = build_plan_request(payload(results_for(bank_questions() + [stored[19]], lambda question: True)),
                                         require_assessment=True)
            served = [question["id"] for question in get_public_questions(CERT_ID)["questions"]]

        self.assertEqual(request.assessment_items, ())
        self.assertEqual([row.problem_id for row in request.problem_results], [*range(1, 19), 19])
        self.assertEqual(request.problem_results[-1].topic, stored[19]["topic"])
        self.assertEqual(served, list(range(1, 19)))
        self.assertEqual(stored[20]["review_status"], "draft")

class MixedEvidenceTests(unittest.TestCase):
    def test_mixed_results_conserve_points_on_same_and_different_topics(self):
        for label, topic in {"same topic": "차트와 분석", "different topic": "컴퓨터 시스템 보호"}.items():
            with self.subTest(label):
                request = mixed_request([4], [custom_result(101, correct=False)])
                tools = PlannerTools(request)

                result = tools.call("analyze_exam_results", {"problem_topics": [topic_mapping(101, topic)]})

                self.assertEqual([row.problem_id for row in request.problem_results], [4])
                self.assertEqual([item.problem_id for item in request.assessment_items], [101])
                self.assertEqual((result["total_possible_score"], result["total_earned_score"]), (2, 1))
                self.assertEqual(sum(row["problem_count"] for row in result["topic_statistics"]), 2)
                self.assertEqual(set(tools.build_topics()), {"차트와 분석", topic})

    def test_mapping_a_reviewed_question_or_omitting_a_custom_one_is_rejected_then_corrected(self):
        request = mixed_request([4, 13], [custom_result(101), custom_result(102, correct=False)])
        tools = PlannerTools(request)
        valid = [topic_mapping(101, "함수와 데이터 관리"), topic_mapping(102, "인터넷 자료 활용")]

        remapped = tools.call("analyze_exam_results", {"problem_topics": [*valid, topic_mapping(4, "스프레드시트 기본")]})
        omitted = tools.call("analyze_exam_results", {"problem_topics": valid[:1]})

        self.assertRegex(remapped, "^INVALID_TOOL_CALL: problem_id 4 already has a reviewed topic")
        self.assertRegex(omitted, r"missing=\[102\], extra=\[\]")
        self.assertFalse(tools.results_analyzed)
        result = tools.call("analyze_exam_results", {"problem_topics": valid})
        self.assertEqual((result["total_possible_score"], result["total_earned_score"]), (4, 3))
        self.assertEqual(set(tools.build_topics()),
                         {"차트와 분석", "컴퓨터 시스템 보호", "함수와 데이터 관리", "인터넷 자료 활용"})

    def test_repeated_successful_analysis_does_not_accumulate_points(self):
        request = mixed_request([4], [custom_result(101)])
        tools = PlannerTools(request)
        arguments = {"problem_topics": [topic_mapping(101, "차트와 분석")]}

        first = tools.call("analyze_exam_results", arguments)
        second = tools.call("analyze_exam_results", copy.deepcopy(arguments))

        self.assertEqual(first, second)
        self.assertEqual(first["total_possible_score"], 2)

    def test_model_input_counts_distinct_questions_and_lists_only_unresolved_ones(self):
        request = mixed_request([1, 2], [custom_result(101)])
        days = ("2026/10/19", "2026/10/21")
        replies = [model_reply("analyze_exam_results", {"problem_topics": [topic_mapping(101, "차트와 분석")]}),
                   build([(days[0], "스프레드시트 기본", 30), (days[0], "함수와 데이터 관리", 30),
                          (days[1], "차트와 분석", 30)])]

        (tools, _), sent = run_recording_agent(request, replies)

        metadata = input_metadata(sent)
        self.assertEqual((metadata["submitted_question_count"], metadata["reviewed_question_count"],
                          metadata["unresolved_question_count"]), (3, 2, 1))
        self.assertEqual([row["problem_id"] for row in metadata["assessment_results"]], [101])
        self.assertIn("Classify only the unresolved questions", sent[0][0]["content"])
        requirements = json.loads(sent[1][-1]["output"])["build_requirements"]
        self.assertEqual(set(requirements["required_topics"]), {"스프레드시트 기본", "함수와 데이터 관리", "차트와 분석"})
        self.assertEqual({block.topic for block in tools.schedule}, set(requirements["required_topics"]))

    def test_full_reviewed_selection_reports_eighteen_questions_without_unresolved_items(self):
        request = build_plan_request(payload(results_for(bank_questions(), computer_general_missed)),
                                     require_assessment=True)
        topics = get_certification(CERT_ID).topics
        replies = [model_reply("analyze_exam_results", {}),
                   build([("2026/10/19", topic, 10) for topic in topics])]

        _, sent = run_recording_agent(request, replies)

        metadata = input_metadata(sent)
        self.assertEqual((metadata["submitted_question_count"], metadata["reviewed_question_count"],
                          metadata["unresolved_question_count"], metadata["assessment_results"]), (18, 18, 0, []))


if __name__ == "__main__":
    unittest.main()
