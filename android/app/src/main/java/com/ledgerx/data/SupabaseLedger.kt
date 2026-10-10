package com.ledgerx.data

import android.content.Context
import com.ledgerx.BuildConfig
import com.ledgerx.domain.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant

// SDK 2.x predates publishable keys: these belong in apikey, never as a JWT.
internal val PublishableKeyHeaders = createClientPlugin("PublishableKeyHeaders") {
    onRequest { request, _ ->
        if (request.headers[HttpHeaders.Authorization]?.startsWith("Bearer sb_publishable_") == true) {
            request.headers.remove(HttpHeaders.Authorization)
        }
    }
}

/**
 * Supabase client singleton. Must be initialized with [initialize] before use.
 *
 * The session is stored encrypted using [EncryptedSessionStore] backed by
 * the Android Keystore. This replaces the SDK's default plaintext storage.
 */
@OptIn(SupabaseInternal::class)
object SupabaseLedger {
    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
        BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_")

    private var _client: SupabaseClient? = null
    private var _sessionStore: EncryptedSessionStore? = null

    val client: SupabaseClient? get() = _client
    val sessionStore: EncryptedSessionStore? get() = _sessionStore

    /**
     * Initialize the Supabase client with encrypted session storage.
     * Call this once from the Application class or MainActivity.
     * Subsequent calls are no-ops if already initialized.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (_client != null) return
        if (!configured) return

        val uri = URI(BuildConfig.SUPABASE_URL)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Supabase requires an HTTPS project URL."
        }

        val store = EncryptedSessionStore(context.applicationContext)
        _sessionStore = store

        _client = createSupabaseClient(
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY
        ) {
            install(Auth) {
                sessionManager = store
            }
            install(Postgrest)
            install(Realtime)
            httpConfig { install(PublishableKeyHeaders) }
        }
    }

    suspend fun exchange(owner: String, records: List<Movement>): List<Movement> {
        val api = checkNotNull(_client) { "Supabase is not configured." }
        check(api.auth.currentUserOrNull()?.id == owner) { "Sign in again before syncing." }
        val response = api.postgrest.rpc("sync_ledger", SyncRequest(records.map(CloudMovement::from)))
            .decodeAs<SyncResponse>()
        check(api.auth.currentUserOrNull()?.id == owner) { "Your session changed. Please sync again." }
        require(response.movements.all { it.ownerId == owner }) { "Unexpected ledger owner." }
        return response.movements.map(CloudMovement::model)
    }

    /** Check if a profile exists for the given user. */
    suspend fun hasProfile(userId: String): Boolean {
        val api = checkNotNull(_client) { "Supabase is not configured." }
        val result = api.postgrest.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeList<ProfileRow>()
        return result.isNotEmpty()
    }

    /** Create a profile for the current user. */
    suspend fun createProfile(userId: String, username: String, displayName: String) {
        val api = checkNotNull(_client) { "Supabase is not configured." }
        api.postgrest.from("profiles").insert(
            ProfileInsert(id = userId, username = username, displayName = displayName)
        )
    }
}

@Serializable
data class SyncRequest(val records: List<CloudMovement>)

@Serializable
data class SyncResponse(val movements: List<CloudMovement>)

@Serializable
data class CloudMovement(
    val id: String,
    val kind: String,
    val amount: Long,
    val account: String,
    val destination: String? = null,
    val category: String? = null,
    val person: String? = null,
    val note: String = "",
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("owner_id") val ownerId: String? = null
) {
    fun model() = Movement(
        id, Kind.valueOf(kind), amount, Account.valueOf(account),
        destination?.let(Account::valueOf), category?.let(Category::valueOf),
        person, note, Instant.parse(occurredAt).toEpochMilli()
    )

    companion object {
        fun from(m: Movement) = CloudMovement(
            m.id, m.kind.name, m.amount, m.account.name, m.destination?.name,
            m.category?.name, m.person, m.note, Instant.ofEpochMilli(m.occurredAt).toString()
        )
    }
}

@Serializable
data class ProfileRow(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String
)

@Serializable
data class ProfileInsert(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String
)
