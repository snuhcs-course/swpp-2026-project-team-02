package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.BuildConfig

/** Screen-specific guidance, shown only where the related action is available. */
internal enum class ContextHelpTopic(val label: String, val title: String, val body: String) {
    START(
        "시작 안내",
        "계획 시작하기",
        "계획 생성에서 자격증과 시험 날짜를 입력하고 진단 문항에 답한 뒤, 공부 시간과 시작 시간을 정해요. " +
            "만든 계획은 이 기기의 앱에 저장돼요. " +
            if (BuildConfig.DEMO_MODE) "데모 버전은 계획 생성과 조정을 이 기기에서 시연용으로 계산해요."
            else "계획 생성과 조정은 서버에서 처리해요.",
    ),
    SAVED_PLANS(
        "저장 안내",
        "저장된 계획",
        "이 목록은 이 기기의 앱에 저장된 계획이에요. 계획을 누르면 일정을 확인하고 조정할 수 있어요. " +
            "앱의 저장 계획과 휴대폰 캘린더 일정은 서로 별도로 관리됩니다.",
    ),
    AVAILABILITY(
        "공부 시간 안내",
        "공부 시간과 캘린더 읽기",
        "요일마다 시간대를 추가하고 시작 시간과 종료 시간을 정해요. 시간대를 누르면 수정할 수 있어요. " +
            "종료 시간이 시작 시간보다 이르면 다음 날 끝나는 시간이에요. " +
            "주마다 다른 시간이나 공부하지 않는 주를 설정할 수 있어요.\n\n" +
            "캘린더는 준비 시작일부터 시험일 전까지 읽어요. 바쁨 또는 미정인 일정만 반영하고, Free·취소·삭제·" +
            "내가 거절한 일정은 공부 시간을 막지 않아요. 반복 일정은 날짜별 발생분으로, 종일 일정은 해당 날짜 전체로 " +
            "반영됩니다. 일정 제목은 읽거나 서버로 보내지 않아요. 읽기 권한 없이도 직접 시간을 설정해 계획을 만들 수 있어요.",
    ),
    ADJUSTMENT(
        "조정 안내",
        "놓친 일정 조정하기",
        "놓친 공부 일정 하나를 선택하면 시험일 전 가능한 시간으로 옮겨요. 공부 주제와 시간 길이는 유지됩니다. " +
            "옮길 시간이 없으면 기존 계획은 그대로 남고 조정할 수 없다는 안내가 표시돼요. " +
            "'시간 직접 옮기기'로 선택한 일정을 원하는 시간에 직접 옮길 수도 있어요. " +
            "자동으로 옮길 시간이 없을 때는 '캘린더에서 빈 시간 찾기'로 캘린더가 비어 있는 시간에 이번 한 번만 옮길 수 있어요.",
    ),
    CALENDAR_EXPORT(
        "내보내기 안내",
        "캘린더에 일정 추가",
        "공부 일정은 선택한 휴대폰 캘린더에 새 일정으로 복사돼요. 앱에서 계획을 조정해도 캘린더 복사본은 " +
            "자동으로 바뀌거나 삭제되지 않아요. 다시 추가하면 중복될 수 있으니 기존 일정을 먼저 확인하세요.",
    ),
}

@Composable
internal fun ContextHelpButton(topic: ContextHelpTopic, vararg additionalTopics: ContextHelpTopic) {
    var showing by rememberSaveable(topic) { mutableStateOf(false) }
    val topics = listOf(topic) + additionalTopics
    TextButton(
        onClick = { showing = true },
        modifier = Modifier.semantics { contentDescription = topics.joinToString(" · ") { it.label } },
    ) { Text("안내") }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(if (additionalTopics.isEmpty()) topic.title else "계획 안내", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (entry in topics) {
                        if (topics.size > 1) Text(entry.title, style = MaterialTheme.typography.titleSmall)
                        Text(entry.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showing = false }) { Text("닫기") }
            },
        )
    }
}
