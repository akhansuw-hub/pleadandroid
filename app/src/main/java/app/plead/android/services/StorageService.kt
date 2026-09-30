// Port of ArgueWin/Services/StorageService.swift.
package app.plead.android.services

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.client.request.header
import io.ktor.http.ContentType
import java.net.URI
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exhibit files in the private `exhibits` bucket + a signed-URL cache. */
class StorageService(private val client: SupabaseClient) {
    private val lock = Mutex()
    private val cache = mutableMapOf<String, Pair<URI, Instant>>()
    private val ttl: Int = 60 * 60 // seconds

    suspend fun upload(data: ByteArray, to: String, contentType: String = "image/jpeg"): String {
        client.storage.from(bucket).upload(to, data) {
            upsert = true
            this.contentType = ContentType.parse(contentType)
            httpOverride { header("cache-control", "max-age=3600") }
        }
        return to
    }

    suspend fun signedURL(path: String): URI {
        lock.withLock {
            cache[path]?.let { (url, expires) -> if (expires.isAfter(Instant.now().plusSeconds(60))) return url }
        }
        val raw = client.storage.from(bucket).createSignedUrl(path, ttl.seconds)
        val url = URI(raw)
        lock.withLock { cache[path] = url to Instant.now().plusSeconds(ttl.toLong()) }
        return url
    }

    /** Resolves many paths at once, skipping failures. */
    suspend fun signedURLs(paths: List<String>): Map<String, URI> {
        val result = mutableMapOf<String, URI>()
        for (path in paths) {
            try {
                result[path] = signedURL(path)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                // Never surfaced as an alert: the tile shows its placeholder. Expected when RLS hides the
                // file (e.g. the other side's evidence before the defence is filed).
                runCatching { Log.i("storage", "signed URL skipped: $e") }
            }
        }
        return result
    }

    suspend fun clearCache() {
        lock.withLock { cache.clear() }
    }

    companion object {
        const val bucket = "exhibits"

        /** Canonical path from CONTRACTS.md. */
        fun path(couple: UUID, case: UUID, exhibit: UUID): String =
            "${couple.lower()}/${case.lower()}/${exhibit.lower()}"

        /** Staging path used while filing (the case id doesn't exist yet); the server moves it. */
        fun pendingPath(couple: UUID, id: UUID = UUID.randomUUID()): String = "${couple.lower()}/pending/${id.lower()}"

        private fun UUID.lower() = toString().lowercase(Locale.ROOT)
    }
}
