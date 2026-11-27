package com.hisabkhata.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hisabkhata.R
import com.hisabkhata.data.cloud.SignInFailure

/**
 * Sign-in gate for v1.3.0.
 *
 * Accounts are created by the owner in the Supabase dashboard and sign-up is
 * disabled server-side, so this screen intentionally has no "create account" or
 * "forgot password" link that could imply self-service.
 */
@Composable
fun SignInScreen(viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sign_in_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::setEmail,
                label = { Text(stringResource(R.string.sign_in_email)) },
                singleLine = true,
                enabled = !state.working,
                isError = state.failure == SignInFailure.INVALID_CREDENTIALS,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    autoCorrect = false
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text(stringResource(R.string.sign_in_password)) },
                singleLine = true,
                enabled = !state.working,
                isError = state.failure == SignInFailure.INVALID_CREDENTIALS,
                visualTransformation = if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); viewModel.submit() }),
                trailingIcon = {
                    TextButton(onClick = viewModel::togglePassword) {
                        Text(stringResource(if (state.showPassword) R.string.sign_in_hide else R.string.sign_in_show))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            state.failure?.let { failure ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        when (failure) {
                            SignInFailure.INVALID_CREDENTIALS -> R.string.sign_in_error_credentials
                            SignInFailure.ACCOUNT_DISABLED -> R.string.sign_in_error_disabled
                            SignInFailure.NETWORK -> R.string.sign_in_error_network
                            SignInFailure.UNEXPECTED -> R.string.sign_in_error_unexpected
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = state.rememberMe,
                    onCheckedChange = viewModel::setRememberMe
                )
                Text(
                    text = stringResource(R.string.auth_remember_me),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { focus.clearFocus(); viewModel.submit() },
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.working) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.sign_in_action))
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.sign_in_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
