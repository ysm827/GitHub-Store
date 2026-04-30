package zed.rainxch.tweaks.presentation.mirror.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_body
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_dont_ask_again
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_maybe_later
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_pick_one
import zed.rainxch.githubstore.core.presentation.res.mirror_auto_suggest_title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSuggestMirrorSheet(
    onDismiss: () -> Unit,
    onPickOne: () -> Unit,
    onMaybeLater: () -> Unit,
    onDontAskAgain: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.mirror_auto_suggest_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.mirror_auto_suggest_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onPickOne,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.mirror_auto_suggest_pick_one))
            }
            OutlinedButton(
                onClick = onMaybeLater,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.mirror_auto_suggest_maybe_later))
            }
            TextButton(
                onClick = onDontAskAgain,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.mirror_auto_suggest_dont_ask_again))
            }
        }
    }
}
