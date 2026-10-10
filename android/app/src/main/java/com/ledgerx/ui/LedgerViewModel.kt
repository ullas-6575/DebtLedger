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

// ── Authentication state ────────────────────────────────────────────

/** Describes where the user is in the auth flow. */
enum class AuthScreen {
    /** Checking for a cached session. */
    LOADING,
    /** No session — must sign in or sign up. */
    LOGIN,
    /** Authenticated but no profile row yet. */
    PROFILE_SETUP,
    /** Fully authenticated with a profile — show the main app. */
    MAIN
}

data class AuthState(
    val screen: AuthScreen = AuthScreen.LOADING,
    val userId: String? = null,
    val email: String? = null,
    val busy: Boolean = false,
    val message: String? = null
)

// ── Cloud / sync state (for the signed-in user) ─────────────────────

data class CloudState(
    val configured: Boolean = SupabaseLedger.configured,
    val owner: String? = null,
    val email: String? = null,
    val busy: Boolean = false,
    val message: String? = null
)

// ── ViewModel ───────────────────────────────────────────────────────

class LedgerViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    val ledger = LedgerController(appContext, viewModelScope)
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

    // Auth state for the mandatory login flow
    private val _auth = MutableStateFlow(AuthState())
    val auth = _auth.asStateFlow()

    init {
        // Initialize Supabase with encrypted session storage.
        SupabaseLedger.initialize(appContext)
        observeSession()
    }

    // ── Session observation ─────────────────────────────────────────

    private fun observeSession() {
        viewModelScope.launch {
            try {
                val supaAuth = SupabaseLedger.client?.auth
                if (supaAuth == null) {
                    // Supabase not configured — show login but it won't work.
                    _auth.value = AuthState(
                        screen = AuthScreen.LOGIN,
                        message = "Cloud is not configured. Add your Supabase URL and key, then rebuild."
                    )
                    return@launch
                }
                supaAuth.sessionStatus.collect { status ->
                    when (status) {
                        SessionStatus.LoadingFromStorage -> {
                            _auth.update { it.copy(screen = AuthScreen.LOADING) }
                        }
                        is SessionStatus.Authenticated -> operations.withLock {
                            val userId = status.session.user?.id
                            val email = status.session.user?.email
                            if (userId != null) {
                                handleAuthenticated(userId, email)
                            } else {
                                _auth.value = AuthState(screen = AuthScreen.LOGIN)
                            }
                        }
                        is SessionStatus.NotAuthenticated -> operations.withLock {
                            clearLedger()
                            _auth.value = AuthState(screen = AuthScreen.LOGIN)
                        }
                        SessionStatus.NetworkError -> operations.withLock {
                            // Offline: check if we have a cached session
                            val store = SupabaseLedger.sessionStore
                            if (store != null && store.hasSession) {
                                val cachedId = store.cachedUserId
                                val cachedEmail = store.cachedEmail
                                if (cachedId != null) {
                                    // User previously logged in — allow offline access.
                                    switchLedger(cachedId, cachedEmail)
                                    _auth.value = AuthState(
                                        screen = AuthScreen.MAIN,
                                        userId = cachedId,
                                        email = cachedEmail,
                                        message = null
                                    )
                                    _cloud.update { it.copy(
                                        owner = cachedId,
                                        email = cachedEmail,
                                        message = "Offline. Saved records are available; changes will sync when connected."
                                    ) }
                                } else {
                                    _auth.value = AuthState(
                                        screen = AuthScreen.LOGIN,
                                        message = "No internet connection. Sign in requires internet for the first time."
                                    )
                                }
                            } else {
                                _auth.value = AuthState(
                                    screen = AuthScreen.LOGIN,
                                    message = "No internet connection. Sign in requires internet for the first time."
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _auth.value = AuthState(
                    screen = AuthScreen.LOGIN,
                    message = "Could not load your session. Please sign in again."
                )
            }
        }
    }

    /** After Supabase confirms auth, check if a profile exists. */
    private suspend fun handleAuthenticated(userId: String, email: String?) {
        switchLedger(userId, email)
        _cloud.update { it.copy(
            owner = userId,
            email = email,
            message = "Records sync automatically when connected."
        ) }

        // Check for profile
        try {
            val hasProfile = SupabaseLedger.hasProfile(userId)
            if (hasProfile) {
                _auth.value = AuthState(
                    screen = AuthScreen.MAIN,
                    userId = userId,
                    email = email
                )
            } else {
                _auth.value = AuthState(
                    screen = AuthScreen.PROFILE_SETUP,
                    userId = userId,
                    email = email
                )
            }
        } catch (_: Exception) {
            // Network error checking profile — if we have a cached session,
            // assume profile exists and go to main. On next online check, we'll verify.
            _auth.value = AuthState(
                screen = AuthScreen.MAIN,
                userId = userId,
                email = email
            )
        }
    }

    // ── Ledger switching ────────────────────────────────────────────

    private fun switchLedger(owner: String, email: String?) {
        ledger.open(owner)
        if (repository != null && _cloud.value.owner == owner) {
            _cloud.update { it.copy(email = email) }
            return
        }
        observation?.cancel()
        _ready.value = false
        _movements.value = emptyList()
        _error.value = null
        val next = LedgerRepository(LedgerDatabase.create(appContext, owner))
        repository = next
        observation = viewModelScope.launch {
            next.movements.catch {
                _error.value = "Could not read your ledger. Please reopen the app."
            }.collect {
                if (repository === next) { _movements.value = it; _ready.value = true }
            }
        }
    }

    private fun clearLedger() {
        ledger.close()
        observation?.cancel()
        repository = null
        _ready.value = false
        _movements.value = emptyList()
        _error.value = null
        _cloud.value = CloudState()
    }

    // ── Auth actions ────────────────────────────────────────────────

    fun signIn(email: String, password: String) = authAction {
        val supaAuth = checkNotNull(SupabaseLedger.client).auth
        supaAuth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        // Session observation will handle the rest.
    }

    fun signUp(email: String, password: String) = authAction {
        val supaAuth = checkNotNull(SupabaseLedger.client).auth
        supaAuth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        _auth.update { it.copy(
            message = "Account created. Check your email to confirm, then sign in."
        ) }
    }

    fun createProfile(username: String, displayName: String) = authAction {
        val userId = checkNotNull(_auth.value.userId) { "Not authenticated." }
        SupabaseLedger.createProfile(userId, username, displayName)
        _auth.update { it.copy(screen = AuthScreen.MAIN, message = null) }
    }

    fun signOut() = authAction {
        val supaAuth = checkNotNull(SupabaseLedger.client).auth
        try { supaAuth.signOut() }
        finally { supaAuth.clearSession() }
        // Session observation will reset to LOGIN.
    }

    private fun authAction(action: suspend () -> Unit) {
        if (_auth.value.busy) return
        _auth.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { operations.withLock { action() } }
            catch (e: CancellationException) { throw e }
            catch (e: RestException) {
                val message = when {
                    e.statusCode == 401 || e.statusCode == 403 ->
                        "Invalid credentials. Check your email and password."
                    e.statusCode == 422 ->
                        "Invalid email or password format."
                    e.statusCode == 429 ->
                        "Too many attempts. Please wait a moment."
                    e.error.contains("already registered", true) ->
                        "This email is already registered. Try signing in."
                    e.error.contains("username", true) ->
                        "This username is already taken. Choose a different one."
                    e.error.contains("not confirmed", true) || e.error.contains("confirm", true) ->
                        "Please confirm your email address first."
                    else ->
                        "Authentication failed. Check your credentials and try again."
                }
                _auth.update { it.copy(message = message) }
            }
            catch (e: IllegalArgumentException) {
                _auth.update { it.copy(message = e.message) }
            }
            catch (_: Exception) {
                _auth.update { it.copy(
                    message = "Could not connect. Check your internet connection and try again."
                ) }
            }
            finally { _auth.update { it.copy(busy = false) } }
        }
    }

    // ── Ledger actions (same as before) ─────────────────────────────

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
                    _cloud.update { it.copy(message = "Saved on this device. Tap Sync to upload changes.") }
                    onSaved()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { _error.value = e.message }
            catch (_: Exception) { _error.value = "Could not save. Your existing records are safe; please try again." }
            finally { _saving.value = false }
        }
    }

    fun sync(importOffline: Boolean = false) = cloudAction {
        val owner = checkNotNull(_cloud.value.owner) { "Sign in before syncing." }
        val supaAuth = checkNotNull(SupabaseLedger.client).auth
        if (supaAuth.currentUserOrNull() == null) {
            val stored = checkNotNull(supaAuth.sessionManager.loadSession()) { "Sign in again before syncing." }
            check(stored.user?.id == owner)
            supaAuth.importSession(supaAuth.refreshSession(stored.refreshToken))
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
