package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.domain.Question

/**
 * Demo-only copy of the onboarding selection that the questions endpoint serves from
 * backend/assessment_questionnaire/questions.json (컴퓨터 일반 and 스프레드시트 일반
 * written-exam examples): prompts, choices, correct keys, and scores only. Unselected
 * stored questions and internal review metadata are not copied. Authored examples, not
 * official exam questions or a validated diagnostic. Live builds never include this file.
 */
object DemoQuestionFixture {
    val questions: List<Question> = listOf(
        Question(
            id = 1,
            prompt = "셀 주소 A1을 수식에서 고정 참조로 사용하려면 어떻게 표시하나요?",
            choices = linkedMapOf("A" to "A1", "B" to "\$A\$1", "C" to "A\$", "D" to "#A1"),
            correctAnswer = "B",
            possibleScore = 1.0,
        ),
        Question(
            id = 2,
            prompt = "A1부터 A5까지의 합계를 구하는 수식으로 알맞은 것은 무엇인가요?",
            choices = linkedMapOf("A" to "=COUNT(A1:A5)", "B" to "=SUM(A1:A5)", "C" to "=MAX(A1:A5)", "D" to "=AVERAGE(A1:A5)"),
            correctAnswer = "B",
            possibleScore = 1.0,
        ),
        Question(
            id = 3,
            prompt = "A1의 값이 60 이상이면 '통과', 아니면 '재시험'을 표시하는 수식은 무엇인가요?",
            choices = linkedMapOf("A" to "=IF(A1>=60,\"통과\",\"재시험\")", "B" to "=SUM(A1>=60,\"통과\",\"재시험\")", "C" to "=COUNTIF(A1,60,\"통과\")", "D" to "=AVERAGE(A1>=60)"),
            correctAnswer = "A",
            possibleScore = 1.0,
        ),
        Question(
            id = 4,
            prompt = "전체에 대한 각 항목의 비율을 비교할 때 일반적으로 알맞은 차트는 무엇인가요?",
            choices = linkedMapOf("A" to "원형 차트", "B" to "분산형 차트", "C" to "주식형 차트", "D" to "표면형 차트"),
            correctAnswer = "A",
            possibleScore = 1.0,
        ),
        Question(
            id = 5,
            prompt = "자동 필터를 적용했을 때 조건에 맞지 않는 행은 어떻게 되나요?",
            choices = linkedMapOf("A" to "영구 삭제됩니다", "B" to "다른 워크시트로 이동합니다", "C" to "화면에서 일시적으로 숨겨집니다", "D" to "셀 수식이 값으로 바뀝니다"),
            correctAnswer = "C",
            possibleScore = 1.0,
        ),
        Question(
            id = 6,
            prompt = "Windows 파일 탐색기에서 선택한 파일의 이름을 바꾸는 바로 가기 키는 무엇인가요?",
            choices = linkedMapOf("A" to "F1", "B" to "F2", "C" to "F5", "D" to "F11"),
            correctAnswer = "B",
            possibleScore = 1.0,
        ),
        Question(
            id = 7,
            prompt = "실행 중인 프로그램의 작업 데이터를 임시로 보관하며 전원을 끄면 내용이 사라지는 장치는 무엇인가요?",
            choices = linkedMapOf("A" to "SSD", "B" to "ROM", "C" to "USB 플래시 드라이브", "D" to "RAM"),
            correctAnswer = "D",
            possibleScore = 1.0,
        ),
        Question(
            id = 8,
            prompt = "Windows에서 설치된 장치의 상태를 확인하고 특정 장치의 드라이버를 업데이트하는 도구는 무엇인가요?",
            choices = linkedMapOf("A" to "장치 관리자", "B" to "파일 탐색기", "C" to "디스크 정리", "D" to "작업 스케줄러"),
            correctAnswer = "A",
            possibleScore = 1.0,
        ),
        Question(
            id = 9,
            prompt = "웹 주소에 입력한 도메인 이름을 IP 주소로 확인할 때 사용하는 서비스는 무엇인가요?",
            choices = linkedMapOf("A" to "SMTP", "B" to "FTP", "C" to "DNS", "D" to "DHCP"),
            correctAnswer = "C",
            possibleScore = 1.0,
        ),
        Question(
            id = 10,
            prompt = "사진을 저장할 때 널리 사용하는 손실 압축 이미지 형식은 무엇인가요?",
            choices = linkedMapOf("A" to "PNG", "B" to "JPEG", "C" to "BMP", "D" to "SVG"),
            correctAnswer = "B",
            possibleScore = 1.0,
        ),
        Question(
            id = 11,
            prompt = "네트워크를 통해 저장 공간이나 컴퓨팅 자원을 필요에 따라 사용하는 방식은 무엇인가요?",
            choices = linkedMapOf("A" to "디스크 조각 모음", "B" to "로컬 이중 부팅", "C" to "화면 미러링", "D" to "클라우드 컴퓨팅"),
            correctAnswer = "D",
            possibleScore = 1.0,
        ),
        Question(
            id = 12,
            prompt = "웹 페이지의 제목, 문단, 링크 같은 문서 구조를 정의하는 데 사용하는 것은 무엇인가요?",
            choices = linkedMapOf("A" to "HTML", "B" to "CSS", "C" to "DNS", "D" to "JPEG"),
            correctAnswer = "A",
            possibleScore = 1.0,
        ),
        Question(
            id = 13,
            prompt = "은행을 사칭한 이메일이 링크에서 비밀번호를 입력하라고 합니다. 가장 적절한 대응은 무엇인가요?",
            choices = linkedMapOf("A" to "자물쇠 표시만 보고 입력한다", "B" to "발신자 이름만 보고 입력한다", "C" to "링크를 열지 않고 은행 공식 앱이나 연락처로 확인한다", "D" to "답장으로 비밀번호를 보낸다"),
            correctAnswer = "C",
            possibleScore = 1.0,
        ),
        Question(
            id = 14,
            prompt = "정해진 규칙에 따라 들어오거나 나가는 네트워크 통신을 허용하거나 차단하는 기능은 무엇인가요?",
            choices = linkedMapOf("A" to "파일 압축", "B" to "방화벽", "C" to "화면 잠금", "D" to "디스크 조각 모음"),
            correctAnswer = "B",
            possibleScore = 1.0,
        ),
        Question(
            id = 15,
            prompt = "Excel 2021에서 사용자가 워크시트를 삭제하거나 이름을 바꾸지 못하게 하려면 어떤 기능을 사용하나요?",
            choices = linkedMapOf("A" to "셀 잠금", "B" to "인쇄 영역 설정", "C" to "틀 고정", "D" to "통합 문서 구조 보호"),
            correctAnswer = "D",
            possibleScore = 1.0,
        ),
        Question(
            id = 16,
            prompt = "Excel 2021에서 값이 100 이상인 셀만 자동으로 색을 표시하려면 어떤 기능이 알맞나요?",
            choices = linkedMapOf("A" to "조건부 서식", "B" to "틀 고정", "C" to "찾기 및 바꾸기", "D" to "인쇄 제목"),
            correctAnswer = "A",
            possibleScore = 1.0,
        ),
        Question(
            id = 17,
            prompt = "Excel 2021에서 여러 페이지에 걸친 표의 첫 행을 각 인쇄 페이지 위에 반복하려면 어떤 기능이 알맞나요?",
            choices = linkedMapOf("A" to "인쇄 영역", "B" to "틀 고정", "C" to "인쇄 제목", "D" to "페이지 나누기 미리 보기"),
            correctAnswer = "C",
            possibleScore = 1.0,
        ),
        Question(
            id = 18,
            prompt = "VBA 매크로를 포함한 통합 문서를 표준 매크로 사용 형식으로 저장할 때 사용하는 확장자는 무엇인가요?",
            choices = linkedMapOf("A" to ".xlsx", "B" to ".xlsm", "C" to ".csv", "D" to ".xltx"),
            correctAnswer = "B",
            possibleScore = 1.0,
        ),
    )
}
