package com.neiro.aftertone.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neiro.aftertone.BatchImportItem
import com.neiro.aftertone.BatchImportState
import com.neiro.aftertone.BatchImportStatus
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
internal fun BatchImportDialog(
    state: BatchImportState,
    isImporting: Boolean,
    onRetryItem: (String) -> Unit,
    onSkipItem: (String) -> Unit,
    onRetryFailed: () -> Unit,
    onSkipFailed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canRetry = state.failedCount > 0 || state.pendingItems.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!isImporting && state.isComplete) onDismiss() },
        title = { Text(if (isImporting) "Импорт файлов" else "Результаты импорта") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val summary = when {
                    isImporting -> "Копируем выбранные файлы в медиатеку."
                    state.failedCount > 0 -> "Импортировано: ${state.importedCount}. Не удалось: ${state.failedCount}. Можно повторить или пропустить только неудачные файлы."
                    else -> "Импортировано: ${state.importedCount}."
                }
                Text(summary, color = AftertoneMuted, fontSize = 13.sp)
                state.items.forEach { item ->
                    BatchImportItemStatus(
                        item = item,
                        isImporting = isImporting,
                        onRetry = { onRetryItem(item.id) },
                        onSkip = { onSkipItem(item.id) },
                    )
                }
            }
        },
        confirmButton = {
            when {
                canRetry -> TextButton(onClick = onRetryFailed, enabled = !isImporting) {
                    Text(if (state.failedCount > 0) "Повторить" else "Продолжить")
                }
                state.isComplete -> TextButton(onClick = onDismiss, enabled = !isImporting) { Text("Готово") }
            }
        },
        dismissButton = {
            if (state.failedCount > 0) {
                TextButton(onClick = onSkipFailed, enabled = !isImporting) { Text("Пропустить") }
            }
        },
    )
}

@Composable
private fun BatchImportItemStatus(
    item: BatchImportItem,
    isImporting: Boolean,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    val statusText = when (item.status) {
        BatchImportStatus.PENDING -> "Ожидает импорта"
        BatchImportStatus.IMPORTING -> "Импортируется"
        BatchImportStatus.IMPORTED -> "Импортировано"
        BatchImportStatus.FAILED -> "Не удалось: ${item.failureMessage.orEmpty()}"
        BatchImportStatus.SKIPPED -> "Пропущено"
    }
    val statusColor = when (item.status) {
        BatchImportStatus.FAILED -> MaterialTheme.colorScheme.error
        BatchImportStatus.IMPORTING -> MaterialTheme.colorScheme.primary
        BatchImportStatus.IMPORTED -> AftertoneText
        BatchImportStatus.PENDING, BatchImportStatus.SKIPPED -> AftertoneMuted
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = item.displayName.ifBlank { "Аудиофайл" },
            color = AftertoneText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = statusText, color = statusColor, fontSize = 12.sp)
        if (item.status == BatchImportStatus.FAILED) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry, enabled = !isImporting) { Text("Повторить") }
                TextButton(onClick = onSkip, enabled = !isImporting) { Text("Пропустить") }
            }
        }
    }
}
