package com.phoneagent.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.engine.AgentEngine
import com.phoneagent.scheduler.RepeatMode
import com.phoneagent.scheduler.ScheduledTask
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen() {
    val context = LocalContext.current
    val engine = remember { AgentEngine.getInstance(context) }
    val tasks by engine.scheduler.allTasks.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with gradient
        TopAppBar(
            title = {
                Column {
                    Text(
                        "定时任务",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${tasks.size} 个任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            actions = {
                FilledTonalIconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新建任务")
                }
            }
        )

        if (tasks.isEmpty()) {
            // Beautiful empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "暂无定时任务",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "创建定时任务，让 AI 自动为你执行",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    FilledTonalButton(
                        onClick = { showCreateDialog = true },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("创建任务")
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks) { task ->
                    TaskCard(
                        task = task,
                        onToggle = { scope.launch { engine.scheduler.setTaskEnabled(task.id, !task.enabled) } },
                        onDelete = { scope.launch { engine.scheduler.deleteTask(task.id) } }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        CreateTaskDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, command, repeat, time, interval ->
                scope.launch {
                    engine.scheduler.createTask(
                        name = name,
                        command = command,
                        repeatMode = repeat,
                        scheduledTime = time,
                        intervalMinutes = interval,
                    )
                }
                showCreateDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskCard(
    task: ScheduledTask,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.enabled) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (task.enabled) MaterialTheme.colorScheme.primaryContainer
                           else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Task,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (task.enabled) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        task.command,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = task.enabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        when (task.repeatMode) {
                            RepeatMode.ONCE -> "一次"
                            RepeatMode.DAILY -> "每天"
                            RepeatMode.WEEKLY -> "每周"
                            RepeatMode.WEEKDAYS -> "工作日"
                            RepeatMode.INTERVAL -> "间隔"
                            RepeatMode.CRON -> "Cron"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (task.nextRunTime > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Outlined.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        dateFormat.format(Date(task.nextRunTime)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, RepeatMode, Long, Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var repeatMode by remember { mutableStateOf(RepeatMode.ONCE) }
    var hour by remember { mutableStateOf("09") }
    var minute by remember { mutableStateOf("00") }
    var intervalMin by remember { mutableStateOf("30") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AddTask, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        title = { Text("创建定时任务", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("任务名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = command, onValueChange = { command = it },
                    label = { Text("执行命令（自然语言）") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    shape = RoundedCornerShape(12.dp)
                )

                Text("重复模式", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RepeatMode.entries.forEach { mode ->
                        FilterChip(
                            selected = repeatMode == mode,
                            onClick = { repeatMode = mode },
                            label = {
                                Text(when(mode) {
                                    RepeatMode.ONCE -> "一次"
                                    RepeatMode.DAILY -> "每天"
                                    RepeatMode.WEEKLY -> "每周"
                                    RepeatMode.WEEKDAYS -> "工作日"
                                    RepeatMode.INTERVAL -> "间隔"
                                    RepeatMode.CRON -> "Cron"
                                }, style = MaterialTheme.typography.labelSmall)
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                if (repeatMode != RepeatMode.INTERVAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hour, onValueChange = { hour = it },
                            label = { Text("时") }, modifier = Modifier.weight(1f),
                            singleLine = true, shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = minute, onValueChange = { minute = it },
                            label = { Text("分") }, modifier = Modifier.weight(1f),
                            singleLine = true, shape = RoundedCornerShape(12.dp)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = intervalMin, onValueChange = { intervalMin = it },
                        label = { Text("间隔（分钟）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, hour.toIntOrNull() ?: 9)
                    cal.set(Calendar.MINUTE, minute.toIntOrNull() ?: 0)
                    cal.set(Calendar.SECOND, 0)
                    if (cal.timeInMillis <= System.currentTimeMillis()) {
                        cal.add(Calendar.DAY_OF_MONTH, 1)
                    }
                    onCreate(name, command, repeatMode, cal.timeInMillis, (intervalMin.toLongOrNull() ?: 30).coerceAtLeast(15))
                },
                enabled = name.isNotBlank() && command.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
