package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.domain.Question

/**
 * Demo-only copy of the reassessment example set kept in
 * backend/assessment_questionnaire/questions.json: prompts, choices, correct keys, scores, topics and
 * explanations. It is separate from the onboarding items and has the same topic coverage. These are
 * authored examples, not official exam questions or a validated assessment. Live builds never include
 * this file.
 */
object DemoReassessmentFixture {
    val questions: List<ReassessmentQuestion> = listOf(
        ReassessmentQuestion(
            Question(19, "Excel 2021에서 한 셀 안에서 줄을 바꿔 입력하려면 어떤 키를 누르나요?", linkedMapOf("A" to "Enter", "B" to "Alt+Enter", "C" to "Ctrl+Enter", "D" to "Shift+Enter"), "B", 1.0),
            topic = "스프레드시트 기본",
            explanation = "Alt+Enter는 같은 셀 안에서 줄을 바꿉니다. Ctrl+Enter는 선택한 여러 셀에 같은 값을 한 번에 입력합니다.",
        ),
        ReassessmentQuestion(
            Question(20, "같은 통합 문서의 Sheet2 워크시트에 있는 B3 셀을 참조하는 수식으로 알맞은 것은 무엇인가요?", linkedMapOf("A" to "=Sheet2!B3", "B" to "=Sheet2.B3", "C" to "=Sheet2:B3", "D" to "=[Sheet2]B3"), "A", 1.0),
            topic = "스프레드시트 기본",
            explanation = "다른 워크시트의 셀은 시트 이름 뒤에 느낌표(!)를 붙이고 셀 주소를 적어 참조합니다.",
        ),
        ReassessmentQuestion(
            Question(21, "Excel 2021에서 표의 행을 특정 열의 값에 따라 오름차순이나 내림차순으로 다시 배열하는 기능은 무엇인가요?", linkedMapOf("A" to "정렬", "B" to "부분합", "C" to "목표값 찾기", "D" to "데이터 유효성 검사"), "A", 1.0),
            topic = "스프레드시트 기본",
            explanation = "정렬은 기준 열의 값 순서대로 행을 다시 배열합니다. 부분합은 그룹별 요약 값을 계산합니다.",
        ),
        ReassessmentQuestion(
            Question(22, "Excel 2021에서 셀에 1부터 100 사이의 정수만 입력할 수 있도록 제한하려면 어떤 기능을 사용하나요?", linkedMapOf("A" to "조건부 서식", "B" to "데이터 유효성 검사", "C" to "시트 보호", "D" to "자동 채우기"), "B", 1.0),
            topic = "스프레드시트 기본",
            explanation = "데이터 유효성 검사로 입력할 수 있는 값의 종류와 범위를 정하면 조건에 맞지 않는 입력을 막을 수 있습니다.",
        ),
        ReassessmentQuestion(
            Question(23, "B2:B10 범위에서 값이 80 이상인 셀의 개수를 구하는 수식으로 알맞은 것은 무엇인가요?", linkedMapOf("A" to "=COUNT(B2:B10)", "B" to "=COUNTIF(B2:B10,\">=80\")", "C" to "=SUMIF(B2:B10,\">=80\")", "D" to "=COUNTA(B2:B10)"), "B", 1.0),
            topic = "함수와 데이터 관리",
            explanation = "COUNTIF는 범위에서 조건을 만족하는 셀의 개수를 셉니다. SUMIF는 조건에 맞는 값의 합계를 구합니다.",
        ),
        ReassessmentQuestion(
            Question(24, "=ROUND(3.14159, 2)의 결과로 알맞은 것은 무엇인가요?", linkedMapOf("A" to "3.14", "B" to "3.15", "C" to "3.1", "D" to "3"), "A", 1.0),
            topic = "함수와 데이터 관리",
            explanation = "ROUND는 지정한 자릿수로 반올림합니다. 소수 둘째 자리까지 남기며 셋째 자리가 1이므로 3.14가 됩니다.",
        ),
        ReassessmentQuestion(
            Question(25, "시간에 따른 값의 변화 추세를 나타낼 때 일반적으로 알맞은 차트는 무엇인가요?", linkedMapOf("A" to "원형 차트", "B" to "꺾은선형 차트", "C" to "방사형 차트", "D" to "도넛형 차트"), "B", 1.0),
            topic = "차트와 분석",
            explanation = "꺾은선형 차트는 일정한 간격의 시간 흐름에 따른 변화와 추세를 보여 주는 데 적합합니다.",
        ),
        ReassessmentQuestion(
            Question(26, "Excel 2021에서 워크시트의 특정 범위만 인쇄되도록 지정하려면 어떤 기능을 사용하나요?", linkedMapOf("A" to "인쇄 영역 설정", "B" to "인쇄 제목", "C" to "틀 고정", "D" to "창 나누기"), "A", 1.0),
            topic = "페이지 설정과 인쇄",
            explanation = "인쇄 영역을 설정하면 지정한 범위만 인쇄됩니다. 인쇄 제목은 각 페이지에 반복할 행이나 열을 정합니다.",
        ),
        ReassessmentQuestion(
            Question(27, "Excel 2021에서 매크로를 기록할 때 지정할 수 있는 바로 가기 키의 형식으로 알맞은 것은 무엇인가요?", linkedMapOf("A" to "Ctrl+영문자", "B" to "Alt+숫자", "C" to "Ctrl+숫자", "D" to "F1~F12 기능 키"), "A", 1.0),
            topic = "매크로 활용",
            explanation = "매크로 기록 대화 상자에서는 Ctrl과 함께 누를 영문자를 지정합니다. 대문자를 입력하면 Ctrl+Shift+영문자가 됩니다.",
        ),
        ReassessmentQuestion(
            Question(28, "Windows에서 응답하지 않는 프로그램을 끝낼 수 있는 작업 관리자를 바로 여는 바로 가기 키는 무엇인가요?", linkedMapOf("A" to "Ctrl+Shift+Esc", "B" to "Alt+F4", "C" to "Windows 로고 키+D", "D" to "Ctrl+Alt+Tab"), "A", 1.0),
            topic = "컴퓨터 시스템 활용",
            explanation = "Ctrl+Shift+Esc는 작업 관리자를 바로 엽니다. Alt+F4는 활성 창을 닫고, Windows 로고 키+D는 바탕 화면을 표시합니다.",
        ),
        ReassessmentQuestion(
            Question(29, "CPU 안에서 처리 중인 데이터나 명령을 잠시 저장하며 가장 빠르게 접근할 수 있는 기억 장치는 무엇인가요?", linkedMapOf("A" to "레지스터", "B" to "하드 디스크", "C" to "ROM", "D" to "광디스크"), "A", 1.0),
            topic = "컴퓨터 시스템 활용",
            explanation = "레지스터는 CPU 내부에 있는 고속 임시 기억 장치로, 주기억 장치보다 접근 속도가 빠릅니다.",
        ),
        ReassessmentQuestion(
            Question(30, "Windows에서 디스크에 흩어져 저장된 파일 조각을 모아 접근 속도를 높이는 도구는 무엇인가요?", linkedMapOf("A" to "디스크 정리", "B" to "드라이브 조각 모음 및 최적화", "C" to "시스템 복원", "D" to "파일 히스토리"), "B", 1.0),
            topic = "컴퓨터 시스템 활용",
            explanation = "드라이브 조각 모음 및 최적화는 흩어진 파일 조각을 모아 읽기 속도를 높입니다. 디스크 정리는 불필요한 파일을 지웁니다.",
        ),
        ReassessmentQuestion(
            Question(31, "IPv6 주소는 몇 비트로 구성되나요?", linkedMapOf("A" to "32비트", "B" to "64비트", "C" to "128비트", "D" to "256비트"), "C", 1.0),
            topic = "인터넷 자료 활용",
            explanation = "IPv6 주소는 128비트이며 16비트씩 8부분을 콜론(:)으로 구분합니다. IPv4 주소는 32비트입니다.",
        ),
        ReassessmentQuestion(
            Question(32, "웹 브라우저와 웹 서버가 하이퍼텍스트 문서를 주고받을 때 사용하는 프로토콜은 무엇인가요?", linkedMapOf("A" to "FTP", "B" to "HTTP", "C" to "SMTP", "D" to "TELNET"), "B", 1.0),
            topic = "인터넷 자료 활용",
            explanation = "HTTP는 웹 문서를 주고받는 프로토콜입니다. FTP는 파일 전송, SMTP는 전자우편 발송에 사용합니다.",
        ),
        ReassessmentQuestion(
            Question(33, "256색까지 표현하며 여러 장면을 이어 보여 주는 간단한 애니메이션을 지원하는 이미지 형식은 무엇인가요?", linkedMapOf("A" to "GIF", "B" to "BMP", "C" to "TIFF", "D" to "JPEG"), "A", 1.0),
            topic = "인터넷 자료 활용",
            explanation = "GIF는 최대 256색을 사용하고 여러 프레임으로 된 간단한 애니메이션을 저장할 수 있습니다.",
        ),
        ReassessmentQuestion(
            Question(34, "메일 서버에 도착한 전자우편을 사용자 컴퓨터로 내려받을 때 사용하는 프로토콜은 무엇인가요?", linkedMapOf("A" to "SMTP", "B" to "POP3", "C" to "SNMP", "D" to "DHCP"), "B", 1.0),
            topic = "인터넷 자료 활용",
            explanation = "POP3는 메일 서버의 메일을 사용자 컴퓨터로 가져올 때 사용합니다. SMTP는 메일을 보낼 때 사용합니다.",
        ),
        ReassessmentQuestion(
            Question(35, "사용자의 파일을 암호화한 뒤 복구해 주는 대가로 돈을 요구하는 악성 프로그램은 무엇인가요?", linkedMapOf("A" to "랜섬웨어", "B" to "스파이웨어", "C" to "애드웨어", "D" to "트로이 목마"), "A", 1.0),
            topic = "컴퓨터 시스템 보호",
            explanation = "랜섬웨어는 파일을 암호화해 쓸 수 없게 만든 뒤 대가를 요구합니다. 중요한 파일을 따로 백업해 두면 피해를 줄일 수 있습니다.",
        ),
        ReassessmentQuestion(
            Question(36, "공개키 암호화 방식에서 상대방에게 보낼 데이터를 암호화할 때 사용하는 키는 무엇인가요?", linkedMapOf("A" to "받는 사람의 공개키", "B" to "받는 사람의 개인키", "C" to "보내는 사람의 개인키", "D" to "두 사람이 미리 나눈 비밀키"), "A", 1.0),
            topic = "컴퓨터 시스템 보호",
            explanation = "공개키 암호화에서는 받는 사람의 공개키로 암호화하고, 받는 사람만 가진 개인키로 복호화합니다.",
        ),
    )
}
