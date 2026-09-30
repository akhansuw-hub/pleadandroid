// Shared JVM test support for the wave-2a service tests: a main-dispatcher rule (Swift `@MainActor`) and a canned
// Supabase HTTP layer (iOS `StubURLProtocol`).
package app.plead.android.services

import androidx.compose.runtime.snapshots.Snapshot
import app.plead.android.app.LaunchArguments
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swift `@MainActor` for JVM tests: `Dispatchers.Main` runs on a test dispatcher (pass [dispatcher] to `runTest` so
 * delays run on virtual time), no demo flags, no analytics sink.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
        LaunchArguments.set(mapOf("AWDemo" to "NO"))
        Analytics.sink = null
    }

    override fun finished(description: Description) {
        Analytics.sink = null
        LaunchArguments.set(emptyMap())
        Dispatchers.resetMain()
    }
}

/** Compose snapshot writes outside a composition reach `snapshotFlow` once apply notifications go out. */
fun sendApplyNotifications() = Snapshot.sendApplyNotifications()

/** Canned PostgREST / Functions responses for a `SupabaseClient` built on ktor's MockEngine. */
object StubSupabase {
    data class Recorded(val method: String, val url: Url, val body: String?) {
        val path: String get() = url.encodedPath
        fun query(name: String): String? = url.parameters[name]

        /** The JSON body (the first object when PostgREST was sent an array). */
        val json: JsonObject?
            get() = body?.let { runCatching { JSONCoding.json.parseToJsonElement(it) }.getOrNull() }?.let {
                (it as? JsonObject) ?: ((it as? JsonArray)?.firstOrNull() as? JsonObject)
            }
    }

    @Volatile private var handler: (Recorded) -> Pair<Int, String> = { 404 to "{}" }
    private val log: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    fun install(h: (Recorded) -> Pair<Int, String>) {
        handler = h
        log.clear()
    }

    val requests: List<Recorded> get() = synchronized(log) { log.toList() }

    /** Same serializer as `SupabaseService.shared`, over the mock engine, with the anon key as the token. */
    fun client(): SupabaseClient = createSupabaseClient(supabaseUrl = "https://stub.supabase.co", supabaseKey = "anon") {
        httpEngine = MockEngine { request ->
            val body = when (val content = request.body) {
                is TextContent -> content.text
                is ByteArrayContent -> content.bytes().decodeToString()
                else -> null
            }
            val rec = Recorded(request.method.value, request.url, body)
            log.add(rec)
            val (status, text) = handler(rec)
            respond(text, HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json"))
        }
        defaultSerializer = KotlinXSerializer(JSONCoding.json)
        install(Postgrest)
        install(Functions)
        install(Storage)
    }
}
