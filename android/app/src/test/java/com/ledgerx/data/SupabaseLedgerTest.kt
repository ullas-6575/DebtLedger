package com.ledgerx.data

import com.ledgerx.domain.*
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(SupabaseInternal::class)
class SupabaseLedgerTest {
    @Test fun `cloud records retain exact poisha timestamps and all record fields`() {
        Kind.entries.forEach { kind ->
            val movement = Movement("sample-id", kind, 123456L, Account.CASH,
                if (kind == Kind.TRANSFER) Account.BKASH else null,
                if (kind == Kind.EXPENSE) Category.EAT else null,
                if (kind in listOf(Kind.LEND, Kind.BORROW, Kind.RECEIVE_REPAYMENT, Kind.PAY_REPAYMENT)) "Rahim" else null,
                "Test note", 1728000000123L)
            val encoded = Json.encodeToString(CloudMovement.from(movement))
            assertEquals(movement, Json.decodeFromString<CloudMovement>(encoded).model())
            assertTrue(encoded.contains("\"amount\":123456"))
            assertFalse(encoded.contains("owner_id"))
        }
    }

    @Test fun `SDK sends publishable key as apikey without treating it as bearer JWT`() = runTest {
        var called = false
        val key = "sb_publishable_test"
        val engine = MockEngine { request ->
            called = true
            assertEquals(key, request.headers["apikey"])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("/rest/v1/rpc/sync_ledger", request.url.encodedPath)
            respond("{\"movements\":[]}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = createSupabaseClient("https://example.supabase.co", key) {
            httpEngine = engine
            install(Postgrest)
            httpConfig { install(PublishableKeyHeaders) }
        }
        try {
            val result = client.postgrest.rpc("sync_ledger", SyncRequest(emptyList())).decodeAs<SyncResponse>()
            assertTrue(called)
            assertTrue(result.movements.isEmpty())
        } finally { client.close() }
    }

    @Test fun `header compatibility preserves authenticated user tokens`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer user.jwt.token", request.headers[HttpHeaders.Authorization])
            respond("ok")
        }
        val client = HttpClient(engine) { install(PublishableKeyHeaders) }
        try { client.get("https://example.supabase.co") { header(HttpHeaders.Authorization, "Bearer user.jwt.token") } }
        finally { client.close() }
    }

    @Test fun `snapshot accepts server metadata and timezone offsets`() {
        val json = Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<SyncResponse>("""{"movements":[{"id":"one","owner_id":"owner","kind":"INCOME","amount":101,"account":"CASH","occurred_at":"2026-10-10T12:00:00+06:00","created_at":"ignored"}]}""")
        assertEquals("owner", response.movements.single().ownerId)
        assertEquals(101L, response.movements.single().model().amount)
        assertEquals(java.time.Instant.parse("2026-10-10T06:00:00Z").toEpochMilli(), response.movements.single().model().occurredAt)
    }
}
