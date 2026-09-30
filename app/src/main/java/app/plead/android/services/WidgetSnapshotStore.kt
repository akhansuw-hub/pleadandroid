// Port of ArgueWin/Services/WidgetSnapshotStore.swift. The DEBUG `WidgetPreviewHarness` overlay (`AWWidgetPreview`)
// renders the widget views themselves, so it belongs to wave 3f (widgets/); see STATUS.md.
package app.plead.android.services

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import app.plead.android.app.DemoHarness
import app.plead.android.app.PleadApplication
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.widgets.PleadWidgetReceiver
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Derives the widget snapshot (CONTRACTS-v2 amendment o) from [CaseStore], writes it to the App Group suite
 * (`widget-snapshot.json`) and reloads the widgets. Observation-driven: every relevant store change schedules a
 * write at most 2 s later (changes inside the window coalesce into it); an unchanged snapshot is not rewritten
 * (no needless reloads).
 *
 * Hooked from [CaseStore] itself (`load(userId)` for the live store; the DEBUG demo store on init), so
 * no app-level wiring is needed. Silent pushes call [refreshNow] after refreshing the store; the verdict screen
 * calls [markVerdictOpened].
 */
class WidgetSnapshotStore(
    private val defaults: UserDefaults? = UserDefaults.appGroup,
    private val clock: () -> Instant = { Instant.now() },
) {
    /** Debounce between a store change and the write. */
    var debounce: Duration = 2.seconds

    private var storeRef: WeakReference<CaseStore>? = null
    private val store: CaseStore? get() = storeRef?.get()
    private var pending: Job? = null
    private var lastWritten: WidgetSnapshot? = null

    /**
     * Verdicts opened on this device in the last 24 h (case id → when): a revealed case drops from
     * "The judge has ruled" once read. Persisted in the App Group suite so a relaunch never brings a read verdict back.
     */
    private var openedAt: MutableMap<UUID, Instant> = loadOpened(defaults, clock())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observers: List<Job> = emptyList()
    private var observation: Job? = null

    /** Called after every write (Android: Glance widget update). Replaced by tests. */
    var reloadTimelines: () -> Unit = WidgetCenter::reloadAllTimelines

    /** Verdicts opened within the last 24 h. */
    val openedVerdicts: Set<UUID>
        get() {
            val cutoff = clock().minusSeconds(openedVerdictTTL)
            return openedAt.filterValues { it.isAfter(cutoff) }.keys
        }

    // MARK: Lifecycle

    /** Start observing `store` (idempotent; a new store replaces the old one, e.g. after sign-out). */
    fun start(store: CaseStore) {
        if (this.store === store) return
        storeRef = WeakReference(store)
        if (observers.isEmpty()) {
            // The Settings toggle writes `lockscreenDetails` to the App Group suite; foregrounding also catches
            // deadlines that passed while suspended (AppModel.becameActive → [appBecameActive]).
            defaults?.let { d -> observers = listOf(scope.launch { d.changes.collect { scheduleWrite() } }) }
        }
        ensureApplyNotifications()
        observe()
        scheduleWrite()
    }

    /**
     * Called by [CaseStore]. The live store always registers; a preview/demo store only in DEBUG `AWDemo`
     * runs (tests and previews build many preview stores and must not touch the App Group).
     */
    fun register(store: CaseStore, preview: Boolean) {
        if (preview && !DemoHarness.isDemo) return
        start(store)
    }

    /** Swift `UIApplication.didBecomeActiveNotification`. */
    fun appBecameActive() = scheduleWrite()

    /** Write immediately (silent push, background refresh) and reload the widgets. */
    fun refreshNow() {
        pending?.cancel()
        pending = null
        write()
    }

    /** The verdict screen was opened for this case: the widget moves on from "The judge has ruled". */
    fun markVerdictOpened(caseId: UUID) {
        if (openedVerdicts.contains(caseId)) return
        openedAt[caseId] = clock()
        persistOpened()
        scheduleWrite()
    }

    /** Writes the unexpired entries (expired ones are pruned on every write). */
    private fun persistOpened() {
        val cutoff = clock().minusSeconds(openedVerdictTTL)
        openedAt = openedAt.filterValues { it.isAfter(cutoff) }.toMutableMap()
        val raw = openedAt.entries.associate { it.key.toString().lowercase() to it.value.toEpochMilli() / 1000.0 }
        defaults?.setDoubles(raw, openedVerdictsKey)
    }

    // MARK: Observation

    private fun observe() {
        observation?.cancel()
        val store = store ?: return
        observation = scope.launch {
            snapshotFlow { makeSnapshot(store, WidgetPrivacyMode.generic, emptySet(), Instant.EPOCH) }
                .drop(1)
                .collect { scheduleWrite() }
        }
    }

    /**
     * Coalescing debounce: the first change arms a write `debounce` later and every change inside that
     * window rides along (the write derives the snapshot at fire time). Not a resetting debounce: any
     * App Group write emits a change, and a timer that restarted on each one could be starved by unrelated writes.
     */
    private fun scheduleWrite() {
        if (store == null || pending != null) return
        val delayFor = debounce
        pending = scope.launch {
            delay(delayFor)
            pending = null
            write()
        }
    }

    val privacyMode: WidgetPrivacyMode
        get() = if (defaults?.bool(WidgetSnapshot.lockscreenDetailsKey) == true) WidgetPrivacyMode.detailed else WidgetPrivacyMode.generic

    /** The snapshot as of now. */
    fun currentSnapshot(now: Instant? = null): WidgetSnapshot? {
        val store = store ?: return null
        return makeSnapshot(store, privacyMode, openedVerdicts, now ?: clock())
    }

    /** The last snapshot this store wrote (tests; the stored JSON is the source of truth for the widgets). */
    val lastWrittenSnapshot: WidgetSnapshot? get() = lastWritten

    private fun write() {
        val snapshot = currentSnapshot() ?: return
        if (snapshot.sameContent(lastWritten)) return
        val d = defaults ?: return
        snapshot.save(d)
        lastWritten = snapshot
        runCatching { reloadTimelines() }
    }

    /** Signed out: widgets fall back to the neutral state. */
    fun clear() {
        val empty = WidgetSnapshot(privacyMode = privacyMode, activeCaseCount = 0, primary = null)
        defaults?.let { empty.save(it) }
        lastWritten = empty
        runCatching { reloadTimelines() }
    }

    companion object {
        val shared: WidgetSnapshotStore by lazy { WidgetSnapshotStore() }

        private var applyObserverInstalled = false

        /**
         * `snapshotFlow` only sees writes to the global snapshot once apply notifications are sent. Compose UI sends
         * them every frame; without a frame (app in the background, unit tests) this does the same on the main
         * thread, like Compose's own `GlobalSnapshotManager`.
         */
        fun ensureApplyNotifications() {
            if (applyObserverInstalled) return
            // No main looper in plain JVM unit tests: those send the notifications themselves.
            val looper = Looper.getMainLooper() ?: return
            applyObserverInstalled = true
            val handler = Handler(looper)
            var scheduled = false
            Snapshot.registerGlobalWriteObserver {
                if (!scheduled) {
                    scheduled = true
                    handler.post {
                        scheduled = false
                        Snapshot.sendApplyNotifications()
                    }
                }
            }
        }

        /** App Group key: `[caseId (lowercased): opened-at Unix seconds]`. */
        const val openedVerdictsKey = "widget.openedVerdicts"

        /** How long an opened verdict stays suppressed (then it would only matter for a stale snapshot). */
        const val openedVerdictTTL: Long = 24 * 3600

        private fun loadOpened(defaults: UserDefaults?, now: Instant): MutableMap<UUID, Instant> {
            val raw = defaults?.dictionary(openedVerdictsKey) ?: return mutableMapOf()
            val cutoff = now.minusSeconds(openedVerdictTTL)
            val out = mutableMapOf<UUID, Instant>()
            for ((key, value) in raw) {
                val id = parseUUID(key) ?: continue
                val seconds = (value as? JsonPrimitive)?.doubleOrNull ?: continue
                val at = Instant.ofEpochMilli((seconds * 1000).toLong())
                if (at.isAfter(cutoff)) out[id] = at
            }
            return out
        }

        // MARK: Derivation (pure; tested)

        /**
         * Priority for `primary`: a summons I must answer → my turn in trial → a settlement offer awaiting me
         * → deliberating / awaiting the verdict → a revealed verdict not yet opened → a judgement I must act on
         * → an outstanding settlement agreement → none. `activeCaseCount` = my open cases.
         */
        fun makeSnapshot(store: CaseStore, privacy: WidgetPrivacyMode, openedVerdicts: Set<UUID>, now: Instant = Instant.now()): WidgetSnapshot {
            val me = store.me ?: return WidgetSnapshot(updatedAt = now, privacyMode = privacy, activeCaseCount = 0, primary = null)
            val mine = store.cases.filter { it.role(me.id) != null }
            val open = mine.filter { it.status.isOpen }
            // Most recently updated first within each rule.
            val byRecency = mine.sortedByDescending { it.updatedAt }
            fun first(match: (Case, CaseAction) -> Boolean): Case? {
                for (c in byRecency) {
                    if (match(c, store.nextAction(c))) return c
                }
                return null
            }

            var pick: Triple<Case, WidgetState, Instant?>? = null
            first { c, a -> c.status == CaseStatus.summoned && c.defendantId == me.id && a == CaseAction.enterPlea }?.let {
                pick = Triple(it, WidgetState.summoned, it.deadlineAt)
            }
            if (pick == null) {
                first { c, a -> c.status == CaseStatus.trial && c.phaseTurnOwner != null && c.phaseTurnOwner == c.role(me.id) && a == CaseAction.yourTurnInCourt }
                    ?.let { pick = Triple(it, WidgetState.yourTurn, it.deadlineAt) }
            }
            if (pick == null) {
                first { c, _ -> store.pendingSettlementForMe(c.id) }?.let {
                    val s = store.settlement(it.id)
                    pick = Triple(it, WidgetState.settlement, store.latestOffer(it.id)?.expiresAt ?: s?.expiresAt)
                }
            }
            if (pick == null) {
                first { c, _ -> c.status.isDeliberating }?.let { pick = Triple(it, WidgetState.deliberating, it.scheduledReadingAt) }
            }
            if (pick == null) {
                first { c, _ -> c.status == CaseStatus.verdict && !openedVerdicts.contains(c.id) }?.let { pick = Triple(it, WidgetState.verdictReady, null) }
            }
            if (pick == null) {
                first { _, a -> a == CaseAction.chooseJudgement || a == CaseAction.acceptJudgement || a == CaseAction.markJudgementServed }
                    ?.let { pick = Triple(it, WidgetState.judgementDue, store.judgement(it.id)?.dueAt) }
            }
            if (pick == null) {
                store.outstandingSettlementCases.firstOrNull { c -> mine.any { it.id == c.id } }
                    ?.let { pick = Triple(it, WidgetState.agreementDue, store.settlement(it.id)?.dueAt) }
            }

            val primary = pick?.let { (c, state, deadline) ->
                WidgetCase(
                    caseId = c.id, caseNumber = c.caseNumber, state = state, caseTitle = c.title,
                    partnerDisplayName = store.opponentId(c)?.let { store.name(it, fallback = "Your partner") } ?: "Your partner",
                    deadlineAt = deadline,
                )
            }
            return WidgetSnapshot(updatedAt = now, privacyMode = privacy, activeCaseCount = open.size, primary = primary)
        }
    }
}

/**
 * WidgetKit's `WidgetCenter.shared.reloadAllTimelines()`: asks every Plead Glance widget to re-render from the
 * stored snapshot (wave 3f adds its receivers to [receivers]).
 */
object WidgetCenter {
    val receivers: MutableList<Class<*>> = mutableListOf(PleadWidgetReceiver::class.java)

    fun reloadAllTimelines() {
        val context = PleadApplication.contextOrNull ?: return
        val manager = AppWidgetManager.getInstance(context) ?: return
        for (receiver in receivers) {
            val ids = manager.getAppWidgetIds(ComponentName(context, receiver))
            if (ids.isEmpty()) continue
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(ComponentName(context, receiver))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
