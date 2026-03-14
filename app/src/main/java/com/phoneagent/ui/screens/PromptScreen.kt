package com.phoneagent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.unit.sp
import com.phoneagent.engine.AgentEngine
import com.phoneagent.prompt.DefaultPrompts
import com.phoneagent.prompt.PromptTemplate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptScreen(onNavigateToChat: (() -> Unit)? = null) {
    val context = LocalContext.current
    val engine = remember { AgentEngine.getInstance(context) }
    val scope = rememberCoroutineScope()

    var selectedCategory by remember { mutableStateOf("全部") }
    var showCustomDialog by remember { mutableStateOf(false) }

    val allCategories = remember { listOf("全部") + DefaultPrompts.categories }
    val filteredTemplates = remember(selectedCategory) {
        if (selectedCategory == "全部") DefaultPrompts.templates
        else DefaultPrompts.getByCategory(selectedCategory)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        TopAppBar(
            title = {
                Text(
                    "Prompt 模板库",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            actions = {
                FilledTonalIconButton(onClick = { showCustomDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "自定义模板")
                }
            }
        )

        // Category tabs
        LazyRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(allCategories) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(category) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Template list
        LazyColumn(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(filteredTemplates) { template ->
                PromptCard(
                    template = template,
                    onUse = {
                        scope.launch {
                            engine.applyPromptTemplate(template)
                            onNavigateToChat?.invoke()
                        }
                    }
                )
            }
        }
    }

    if (showCustomDialog) {
        CustomPromptDialog(
            onDismiss = { showCustomDialog = false },
            onConfirm = { title, prompt ->
                scope.launch {
                    engine.newConversation(title, prompt)
                    onNavigateToChat?.invoke()
                }
                showCustomDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptCard(
    template: PromptTemplate,
    onUse: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onUse,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(template.icon, fontSize = 22.sp)
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    template.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                ) {
                    Text(
                        template.category,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onUse,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = "使用",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CustomPromptDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, systemPrompt: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.EditNote, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        title = { Text("自定义 Prompt", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("对话标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("系统提示词") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.ifBlank { "自定义对话" }, systemPrompt) },
                enabled = systemPrompt.isNotBlank()
            ) { Text("开始对话") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
