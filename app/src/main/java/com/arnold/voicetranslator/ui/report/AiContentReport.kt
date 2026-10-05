package com.arnold.voicetranslator.ui.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.arnold.voicetranslator.R
import com.arnold.voicetranslator.ui.localization.UiLanguage
import com.arnold.voicetranslator.ui.localization.localized

/** Opens the mail app with a prefilled AI-output report, or toasts the address. */
fun sendAiContentReport(
    context: Context,
    reason: String,
    comment: String,
    aiText: String,
    thanksMessage: String,
) {
    val version = appVersionName(context)
    val mailto = Uri.parse("mailto:$AI_REPORT_EMAIL").buildUpon()
        .appendQueryParameter("subject", AI_REPORT_SUBJECT)
        .appendQueryParameter("body", aiReportBody(reason, comment, version, aiText))
        .build()
    val intent = Intent(Intent.ACTION_SENDTO, mailto)
    try {
        context.startActivity(intent)
        Toast.makeText(context, thanksMessage, Toast.LENGTH_SHORT).show()
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, AI_REPORT_EMAIL, Toast.LENGTH_LONG).show()
    }
}

private fun appVersionName(context: Context): String {
    return runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName
    }.getOrNull().orEmpty().ifBlank { "1.0" }
}

@Composable
fun AiReportFlag(
    uiLanguage: UiLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, modifier = modifier.size(32.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_report_flag),
            contentDescription = localized(uiLanguage, R.string.ai_report),
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Reason picker for one piece of AI output. [aiText] is the generated text
 * only; the email body is built from that plus the reason and comment.
 */
@Composable
fun AiReportDialog(
    aiText: String,
    uiLanguage: UiLanguage,
    onDismiss: () -> Unit,
    onSend: (reason: String, comment: String) -> Unit,
) {
    val reasons = listOf(
        localized(uiLanguage, R.string.ai_report_reason_offensive),
        localized(uiLanguage, R.string.ai_report_reason_incorrect),
        localized(uiLanguage, R.string.ai_report_reason_other),
    )
    var selected by rememberSaveable { mutableStateOf(reasons.first()) }
    var comment by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        text = {
            AiReportDialogBody(
                reasons = reasons,
                selected = selected,
                onSelect = { selected = it },
                comment = comment,
                onComment = { comment = it },
                uiLanguage = uiLanguage,
                onDismiss = onDismiss,
                onSend = { onSend(selected, comment) },
            )
        },
    )
}

@Composable
internal fun AiReportDialogBody(
    reasons: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    comment: String,
    onComment: (String) -> Unit,
    uiLanguage: UiLanguage,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = localized(uiLanguage, R.string.ai_report_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        reasons.forEach { reason ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = reason == selected,
                        onClick = { onSelect(reason) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = reason == selected, onClick = { onSelect(reason) })
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = comment,
            onValueChange = onComment,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(localized(uiLanguage, R.string.ai_report_comment_hint)) },
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = onDismiss) {
                Text(localized(uiLanguage, R.string.ai_report_cancel))
            }
            TextButton(onClick = onSend) {
                Text(localized(uiLanguage, R.string.ai_report_send))
            }
        }
    }
}
