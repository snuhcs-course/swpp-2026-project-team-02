package com.swpp.licenseplanner.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.MoveSave
import com.swpp.licenseplanner.ui.SessionMove
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.theme.Ink
import java.time.LocalDate

/**
 * `2026년 11월 18일(수) 19:00–19:30`. An end on another day names its full date too, so moves
 * across weeks, months, years and midnight read unambiguously. Rows that do not parse keep
 * their wire text.
 */
internal fun moveIntervalText(row: ScheduleRow): String {
    val start = WireDateTime.parseOrNull(row.startDate)
    val end = WireDateTime.parseOrNull(row.endDate)
    if (start == null || end == null) return "${row.startDate} – ${row.endDate}"
    val endDay = if (end.toLocalDate() == start.toLocalDate()) "" else "${fullDay(end.toLocalDate())} "
    return "${fullDay(start.toLocalDate())} ${WireDateTime.formatTime(start.toLocalTime())}–" +
        "$endDay${WireDateTime.formatTime(end.toLocalTime())}"
}

private fun fullDay(date: LocalDate) = "${date.year}년 ${monthDay(date)}(${dayLabel(date)})"

/** Whether the changed plan is on this device yet, for the shown move only. */
internal fun moveSaveText(save: MoveSave): String = when (save) {
    MoveSave.Saving -> "기기에 저장하는 중이에요"
    MoveSave.Saved -> "이 기기에 저장했어요"
    MoveSave.Unsaved -> "아직 이 기기에 저장하지 못했어요"
}

/**
 * The accepted move: the returned topic, the sent and returned intervals and whether the changed
 * plan is saved on this device. A failed save is explained and retried by the storage alert.
 */
@Composable
internal fun MoveResult(move: SessionMove, save: MoveSave) {
    MutedBox(Modifier.testTag("move-result")) {
        Text("계획이 조정됐어요", style = MaterialTheme.typography.titleSmall, color = Ink)
        if (move.after.topic.isNotBlank()) Text(move.after.topic, style = MaterialTheme.typography.bodyMedium, color = Ink)
        Text("원래 시간: ${moveIntervalText(move.before)}", style = MaterialTheme.typography.bodyMedium, color = Ink)
        Text("바뀐 시간: ${moveIntervalText(move.after)}", style = MaterialTheme.typography.bodyMedium, color = Ink)
        Text(moveSaveText(save), style = MaterialTheme.typography.bodyMedium, color = Ink)
        Note("옮긴 일정이 있는 주를 보여 드려요. 다른 일정도 이어서 조정할 수 있어요.")
    }
}
