package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.PlanStorage
import kotlinx.coroutines.launch

@Composable
internal fun CalendarPlanDrawer(
    plans: List<SessionPlan>,
    hidden: Set<String>,
    storage: PlanStorage,
    onOpen: (Int) -> Unit,
    onCreate: () -> Unit,
    onManage: () -> Unit,
    onToggle: (Int, Boolean) -> Unit,
    content: @Composable (() -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun close(next: () -> Unit = {}) { scope.launch { drawer.close(); next() } }
    BackHandler(drawer.isOpen) { close() }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = Color.White) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("계획 목록", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { close() }) { Text("닫기") }
            }
            LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("달력에서 보고 싶은 학습 계획을 선택하세요.")
                    TextButton(enabled = storage == PlanStorage.Ready, onClick = { close(onCreate) }) { Text("＋ 학습 계획 생성하기") }
                }
                if (plans.isEmpty()) item {
                    Text(when (storage) {
                        PlanStorage.Loading -> LOADING_SAVED_PLANS
                        PlanStorage.LoadFailure -> "계획을 불러오지 못했어요."
                        else -> "아직 만든 계획이 없어요."
                    })
                }
                items(plans, key = { it.ordinal }) { plan ->
                    val visible = plan.ordinal.toString() !in hidden
                    Surface(shape = RoundedCornerShape(18.dp), color = if (visible) Color.White else Color(0xFFF5F5F5),
                        border = BorderStroke(1.dp, Color(0xFFE8E9EB))) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { close { onOpen(plan.ordinal) } }.padding(end = 8.dp)) {
                                Text("계획 ${plan.ordinal} · $CERTIFICATION_NAME ›", style = MaterialTheme.typography.titleSmall)
                                Text("시험일 ${wireDate(plan.original.examDate)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = visible, onCheckedChange = { onToggle(plan.ordinal, it) },
                                modifier = Modifier.semantics { contentDescription = "계획 ${plan.ordinal} 달력에 표시" })
                        }
                    }
                }
                item { TextButton(onClick = { close(onManage) }) { Text("저장된 계획 관리") } }
            }
        }
    }) { content { scope.launch { drawer.open() } } }
}
