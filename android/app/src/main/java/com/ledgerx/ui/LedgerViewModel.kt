package com.ledgerx.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerx.data.LedgerDatabase
import com.ledgerx.data.LedgerRepository
import com.ledgerx.data.SupabaseLedger
import com.ledgerx.domain.Movement
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CloudState(
    val configured: Boolean = SupabaseLedger.configured,
    val owner: String? = null,
    val email: String? = null,
    val busy: Boolean = false,
    val message: String? = null
)

class LedgerViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private var repository: LedgerRepository? = null
    private var observation: Job? = null
    private val operations = Mutex()
    private val _movements = MutableStateFlow<List<Movement>>(emptyList())
    val movements = _movements.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _cloud = MutableStateFlow(CloudState())
    val cloud = _cloud.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val auth = SupabaseLedger.client?.auth
                if (auth == null) {
                    switchLedger(null, null)
                } else {
                    auth.sessionStatus.collect { status ->
                        when (status) {
                            SessionStatus.LoadingFromStorage -> Unit
                            is SessionStatus.Authenticated -> operations.withLock {
                                switchLedger(status.session.user?.id, status.session.user?.email)
                            }
                            is SessionStatus.NotAuthenticated -> operations.withLock { switchLedger(null, null) }
                            SessionStatus.NetworkError -> operations.withLock {
                                val stored = auth.sessionManager.loadSession()
                                switchLedger(stored?.user?.id, stored?.user?.email)
                                _cloud.update { it.copy(message = "Offline. Saved records are available; reconnect and tap Sync.") }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // Configuration/session errors must never remove the original offline ledger.
                switchLedger(null, null)
                _cloud.update { it.copy(message = "Cloud setup could not be loaded. Your offline ledger is available.") }
            }
        }
    }

    private fun switchLedger(owner: String?, email: String?) {
        if (repository != null && _cloud.value.owner == owner) {
            _cloud.update { it.copy(email = email) }
            return
        }
        observation?.cancel()
        _ready.value = false
        _movements.value = emptyList()
        _error.value = null
        _cloud.update { it.copy(owner = owner, email = email, message = if (owner == null) null else "Tap Sync to load or upload this account's records.") }
        val next = LedgerRepository(LedgerDatabase.create(appContext, owner))
        repository = next
        observation = viewModelScope.launch {
            next.movements.catch { _error.value = "Could not read your ledger. Please reopen the app." }.collect {
                if (repository === next) { _movements.value = it; _ready.value = true }
            }
        }
    }

    fun clearError() { _error.value = null }

    fun save(movement: Movement, onSaved: () -> Unit) {
        if (_saving.value || _cloud.value.busy || !_ready.value) return
        val target = repository ?: return
        _saving.value = true
        viewModelScope.launch {
            try {
                operations.withLock {
                    check(repository === target) { "The active ledger changed. Please reopen the form." }
                    target.add(movement)
                    if (_cloud.value.owner != null) _cloud.update { it.copy(message = "Saved on this device. Tap Sync to upload changes.") }
                    onSaved()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { _error.value = e.message }
            catch (_: Exception) { _error.value = "Could not save. Your existing records are safe; please try again." }
            finally { _saving.value = false }
        }
    }

    fun authenticate(email: String, password: String, signUp: Boolean) = cloudAction {
        require(email.trim().contains('@') && password.isNotBlank()) { "Enter your email and password." }
        val auth = checkNotNull(SupabaseLedger.client).auth
        if (signUp) {
            auth.signUpWith(Email) { this.email = email.trim(); this.password = password }
            _cloud.update { it.copy(message = "Account requested. Check your email to confirm, then sign in.") }
        } else {
            auth.signInWith(Email) { this.email = email.trim(); this.password = password }
        }
    }

    fun signOut() = cloudAction {
        val auth = checkNotNull(SupabaseLedger.client).auth
        try { auth.signOut() }
        finally { auth.clearSession() } // Also leave the account locally when offline.
    }

    fun sync(importOffline: Boolean = false) = cloudAction {
        val owner = checkNotNull(_cloud.value.owner) { "Sign in before syncing." }
        val auth = checkNotNull(SupabaseLedger.client).auth
        if (auth.currentUserOrNull() == null) {
            val stored = checkNotNull(auth.sessionManager.loadSession()) { "Sign in again before syncing." }
            check(stored.user?.id == owner)
            auth.importSession(auth.refreshSession(stored.refreshToken))
        }
        val offline = if (importOffline) LedgerRepository(LedgerDatabase.create(appContext)).records() else emptyList()
        val count = checkNotNull(repository).sync { local ->
            val ids = local.map { it.id }.toSet()
            SupabaseLedger.exchange(owner, local + offline.filterNot { it.id in ids })
        }
        _cloud.update { it.copy(message = "Synced successfully · $count records. Other devices update when you tap Sync there.") }
    }

    private fun cloudAction(action: suspend () -> Unit) {
        if (_cloud.value.busy || _saving.value) return
        _cloud.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { operations.withLock { action() } }
            catch (e: CancellationException) { throw e }
            catch (e: RestException) {
                // SDK exception messages can contain headers. Never display or log them.
                val message = when {
                    e.statusCode == 404 -> "Cloud tables or sync function are missing. Complete the database setup in docs/supabase-setup.md."
                    e.statusCode == 401 || e.statusCode == 403 -> "Access denied. Check your credentials, confirm your email, and sign in again."
                    e.statusCode == 429 -> "Too many requests. Wait a moment before trying again."
                    e.error.contains("Insufficient", true) || e.error.contains("Repayment", true) -> "Sync conflicts with the cloud balance. Check other devices for spending or repayments; local records were kept."
                    else -> "Cloud request rejected. Check your credentials, email confirmation, and database setup. Local records were kept."
                }
                _cloud.update { it.copy(message = message) }
            }
            catch (e: IllegalArgumentException) { _cloud.update { it.copy(message = e.message) } }
            catch (_: Exception) { _cloud.update { it.copy(message = "Could not connect or sync. Check your connection and sign in again if needed. Local records were kept.") } }
            finally { _cloud.update { it.copy(busy = false) } }
        }
    }
}
