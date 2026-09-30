// Android counterpart of Foundation's `UserDefaults` (the standard domain and the App Group suite the widgets read).
// Not a Swift file of its own: every ported service reads and writes the same keys it does on iOS through this.
//
// Reads are synchronous (like `UserDefaults`), served from an in-memory mirror; every write updates the mirror at
// once and is persisted to a Preferences DataStore in order on a background writer (PORT.md: "DataStore, same
// keys"). Values are stored as JSON text so a key keeps its Swift type (bool, number, string, array, dictionary).
package app.plead.android.services

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.plead.android.app.PleadApplication
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Where a [UserDefaults] persists. The in-memory store has none (tests, previews). */
interface UserDefaultsBacking {
    fun load(): Map<String, String>
    fun write(key: String, json: String?)
}

open class UserDefaults(private val backing: UserDefaultsBacking? = null) {
    private val values = ConcurrentHashMap<String, JsonElement>()
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Swift `UserDefaults.didChangeNotification` (with the key that changed). */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    init {
        backing?.load()?.forEach { (k, v) ->
            runCatching { JSONCoding.json.parseToJsonElement(v) }.getOrNull()?.let { values[k] = it }
        }
    }

    // MARK: Reads (Swift `UserDefaults` semantics)

    /** `object(forKey:) != nil`. */
    fun has(key: String): Boolean = values.containsKey(key)

    /** `object(forKey:)` as its JSON value. */
    fun objectForKey(key: String): JsonElement? = values[key]

    /** `bool(forKey:)`: false when absent. */
    fun bool(key: String): Boolean {
        val p = values[key] as? JsonPrimitive ?: return false
        p.booleanOrNull?.let { if (!p.isString) return it }
        if (p.isString) return app.plead.android.app.LaunchArguments.boolValue(p.content)
        return (p.doubleOrNull ?: 0.0) != 0.0
    }

    /** `object(forKey:) as? Bool`. */
    fun boolOrNull(key: String): Boolean? = (values[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** `integer(forKey:)`: 0 when absent. */
    fun integer(key: String): Int = int(key) ?: 0

    /** `object(forKey:) as? Int`. */
    fun int(key: String): Int? {
        val p = values[key] as? JsonPrimitive ?: return null
        if (p.isString || p.booleanOrNull != null) return null
        val d = p.doubleOrNull ?: return null
        return if (d == Math.floor(d)) d.toInt() else null
    }

    /** `double(forKey:)`: 0 when absent; `doubleOrNull` = `object(forKey:) as? Double`. */
    fun double(key: String): Double = doubleOrNull(key) ?: 0.0
    fun doubleOrNull(key: String): Double? = (values[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** `string(forKey:)`. */
    fun string(key: String): String? = (values[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `stringArray(forKey:)`. */
    fun stringArray(key: String): List<String>? =
        (values[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    /** `dictionary(forKey:)`. */
    fun dictionary(key: String): Map<String, JsonElement>? = (values[key] as? JsonObject)?.toMap()

    /** `dictionaryRepresentation().keys`. */
    val keys: Set<String> get() = values.keys.toSet()

    // MARK: Writes

    fun set(value: Boolean, key: String) = put(key, JsonPrimitive(value))
    fun set(value: Int, key: String) = put(key, JsonPrimitive(value))
    fun set(value: Long, key: String) = put(key, JsonPrimitive(value))
    fun set(value: Double, key: String) = put(key, JsonPrimitive(value))
    fun set(value: String?, key: String) = if (value == null) removeObject(key) else put(key, JsonPrimitive(value))
    fun set(value: List<String>, key: String) = put(key, JsonArray(value.map(::JsonPrimitive)))
    fun set(value: JsonElement?, key: String) = if (value == null || value is JsonNull) removeObject(key) else put(key, value)

    /** A `[String: Double]` dictionary (e.g. `widget.openedVerdicts`). */
    fun setDoubles(value: Map<String, Double>, key: String) = put(key, JsonObject(value.mapValues { JsonPrimitive(it.value) }))

    fun removeObject(key: String) {
        if (values.remove(key) != null) {
            backing?.write(key, null)
            _changes.tryEmit(key)
        }
    }

    private fun put(key: String, value: JsonElement) {
        values[key] = value
        backing?.write(key, JSONCoding.json.encodeToString(JsonElement.serializer(), value))
        _changes.tryEmit(key)
    }

    companion object {
        /** A fresh, empty, unpersisted store (tests, previews, the demo harness). */
        fun inMemory(): UserDefaults = UserDefaults(null)

        /** `UserDefaults.standard`. Falls back to memory when no Application exists (plain JVM unit tests). */
        val standard: UserDefaults by lazy { persistent("plead_standard") }

        /**
         * The App Group suite `group.app.plead.shared` (iOS `UserDefaults(suiteName:)`): what the widgets read
         * (`lockscreenDetails`, `widget.openedVerdicts`, the snapshot JSON).
         */
        val appGroup: UserDefaults by lazy { persistent("group.app.plead.shared") }

        private fun persistent(name: String): UserDefaults {
            val context = PleadApplication.contextOrNull ?: return inMemory()
            return UserDefaults(DataStoreBacking(context, name))
        }
    }
}

/** One Preferences DataStore per suite; the whole file is read once, writes are applied in order. */
@OptIn(ExperimentalCoroutinesApi::class)
private class DataStoreBacking(context: Context, name: String) : UserDefaultsBacking {
    private val store: DataStore<Preferences> = stores.getOrPut(name) {
        PreferenceDataStoreFactory.create(produceFile = { context.applicationContext.preferencesDataStoreFile(name) })
    }
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    override fun load(): Map<String, String> = runCatching {
        runBlocking(Dispatchers.IO) { store.data.first() }.asMap().entries
            .mapNotNull { (k, v) -> (v as? String)?.let { k.name to it } }.toMap()
    }.getOrDefault(emptyMap())

    override fun write(key: String, json: String?) {
        writer.launch {
            runCatching {
                store.edit { prefs ->
                    val k = stringPreferencesKey(key)
                    if (json == null) prefs.remove(k) else prefs[k] = json
                }
            }
        }
    }

    companion object {
        private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
    }
}
