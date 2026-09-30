// Port of ArgueWin/Services/JSONCoding.swift + the decoding config at the end of ArgueWin/Models/Models.swift.
package app.plead.android.services

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * The one shared JSON configuration (iOS `JSONDecoder.arguewin` / `JSONDecoder.supabase` / `JSONEncoder.supabase`).
 *
 * - Keys: snake_case on the wire, camelCase properties (Swift `.convertFromSnakeCase` / `.convertToSnakeCase`).
 *   An explicit `@SerialName` is written in snake_case too (the strategy leaves it unchanged).
 * - Unknown keys are ignored; a missing optional (`T?`) field decodes as null and null fields are not
 *   encoded (Swift `decodeIfPresent` / `encodeIfPresent`). A non-optional field that has a Kotlin default
 *   for construction is `@Required` in the models, so a missing one fails exactly as it does on iOS.
 * - Unknown enum raw values fail to decode, as in Swift; types with a documented fallback (e.g. `Avatar`)
 *   decode tolerantly via their own serializer.
 * - Dates (`java.time.Instant`): every timestamp shape Supabase emits is accepted ([SupabaseDate.parse]);
 *   encoded as ISO-8601 in UTC without fractional seconds (Swift `.iso8601`).
 * - UUIDs are encoded upper-case like Swift's `uuidString`; decoding accepts either case.
 *
 * Model files opt in to the date/UUID serializers with
 * `@file:UseSerializers(SupabaseDateSerializer::class, UUIDSerializer::class)`.
 */
@OptIn(ExperimentalSerializationApi::class)
object JSONCoding {
    val json: Json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }

    /** iOS `JSONDecoder.arguewin` (all network decoding). */
    val arguewin: Json get() = json

    /** iOS `JSONDecoder.supabase` / `JSONEncoder.supabase`. */
    val supabase: Json get() = json
}

object SupabaseDate {
    /** Normalises to `yyyy-MM-ddTHH:mm:ss(.SSS)±HH:MM` then parses with ISO-8601 (port of `SupabaseDate.parse`). */
    fun parse(raw: String): Instant? {
        var s = raw.trim(' ', '\t')
        if (s.length >= 11 && s[10] == ' ') {
            s = s.substring(0, 10) + "T" + s.substring(11)
        }
        // Split off the zone designator (Z, +hh, +hh:mm, -hhmm) if present after the time part.
        var zone = "Z"
        val tIndex = s.indexOf('T')
        if (tIndex >= 0) {
            val timePart = s.substring(tIndex)
            if (timePart.endsWith("Z")) {
                s = s.dropLast(1)
            } else {
                val signInTime = timePart.indexOfLast { it == '+' || it == '-' }
                if (signInTime >= 0) {
                    val signIndex = tIndex + signInTime
                    var z = s.substring(signIndex)
                    s = s.substring(0, signIndex)
                    if (z.length == 3) {
                        z += ":00"                                       // +00
                    } else if (z.length == 5 && !z.contains(":")) {        // +0000
                        z = z.substring(0, 3) + ":" + z.substring(3)
                    }
                    zone = z
                }
            }
        } else {
            // Date only.
            s += "T00:00:00"
        }
        // Clamp fractional seconds to milliseconds.
        val dot = s.lastIndexOf('.')
        if (dot >= 0) {
            val digits = s.substring(dot + 1).take(3).padEnd(3, '0')
            s = s.substring(0, dot) + "." + digits
        }
        val normalised = s + zone
        return try {
            val formatter = if (normalised.contains(".")) WITH_FRACTION else WITHOUT_FRACTION
            OffsetDateTime.parse(normalised, formatter).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    /** Swift `JSONEncoder.dateEncodingStrategy = .iso8601`: `2026-09-23T17:00:00Z`. */
    fun format(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

    private val WITH_FRACTION = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val WITHOUT_FRACTION = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.US)
}

/** `Date` columns: every Supabase timestamp shape in, ISO-8601 UTC out. */
object SupabaseDateSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.plead.SupabaseDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(SupabaseDate.format(value))
    override fun deserialize(decoder: Decoder): Instant {
        val s = decoder.decodeString()
        return SupabaseDate.parse(s) ?: throw SerializationException("Unrecognised date $s")
    }
}

/** `UUID` columns: Swift encodes `uuidString` (upper-case); either case decodes. */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.plead.UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.uuidString)
    override fun deserialize(decoder: Decoder): UUID {
        val s = decoder.decodeString()
        return parseUUID(s) ?: throw SerializationException("Bad UUID $s")
    }
}

private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Swift `UUID(uuidString:)`: strict 8-4-4-4-12 hex, either case; null otherwise. */
fun parseUUID(raw: String?): UUID? {
    if (raw == null || !UUID_PATTERN.matches(raw)) return null
    return UUID.fromString(raw)
}

/** Swift `uuid.uuidString` (upper-case), for keys and URLs built from ids. */
val UUID.uuidString: String get() = toString().uppercase(Locale.ROOT)
