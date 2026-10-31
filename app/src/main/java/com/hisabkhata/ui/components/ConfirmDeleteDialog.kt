package com.hisabkhata.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import android.view.ViewTreeObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.hisabkhata.R

/** The destructive action is never a default; back/outside behave like Cancel until work starts. */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    isBusy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onCancel() },
        title = { Text(title) },
        text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isBusy, modifier = Modifier.focusRequester(cancelFocus).focusable(enabled = !isBusy)) {
                Text(stringResource(R.string.cancel))
            }
            // A dialog has its own window. Request focus only after that window gains focus,
            // and post to its view so the Compose focus target is already attached.
            val view = LocalView.current
            DisposableEffect(view, cancelFocus) {
                var active = true
                val request = Runnable {
                    if (active && view.isAttachedToWindow && view.hasWindowFocus()) {
                        runCatching { cancelFocus.requestFocus() }
                    }
                }
                val observer = view.viewTreeObserver
                val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus) view.post(request)
                }
                observer.addOnWindowFocusChangeListener(listener)
                view.post(request)
                onDispose {
                    active = false
                    view.removeCallbacks(request)
                    if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isBusy) {
                Text(stringResource(R.string.delete), color = if (!isBusy) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
            }
        }
    )
}
