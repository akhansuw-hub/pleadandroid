// Port of ArgueWin/Services/ProfileService.swift.
package app.plead.android.services

import app.plead.android.models.Avatar
import app.plead.android.models.Couple
import app.plead.android.models.JudgePersona
import app.plead.android.models.Profile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.result.PostgrestResult
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Direct table access the client is allowed by RLS + column grants: own profile, own couple, persona. */
class ProfileService(val client: SupabaseClient) {

    // MARK: Profiles (20260924000700: column grants + `profiles_public`)
    //
    // `select=*` on `profiles` is refused (42501): clients may not read `push_token`, `timezone` or
    // `past_couple_ids` there. Reads go through the `profiles_public` view, which returns every safe
    // column for me and my current partner (incl. `couple_id` and `onboarding_completed_at`), and only
    // id/name/avatar/deleted_at/created_at for ex-partners (other columns null). Writes to `profiles`
    // return the same explicit column list.

    /** My row plus `past_couple_ids` (only non-null on my own row in the view). */
    data class OwnProfile(val profile: Profile, val pastCoupleIds: List<UUID>) {
        companion object {
            fun from(row: JsonObject): OwnProfile = OwnProfile(
                profile = JSONCoding.json.decodeFromJsonElement(Profile.serializer(), row),
                pastCoupleIds = row.lenient("past_couple_ids", ListSerializer(UUIDSerializer)) ?: emptyList(),
            )
        }
    }

    suspend fun fetchOwnProfile(id: UUID): OwnProfile? {
        val rows = client.from(readSource).select(Columns.raw(ownColumns)) {
            filter { eq("id", id.toString()) }
            limit(1)
        }.rows()
        return rows.firstOrNull()?.let(OwnProfile::from)
    }

    suspend fun fetchProfile(id: UUID): Profile? = fetchOwnProfile(id)?.profile

    /**
     * The other member of my couple. The view only returns `couple_id` for me and my *current*
     * partner (null for ex-partners), so an ex-partner never matches this filter.
     */
    suspend fun fetchPartner(coupleId: UUID, me: UUID): Profile? {
        val result = client.from(readSource).select(Columns.raw(columns)) {
            filter {
                eq("couple_id", coupleId.toString())
                neq("id", me.toString())
            }
            limit(1)
        }
        return result.list(Profile.serializer()).firstOrNull()
    }

    /**
     * Ex-partners (and anyone else the view lets me see) by id: name + avatar for case history.
     * Their `couple_id` / `onboarding_completed_at` / `partner_name_temp` come back null.
     */
    suspend fun fetchProfiles(ids: List<UUID>): List<Profile> {
        if (ids.isEmpty()) return emptyList()
        return client.from(readSource).select(Columns.raw(columns)) {
            filter { isIn("id", ids.map(UUID::toString)) }
        }.list(Profile.serializer())
    }

    suspend fun fetchCouple(id: UUID): Couple? =
        client.from("couples").select {
            filter { eq("id", id.toString()) }
            limit(1)
        }.list(Couple.serializer()).firstOrNull()

