from .models import ProblemResult

QUESTIONS = [
    {
        "problem_id": 1,
        "topic": "하드웨어·운영체제",
        "question": "다음 중 컴퓨터의 주기억장치에 해당하는 것은 무엇인가요?",
        "choices": [
            "RAM",
            "SSD",
            "USB 메모리",
            "CD-ROM",
        ],
        "answer": 1,
    },
    {
        "problem_id": 2,
        "topic": "하드웨어·운영체제",
        "question": "다음 중 운영체제의 주요 역할로 가장 적절한 것은 무엇인가요?",
        "choices": [
            "인터넷 속도를 항상 일정하게 유지한다.",
            "모든 문서를 자동으로 작성한다.",
            "컴퓨터의 하드웨어와 소프트웨어 자원을 관리한다.",
            "컴퓨터의 전원을 대신 공급한다.",
        ],
        "answer": 3,
    },
    {
        "problem_id": 3,
        "topic": "네트워크·보안",
        "question": "인터넷에서 데이터를 주고받을 때 사용하는 통신 규약을 의미하는 것은 무엇인가요?",
        "choices": [
            "디렉터리",
            "프로토콜",
            "드라이버",
            "레지스트리",
        ],
        "answer": 2,
    },
    {
        "problem_id": 4,
        "topic": "네트워크·보안",
        "question": "다음 중 다른 사람에게 계정 정보를 알려주지 않는 것과 가장 관련이 있는 보안 원칙은 무엇인가요?",
        "choices": [
            "가용성",
            "확장성",
            "호환성",
            "기밀성",
        ],
        "answer": 4,
    },
    {
        "problem_id": 5,
        "topic": "수식·함수",
        "question": "스프레드시트에서 범위 내 숫자가 입력된 셀의 개수를 구하는 함수는 무엇인가요?",
        "choices": [
            "COUNT",
            "COUNTA",
            "COUNTBLANK",
            "SUM",
        ],
        "answer": 1,
    },
    {
        "problem_id": 6,
        "topic": "수식·함수",
        "question": "A1 셀과 A2 셀의 값을 더하는 수식으로 올바른 것은 무엇인가요?",
        "choices": [
            "=A1-A2",
            "=A1*A2",
            "=A1+A2",
            "=A1/A2",
        ],
        "answer": 3,
    },
    {
        "problem_id": 7,
        "topic": "데이터 도구(정렬·필터)",
        "question": "스프레드시트에서 특정 조건에 맞는 데이터만 표시하고 나머지를 숨기려 할 때 사용하는 기능은 무엇인가요?",
        "choices": [
            "병합",
            "필터",
            "인쇄 영역",
            "페이지 나누기",
        ],
        "answer": 2,
    },
    {
        "problem_id": 8,
        "topic": "차트·인쇄",
        "question": "스프레드시트의 데이터를 막대나 선 등의 형태로 시각적으로 표현하는 기능은 무엇인가요?",
        "choices": [
            "필터",
            "정렬",
            "찾기",
            "차트",
        ],
        "answer": 4,
    },
]


def get_questions():
    """Assessment 문제 목록을 반환합니다."""
    return QUESTIONS


def grade_assessment(answers):
    """
    사용자의 답안을 채점하고 문제별 결과를 반환합니다.
    """
    results = []

    for question in QUESTIONS:
        problem_id = question["problem_id"]
        selected_answer = answers.get(problem_id)

        earned_score = (
            1
            if selected_answer == question["answer"]
            else 0
        )

        results.append(
            ProblemResult(
                problem_id=problem_id,
                topic=question["topic"],
                possible_score=1,
                earned_score=earned_score,
            )
        )

    return tuple(results)


def get_topic_statistics(results):
    """
    문제별 결과를 유형별로 묶어
    맞힌 문제 수 / 전체 문제 수를 계산합니다.
    """
    statistics = {}

    for result in results:
        topic = result.topic

        if topic not in statistics:
            statistics[topic] = {
                "earned_score": 0,
                "possible_score": 0,
            }

        statistics[topic]["earned_score"] += result.earned_score
        statistics[topic]["possible_score"] += result.possible_score

    for topic, data in statistics.items():
        data["score_percent"] = (
            data["earned_score"] / data["possible_score"] * 100
        )

    return statistics


def get_total_score(results):
    """전체 진단 점수를 계산합니다."""
    earned_score = sum(result.earned_score for result in results)
    possible_score = sum(result.possible_score for result in results)

    return {
        "earned_score": earned_score,
        "possible_score": possible_score,
        "score_percent": (
            earned_score / possible_score * 100
            if possible_score > 0
            else 0
        ),
    }