package com.hisabkhata.data.cloud

import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The signed-in account, or null when the app is locked behind sign-in. */
data class CloudAccount(val userId: String, val email: String?)

enum class SignInFailure {
    INVALID_CREDENTIALS,
    /** The owner disabled this account in Supabase; the server rejected the sign-in. */
    ACCOUNT_DISABLED,
    NETWORK,
    UNEXPECTED
}

sealed interface SignInOutcome {
    data object Success : SignInOutcome
    data class Failed(val reason: SignInFailure) : SignInOutcome
}

/**
 * Owns the current session for this process only.
 *
 * Accounts are created by the owner in the Supabase dashboard with sign-up disabled,
 * so there is no registration path here at all — only email + password sign-in.
 */
@Singleton
open class CloudSession @Inject constructor(private val auth: Auth?) {
    /** Test-only: a signed-out session with no network client. */
    protected constructor() : this(null)
    private val mutableAccount = MutableStateFlow<CloudAccount?>(null)
    val account: StateFlow<CloudAccount?> = mutableAccount.asStateFlow()

    fun currentUserId(): String? = mutableAccount.value?.userId

    suspend fun signIn(email: String, password: String): SignInOutcome {
        val auth = auth ?: return SignInOutcome.Failed(SignInFailure.UNEXPECTED)
        return try {
            auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
            val user = auth.currentSessionOrNull()?.user
            if (user == null) {
                SignInOutcome.Failed(SignInFailure.UNEXPECTED)
            } else {
                mutableAccount.value = CloudAccount(user.id, user.email)
                SignInOutcome.Success
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            SignInOutcome.Failed(classify(error))
        }
    }

    suspend fun signOut() {
        // Local session state is cleared even if the network call fails, so the
        // app never keeps a stale owner visible to the next person using the phone.
        runCatching { auth?.signOut() }
        mutableAccount.value = null
    }

    private fun classify(error: Exception): SignInFailure {
        val text = (error.message ?: "") + " " + (error.cause?.message ?: "")
        return when {
            text.contains("disabled", ignoreCase = true) -> SignInFailure.ACCOUNT_DISABLED
            text.contains("invalid", ignoreCase = true) ||
                text.contains("credentials", ignoreCase = true) -> SignInFailure.INVALID_CREDENTIALS
            text.contains("unable to resolve", ignoreCase = true) ||
                text.contains("host", ignoreCase = true) ||
                text.contains("timeout", ignoreCase = true) ||
                text.contains("connect", ignoreCase = true) -> SignInFailure.NETWORK
            else -> SignInFailure.UNEXPECTED
        }
    }
}
