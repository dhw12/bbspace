package com.naaammme.bbspace.feature.bbspace.relation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RelationCheckPane(
    modifier: Modifier = Modifier,
    vm: RelationCheckViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "关系查询",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "支持查询任意两个用户之间的关系",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // up UID 输入框（加载时禁用输入）
                OutlinedTextField(
                    value = state.upInput,
                    onValueChange = vm::updateUpInput,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("up UID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                
                // user UID 输入框（修复：原代码漏掉了加载时禁用，导致加载中仍可修改输入导致数据不一致）
                OutlinedTextField(
                    value = state.userInput,
                    onValueChange = vm::updateUserInput,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("user UID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                
                // 查询按钮（加载时禁用防止重复提交）
                Button(
                    onClick = vm::query,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading
                ) {
                    Text(if (state.isLoading) "查询中..." else "查询")
                }
            }
        }

        // 统一展示顶部级全局错误（包含参数错误、网络框架异常或防抖频繁点击提示）
        state.error?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // 拉黑结果卡片
        state.blockResult?.let { result ->
            RelationResultCard(
                result = result,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 关注结果卡片
        state.followResult?.let { result ->
            RelationResultCard(
                result = result,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun RelationResultCard(
    result: RelationQueryResult,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            result.text?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            result.hint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            result.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}