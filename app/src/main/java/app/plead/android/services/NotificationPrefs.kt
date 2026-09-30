// Port of ArgueWin/Services/NotificationPrefs.swift.
package app.plead.android.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `profiles.notification_prefs` (CONTRACTS-v2 amendment o):
 * `{summons, verdict, settlement, reminders, lockscreen_details}`; client-writable on the own row.
 * Missing keys fall back to the brief §6 defaults (everything on, Lock Screen details off).
 */
@Serializable(with = NotificationPrefsSerializer::class)
data class NotificationPrefs(
    val summons: Boolean = true,
    val verdict: Boolean = true,
    val settlement: Boolean = true,
    val reminders: Boolean = true,
    val lockscreenDetails: Boolean = false,
) {
    enum class Toggle(val rawValue: String) {
        summons("summons"), verdict("verdict"), settlement("settlement"), reminders("reminders"), lockscreenDetails("lockscreenDetails");

        val id: String get() = rawValue

        val title: String
            get() = when (this) {
                summons -> "Summons and required actions"
                verdict -> "Verdict and judgement"
                settlement -> "Settlement updates"
                reminders -> "Deadline reminders"
                lockscreenDetails -> "Show case details on Lock Screen"
            }

        val subtitle: String
            get() = when (this) {
                summons -> "Core court notices: summons, your turn, trial set."
                verdict -> "When the judge rules and a judgement is set."
                settlement -> "Only direct offers and replies from your partner."
                reminders -> "A single reminder before a real deadline."
                lockscreenDetails -> "Case titles and names on the Lock Screen and widgets."
            }

        /** The SF Symbol name on iOS (the Settings screen maps it to its Android icon). */
        val systemImage: String
            get() = when (this) {
                summons -> "envelope.badge.fill"
                verdict -> "building.columns.fill"
                settlement -> "hand.raised.fingers.spread.fill"
                reminders -> "alarm.fill"
                lockscreenDetails -> "lock.rectangle.on.rectangle.fill"
            }

        /** The `notification_prefs` JSON key this toggle writes. */
        val jsonKey: String
            get() = when (this) {
                lockscreenDetails -> "lockscreen_details"
                else -> rawValue
            }
    }

    /** Swift `subscript(toggle:)` (get). */
    operator fun get(toggle: Toggle): Boolean = when (toggle) {
        Toggle.summons -> summons
        Toggle.verdict -> verdict
        Toggle.settlement -> settlement
        Toggle.reminders -> reminders
        Toggle.lockscreenDetails -> lockscreenDetails
    }

    /** Settings toggle → new prefs value (pure; unit-tested). */
    fun setting(toggle: Toggle, to: Boolean): NotificationPrefs = when (toggle) {
        Toggle.summons -> copy(summons = to)
        Toggle.verdict -> copy(verdict = to)
        Toggle.settlement -> copy(settlement = to)
        Toggle.reminders -> copy(reminders = to)
        Toggle.lockscreenDetails -> copy(lockscreenDetails = to)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("summons", summons)
        put("verdict", verdict)
        put("settlement", settlement)
        put("reminders", reminders)
        put("lockscreen_details", lockscreenDetails)
    }

    // MARK: Local mirrors

    /** Caches the prefs and mirrors `lockscreenDetails` into the App Group for the widgets. */
    fun persistLocally(defaults: UserDefaults = UserDefaults.standard, appGroup: UserDefaults? = UserDefaults.appGroup) {
        defaults.set(toJson(), cacheKey)
        appGroup?.set(lockscreenDetails, lockscreenDetailsKey)
    }

    companion object {
        val defaults = NotificationPrefs()

        /** The App Group suite shared with the widgets (Android: [UserDefaults.appGroup]). */
        const val appGroup = "group.app.plead.shared"

        /** App Group key the widgets read for `privacyMode` (detailed when true). */
        const val lockscreenDetailsKey = "lockscreenDetails"

        /** Local mirror of the last known prefs (offline start, instant Settings). */
        const val cacheKey = "notification_prefs.cache"

        fun cached(defaults: UserDefaults = UserDefaults.standard): NotificationPrefs {
            val element = defaults.objectForKey(cacheKey) as? JsonObject ?: return NotificationPrefs.defaults
            return fromJson(element)
        }

        /** Decodes the backend's snake_case keys whether or not they were already converted. */
        fun fromJson(o: JsonObject): NotificationPrefs {
            fun bool(vararg keys: String, default: Boolean): Boolean {
                for (k in keys) {
                    val p = o[k] as? JsonPrimitive ?: continue
                    if (!p.isString) p.booleanOrNull?.let { return it }
                }
                return default
            }
            return NotificationPrefs(
                summons = bool("summons", default = true),
                verdict = bool("verdict", default = true),
                settlement = bool("settlement", default = true),
                reminders = bool("reminders", default = true),
                lockscreenDetails = bool("lockscreen_details", "lockscreenDetails", default = false),
            )
        }
    }
}

object NotificationPrefsSerializer : KSerializer<NotificationPrefs> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: NotificationPrefs) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("NotificationPrefs needs JSON")
        json.encodeJsonElement(value.toJson())
    }

    override fun deserialize(decoder: Decoder): NotificationPrefs {
        val json = decoder as? JsonDecoder ?: throw SerializationException("NotificationPrefs needs JSON")
        val o = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("notification_prefs is not an object")
        return NotificationPrefs.fromJson(o)
    }
}

/**
 * Settings → Notifications state: the prefs, loaded from the own profile row and written back on every
 * toggle (optimistic; a failed write reverts that toggle). Every change is mirrored locally and, for
 * `lockscreenDetails`, into the App Group the widgets read.
 */
class NotificationPrefsModel(
    private val defaults: UserDefaults = UserDefaults.standard,
    private val appGroup: UserDefaults? = UserDefaults.appGroup,
) {
    var prefs: NotificationPrefs by mutableStateOf(NotificationPrefs.cached(defaults))
        private set
    var loaded: Boolean by mutableStateOf(false)
        private set
    var errorMessage: String? by mutableStateOf(null)

    /** Reads `profiles.notification_prefs` (set by `AppModel` per signed-in user; null in demo / previews). */
    var loader: (suspend () -> NotificationPrefs?)? = null

    /** Writes `profiles.notification_prefs`. */
    var saver: (suspend (NotificationPrefs) -> Unit)? = null

    suspend fun load() {
        try {
            val loader = loader ?: return
            try {
                loader()?.let(::apply)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Column not deployed yet / offline: keep the cached (or default) prefs.
            }
        } finally {
            loaded = true
        }
    }

    /** A Settings toggle changed. */
    suspend fun set(toggle: NotificationPrefs.Toggle, to: Boolean) {
        if (prefs[toggle] == to) return
        apply(prefs.setting(toggle, to))
        Analytics.track("notification_pref_changed", mapOf("pref" to toggle.jsonKey, "value" to if (to) "on" else "off"))
        if (toggle == NotificationPrefs.Toggle.lockscreenDetails && to) Analytics.track("notification_detail_preview_enabled")
        val saver = saver ?: return
        try {
            saver(prefs)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            apply(prefs.setting(toggle, !to))
            errorMessage = "Couldn't save that setting. Check your connection and try again."
        }
    }

    /** Sign-out: back to the privacy-first defaults on this device. */
    fun reset() {
        loader = null
        saver = null
        loaded = false
        apply(NotificationPrefs.defaults)
    }

    private fun apply(new: NotificationPrefs) {
        prefs = new
        new.persistLocally(defaults, appGroup)
    }
}
