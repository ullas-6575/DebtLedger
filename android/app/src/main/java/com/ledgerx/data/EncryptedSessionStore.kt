package com.ledgerx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.gotrue.SessionManager
import io.github.jan.supabase.gotrue.user.UserSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores the Supabase session encrypted by the Android Keystore.
 *
 * Uses [EncryptedSharedPreferences] backed by an AES256-GCM master key.
 * The key material lives in the hardware keystore and never leaves the
 * device. This replaces the default plaintext session storage.
 *
 * A cached copy of the user ID and email is kept separately so that
 * offline session validation can show identity without decrypting the
 * full token. These are informational only; the full session is always
 * required for API calls.
 */
class EncryptedSessionStore(context: Context) : SessionManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "ledgerx_secure_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override suspend fun saveSession(session: UserSession) {
        val encoded = json.encodeToString(session)
        prefs.edit()
            .putString(KEY_SESSION, encoded)
            .putString(KEY_USER_ID, session.user?.id)
            .putString(KEY_EMAIL, session.user?.email)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    override suspend fun loadSession(): UserSession? {
        val encoded = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            json.decodeFromString<UserSession>(encoded)
        } catch (_: Exception) {
            // Corrupt or schema-incompatible session; discard it.
            deleteSession()
            null
        }
    }

    override suspend fun deleteSession() {
        prefs.edit()
            .remove(KEY_SESSION)
            .remove(KEY_USER_ID)
            .remove(KEY_EMAIL)
            .remove(KEY_SAVED_AT)
            .apply()
    }

    /** The user ID from the last saved session, available without full decoding. */
    val cachedUserId: String? get() = prefs.getString(KEY_USER_ID, null)

    /** The email from the last saved session. */
    val cachedEmail: String? get() = prefs.getString(KEY_EMAIL, null)

    /** Whether a session was previously stored (does not guarantee validity). */
    val hasSession: Boolean get() = prefs.contains(KEY_SESSION)

    /** Epoch millis when the session was last saved. */
    val savedAt: Long get() = prefs.getLong(KEY_SAVED_AT, 0L)

    companion object {
        private const val KEY_SESSION = "session"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