    /**
     * `avatar_json` is written as a JSON object (keys: skin, hair, hairstyle, top, outfit, version).
     * Only these writable columns: display_name, avatar_json, mute_reminders, partner_name_temp,
     * onboarding_completed_at (+ id on insert). The time zone goes through `register_push`.
     */
    data class ProfileInsert(val id: UUID, val displayName: String, val avatarJson: Avatar) {
        fun body(): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id.uuidString))
            put("display_name", displayName)
            put("avatar_json", JSONCoding.json.encodeToJsonElement(Avatar.serializer(), avatarJson))
        }
    }

    data class ProfileUpdate(val displayName: String, val avatarJson: Avatar) {
        fun body(): JsonObject = buildJsonObject {
            put("display_name", displayName)
            put("avatar_json", JSONCoding.json.encodeToJsonElement(Avatar.serializer(), avatarJson))
        }
    }

    /**
     * Creates or updates my profile row (display name + pixel avatar). Update first, insert if no
     * row exists: a PostgREST upsert would put `id` in `ON CONFLICT DO UPDATE SET`, and clients have
     * no UPDATE grant on `id`.
     */
    suspend fun upsertProfile(id: UUID, displayName: String, avatar: Avatar): Profile {
        val update = ProfileUpdate(displayName = displayName, avatarJson = avatar)
        val updated = client.from("profiles").update(update.body()) {
            select(Columns.raw(columns))
            filter { eq("id", id.toString()) }
        }.list(Profile.serializer())
        updated.firstOrNull()?.let { return it }
        return try {
            client.from("profiles").insert(ProfileInsert(id = id, displayName = displayName, avatarJson = avatar).body()) {
                select(Columns.raw(columns))
                single()
            }.single(Profile.serializer())
        } catch (e: PostgrestRestException) {
            if (e.code != "23505") throw e
            // Created concurrently (another device): update it instead.
            client.from("profiles").update(update.body()) {
                select(Columns.raw(columns))
                single()
                filter { eq("id", id.toString()) }
            }.single(Profile.serializer())
        }
    }

    data class AvatarUpdate(val avatarJson: Avatar) {
        fun body(): JsonObject = buildJsonObject { put("avatar_json", JSONCoding.json.encodeToJsonElement(Avatar.serializer(), avatarJson)) }
    }

    /** Clients may write their own profile row (RLS); updates `profiles.avatar_json`. */
    suspend fun setAvatar(id: UUID, avatar: Avatar): Profile = updateProfile(id, AvatarUpdate(avatar).body())

    // MARK: Onboarding (migration 20260924000400)

    /** `profiles.partner_name_temp`: what I call my partner until they link (null clears it). */
    suspend fun setPartnerNameTemp(id: UUID, name: String?): Profile =
        updateProfile(id, buildJsonObject { put("partner_name_temp", name?.let(::JsonPrimitive) ?: JsonNull) }) // explicit null clears

    data class OnboardingCompleted(val onboardingCompletedAt: Instant) {
        fun body(): JsonObject = buildJsonObject { put("onboarding_completed_at", SupabaseDate.format(onboardingCompletedAt)) }
    }

    /** `profiles.onboarding_completed_at`, set once on the final onboarding screen's CTA. */
    suspend fun markOnboardingCompleted(id: UUID, at: Instant = Instant.now()): Profile =
        updateProfile(id, OnboardingCompleted(at).body())

    /** `couples.together_since` is a `date` column: written as "yyyy-MM-dd" (null clears it). */
    suspend fun setTogetherSince(coupleId: UUID, date: Instant?): Couple =
        client.from("couples").update(buildJsonObject { put("together_since", date?.let { JsonPrimitive(dateOnly(it)) } ?: JsonNull) }) {
            select()
            single()
            filter { eq("id", coupleId.toString()) }
        }.single(Couple.serializer())

    // MARK: Notification prefs (amendment o)
    //
    // `profiles_public` (20260924000700) does not expose `notification_prefs`, so the own row is read
    // with an explicit single-column select on `profiles` (RLS: own row; needs the column's SELECT grant)
    // and written with a single-column update (UPDATE grant on `notification_prefs`).

    /** My `notification_prefs` (null when the row / column is missing: callers keep the defaults). */
    suspend fun fetchNotificationPrefs(id: UUID): NotificationPrefs? {
        val rows = client.from("profiles").select(Columns.raw("notification_prefs")) {
            filter { eq("id", id.toString()) }
            limit(1)
        }.rows()
        return rows.firstOrNull()?.lenient("notification_prefs", NotificationPrefs.serializer())
    }

    data class NotificationPrefsUpdate(val notificationPrefs: NotificationPrefs) {
        fun body(): JsonObject = buildJsonObject {
            put("notification_prefs", JSONCoding.json.encodeToJsonElement(NotificationPrefs.serializer(), notificationPrefs))
        }
    }

    /** Writes the whole prefs object (`profiles.notification_prefs`) on my row. */
    suspend fun updateNotificationPrefs(prefs: NotificationPrefs, id: UUID) {
        client.from("profiles").update(NotificationPrefsUpdate(prefs).body()) {
            filter { eq("id", id.toString()) }
        }
    }

    data class PersonaUpdate(val judgePersona: JudgePersona)

    suspend fun setPersona(coupleId: UUID, persona: JudgePersona): Couple =
        client.from("couples").update(buildJsonObject { put("judge_persona", persona.rawValue) }) {
            select()
            single()
            filter { eq("id", coupleId.toString()) }
        }.single(Couple.serializer())

    private suspend fun updateProfile(id: UUID, body: JsonObject): Profile =
        client.from("profiles").update(body) {
            select(Columns.raw(columns))
            single()
            filter { eq("id", id.toString()) }
        }.single(Profile.serializer())

    companion object {
        /** The columns the app reads, from `profiles_public` and from `profiles` writes (`.select(...)`). */
        const val columns = "id,display_name,avatar_json,couple_id,partner_name_temp,onboarding_completed_at,deleted_at,created_at"

        /** Own row only: also the couples I have left (history stays readable). */
        const val ownColumns = "$columns,past_couple_ids"

        /** The client read path for profiles. */
        const val readSource = "profiles_public"

        /** Calendar date in the user's time zone, as Postgres `date` text. */
        fun dateOnly(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
            val d = date.atZone(zone).toLocalDate()
            return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
        }
    }
}

// MARK: - PostgREST result decoding with the app's JSON (JSONDecoder.arguewin)

/** The response rows as JSON objects (an object response counts as one row). */
internal fun PostgrestResult.rows(): List<JsonObject> {
    val element = JSONCoding.json.parseToJsonElement(data)
    return when (element) {
        is kotlinx.serialization.json.JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(element)
        else -> emptyList()
    }
}

internal fun <T> PostgrestResult.list(serializer: KSerializer<T>): List<T> {
    val element: JsonElement = JSONCoding.json.parseToJsonElement(data)
    return when (element) {
        is kotlinx.serialization.json.JsonArray -> JSONCoding.json.decodeFromJsonElement(ListSerializer(serializer), element)
        is JsonObject -> listOf(JSONCoding.json.decodeFromJsonElement(serializer, element))
        else -> emptyList()
    }
}

internal fun <T> PostgrestResult.single(serializer: KSerializer<T>): T =
    list(serializer).firstOrNull() ?: throw IllegalStateException("PostgREST returned no row")
