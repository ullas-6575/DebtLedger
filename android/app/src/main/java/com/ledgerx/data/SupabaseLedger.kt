package com.ledgerx.data

import com.ledgerx.BuildConfig
import com.ledgerx.domain.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
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

@OptIn(SupabaseInternal::class)
object SupabaseLedger {
    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
        BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_")

    val client: SupabaseClient? by lazy {
        if (!configured) null else {
            val uri = URI(BuildConfig.SUPABASE_URL)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Supabase requires an HTTPS project URL." }
            createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
                install(Auth)
                install(Postgrest)
                httpConfig { install(PublishableKeyHeaders) }
            }
        }
    }

    suspend fun exchange(owner: String, records: List<Movement>): List<Movement> {
        val api = checkNotNull(client) { "Supabase is not configured." }
        check(api.auth.currentUserOrNull()?.id == owner) { "Sign in again before syncing." }
        val response = api.postgrest.rpc("sync_ledger", SyncRequest(records.map(CloudMovement::from)))
            .decodeAs<SyncResponse>()
        check(api.auth.currentUserOrNull()?.id == owner) { "Your session changed. Please sync again." }
        require(response.movements.all { it.ownerId == owner }) { "Unexpected ledger owner." }
        return response.movements.map(CloudMovement::model)
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
