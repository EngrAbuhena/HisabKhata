package com.hisabkhata.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.cloud.CloudSession
import com.hisabkhata.data.cloud.SignInFailure
import com.hisabkhata.data.cloud.SignInOutcome
import com.hisabkhata.data.sync.SyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val working: Boolean = false,
    val failure: SignInFailure? = null,
    val showPassword: Boolean = false
) {
    val canSubmit: Boolean get() = !working && email.contains('@') && password.isNotEmpty()
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val session: CloudSession,
    private val sync: SyncCoordinator
) : ViewModel() {
    private val mutable = MutableStateFlow(SignInUiState())
    val state = mutable.asStateFlow()
    val account = session.account

    fun setEmail(value: String) = mutable.update { it.copy(email = value, failure = null) }
    fun setPassword(value: String) = mutable.update { it.copy(password = value, failure = null) }
    fun togglePassword() = mutable.update { it.copy(showPassword = !it.showPassword) }

    fun submit() {
        val current = mutable.value
        if (!current.canSubmit) return
        // Passwords are never trimmed: only the e-mail may carry stray spaces.
        val email = current.email.trim()
        val password = current.password
        mutable.update { it.copy(working = true, failure = null, password = "") }
        viewModelScope.launch {
            when (val outcome = session.signIn(email, password)) {
                is SignInOutcome.Success -> {
                    mutable.update { it.copy(working = false) }
                    // First sign-in pushes whatever was saved locally before sign-in.
                    sync.pushNow()
                }
                is SignInOutcome.Failed -> mutable.update { it.copy(working = false, failure = outcome.reason) }
            }
        }
    }

    fun clearFailure() = mutable.update { it.copy(failure = null) }
}
