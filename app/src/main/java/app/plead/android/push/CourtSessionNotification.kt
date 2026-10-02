// The Android replacement for the Live Activity (PORT.md §2, amendment az): an ongoing "court in session" notification
// showing the same phase / case / countdown state as PleadWidgets/LiveActivity (PleadActivityLockScreenView), driven
// by the same planner (services/CourtSessionState.kt `LiveActivityPlanner`) from app state, and by the alert pushes
// when the app is not running. It implements AppModel's `CourtSessionPresenter` seam (app/AppModelHosts.kt) in place
// of Swift's `LiveActivityService` (minus the ActivityKit tokens: Android never calls `register_live_activity`).
//
// ActivityKit → Android:
//   Activity.request        post an ongoing notification (tag `court_session`, one per case and kind)
//   activity.update         re-post it with the new content state (silently: setOnlyAlertOnce)
//   activity.end(.immediate) cancel it; `.after(+15 min)` (plea entered) re-post the final state, no longer ongoing,
//                            timing out after 15 minutes
//   staleDate               the notification times out (setTimeoutAfter) and the next reconcile ends it
//   user dismissed          counts as ended: never restarted for that case and kind
// Bookkeeping keeps the iOS keys (`liveActivity.startedAt`, `liveActivity.finished`) in UserDefaults.standard.
@file:Suppress("EnumEntryName")

package app.plead.android.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.plead.android.R
import app.plead.android.app.CourtSessionPresenter
import app.plead.android.app.PleadApplication
import app.plead.android.services.Analytics
import app.plead.android.services.CaseStore
import app.plead.android.services.CourtSessionPhase
import app.plead.android.services.CourtSessionState
import app.plead.android.services.LiveActivityPlanner
import app.plead.android.services.NotificationPrefs
import app.plead.android.services.PleadActivityCopy
import app.plead.android.services.PleadCaseActivityAttributes
import app.plead.android.services.PushService
import app.plead.android.services.UserDefaults
import app.plead.android.services.parseUUID
import app.plead.android.widgets.PixelJudgeGlyph
import app.plead.android.widgets.PleadPixelSprites
import app.plead.android.widgets.PleadWidgetPalette
import app.plead.android.widgets.argb
import app.plead.android.widgets.tapIntentFor
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

class CourtSessionNotification(
    private val context: Context,
    private val defaults: UserDefaults = UserDefaults.standard,
    private val now: () -> Instant = { Instant.now() },
    /** iOS `areActivitiesEnabled`: Plead may post notifications. Replaced by tests. */
    private val activitiesEnabled: () -> Boolean = { PushNotifications.canPost(context) },
) : CourtSessionPresenter {
    /** Local starts/updates run only while this is true (set from the activity lifecycle). */
    override var isForeground: Boolean = false

    /** A running session (Swift `Activity<PleadCaseActivityAttributes>`): what is on screen. */
    @Serializable
    data class Session(
        val activityId: String,
        val caseId: String,
        val caseNumber: Int,
        val kind: String,
        val state: JsonElement,
        /** Epoch millis when the content goes stale (ActivityKit `staleDate`). */
        val staleAt: Long? = null,
    ) {
        val attributes: PleadCaseActivityAttributes?
            get() {
                val id = parseUUID(caseId) ?: return null
                val k = PleadCaseActivityAttributes.Kind.entries.firstOrNull { it.name == kind } ?: return null
                return PleadCaseActivityAttributes(id, caseNumber, k)
            }
        val contentState: CourtSessionState?
            get() = runCatching { PleadCaseActivityAttributes.json.decodeFromJsonElement(CourtSessionState.serializer(), state) }.getOrNull()
    }

    // MARK: Presenter seam

    /** Signed out: a shared device must not keep showing the previous account's court. */
    override suspend fun userChanged(uid: UUID?) {
        if (uid == null) endAll(LiveActivityPlanner.EndReason.caseMissing)
    }

    /**
     * Runs the planner against the store and applies its actions. Called on launch (reconcile), on every store change
     * while the app is open, and on foreground (AppModel.scheduleLiveActivityReconcile).
     */
    override suspend fun reconcile(store: CaseStore, openedVerdicts: Set<UUID>) {
        if (!store.hasLoaded || store.me == null) return
        forgetDismissed()
        val inputs = store.cases.map { LiveActivityPlanner.CaseInput.from(it, store, verdictOpened = openedVerdicts.contains(it.id)) }
        val at = now()
        val running = sessions().mapNotNull { s ->
            val attrs = s.attributes ?: return@mapNotNull null
            val state = s.contentState ?: return@mapNotNull null
            LiveActivityPlanner.Running(
                activityId = s.activityId, caseId = attrs.caseId, kind = attrs.kind, phase = state.phase,
                startedAt = startedAt(s.activityId) ?: recordStart(s.activityId),
                isStale = s.staleAt?.let { it <= at.toEpochMilli() } ?: false,
            )
        }
        val allowStart = isForeground && activitiesEnabled()
        val actions = LiveActivityPlanner.plan(
            cases = inputs, running = running, now = at, allowStart = allowStart,
            finished = finished, detailed = NotificationPrefs.cached(defaults).lockscreenDetails,
        )
        for (action in actions) apply(action)
    }

    /** DEBUG `AWLiveActivity summons|verdict|verdictReady`: a session with demo data (screenshots). */
    override fun startDemo(mode: String) {
        val caseId = UUID.fromString("00000000-0000-4000-8000-000000000021")
        val at = now()
        when (mode) {
            "verdict" -> request(
                PleadCaseActivityAttributes(caseId, 21, PleadCaseActivityAttributes.Kind.verdict),
                PleadActivityCopy.state(CourtSessionPhase.deliberating, at.plusSeconds(42 * 60)), source = "demo",
            )
            "verdictReady" -> request(
                PleadCaseActivityAttributes(caseId, 21, PleadCaseActivityAttributes.Kind.verdict),
                PleadActivityCopy.state(CourtSessionPhase.verdictReady, null), source = "demo",
            )
            else -> request(
                PleadCaseActivityAttributes(caseId, 21, PleadCaseActivityAttributes.Kind.summons),
                PleadActivityCopy.state(CourtSessionPhase.summoned, at.plusSeconds(23 * 3600 + 41 * 60)), source = "demo",
            )
        }
    }

    // MARK: Pushes

    /**
     * An FCM push arrived. With the app running, the silent refresh that follows every state change re-runs the planner
     * on fresh state (AppModel `push.onSilentRefresh`), so nothing to do here. Without it (the process was started for
     * the push), the alert kinds that start or move a Live Activity on iOS drive the session directly, as the backend's
     * Live Activity pushes do there: summons → Summoned, verdict_soon → Deliberating, verdict_ready → Verdict ready.
     */
    fun pushReceived(info: Map<String, Any?>, appRunning: Boolean = PleadApplication.contextOrNull?.let { PleadApplication.instance.existingModel != null } ?: false) {
        if (appRunning || PushService.isSilent(info)) return
        val payload = PushService.stringPayload(info)
        val caseId = payload["case_id"]?.let(::parseUUID) ?: return
        val detailed = NotificationPrefs.cached(defaults).lockscreenDetails
        when (payload["kind"]) {
            "summons" -> {
                val key = LiveActivityPlanner.key(caseId, PleadCaseActivityAttributes.Kind.summons)
                if (finished.contains(key) || session(key) != null || !activitiesEnabled()) return
                request(
                    PleadCaseActivityAttributes(caseId, 0, PleadCaseActivityAttributes.Kind.summons),
                    PleadActivityCopy.state(CourtSessionPhase.summoned, null, detailed = detailed), source = "push",
                )
            }
            "verdict_soon", "verdict_ready" -> {
                val phase = if (payload["kind"] == "verdict_ready") CourtSessionPhase.verdictReady else CourtSessionPhase.deliberating
                val key = LiveActivityPlanner.key(caseId, PleadCaseActivityAttributes.Kind.verdict)
                val state = PleadActivityCopy.state(phase, null, detailed = detailed)
                val running = session(key)
                if (running != null) {
                    if (running.contentState?.phase != phase) apply(LiveActivityPlanner.Action.update(key, state))
                } else if (!finished.contains(key) && activitiesEnabled()) {
                    request(PleadCaseActivityAttributes(caseId, 0, PleadCaseActivityAttributes.Kind.verdict), state, source = "push")
                }
            }
        }
    }

    // MARK: Actions

    fun apply(action: LiveActivityPlanner.Action) {
        when (action) {
            is LiveActivityPlanner.Action.start ->
                request(PleadCaseActivityAttributes(action.caseId, action.caseNumber, action.kind), action.state, source = "local")
            is LiveActivityPlanner.Action.update -> {
                val s = session(action.activityId) ?: return
                val attrs = s.attributes ?: return
                post(attrs, action.state, ongoing = true)
                save(s.copy(state = encode(action.state), staleAt = LiveActivityPlanner.staleDate(attrs.kind, action.state, now()).toEpochMilli()))
                Analytics.track("live_activity_updated", mapOf("kind" to attrs.kind.name, "phase" to action.state.phase.name))
            }
            is LiveActivityPlanner.Action.end -> {
                val s = session(action.activityId) ?: return
                end(s, action.state, action.reason)
            }
        }
    }

    /** `Activity.request`: posts the session. Returns its id (`caseId:kind`), or null when notifications are off. */
    fun request(attributes: PleadCaseActivityAttributes, state: CourtSessionState, source: String): String? {
        if (!activitiesEnabled()) return null
        val id = LiveActivityPlanner.key(attributes.caseId, attributes.kind)
        post(attributes, state, ongoing = true)
        save(
            Session(
                activityId = id, caseId = attributes.caseId.toString().lowercase(Locale.ROOT), caseNumber = attributes.caseNumber,
                kind = attributes.kind.name, state = encode(state),
                staleAt = LiveActivityPlanner.staleDate(attributes.kind, state, now()).toEpochMilli(),
            ),
        )
        recordStart(id)
        Analytics.track("live_activity_started", mapOf("kind" to attributes.kind.name, "source" to source))
        return id
    }

    private fun end(s: Session, state: CourtSessionState, reason: LiveActivityPlanner.EndReason) {
        val attrs = s.attributes
        // A plea lingers briefly as confirmation; anything else goes at once.
        if (attrs != null && reason == LiveActivityPlanner.EndReason.pleaEntered) {
            post(attrs, state, ongoing = false, timeout = Duration.ofMinutes(15))
        } else {
            cancel(s.activityId)
        }
        markFinished(s.activityId)
        forgetStart(s.activityId)
        remove(s.activityId)
        Analytics.track("live_activity_ended", mapOf("kind" to s.kind, "reason" to reason.rawValue))
    }

    fun endAll(reason: LiveActivityPlanner.EndReason) {
        for (s in sessions()) {
            val phase = s.contentState?.phase ?: CourtSessionPhase.ended
            end(s, LiveActivityPlanner.finalState(phase), reason)
        }
    }

    /** Sessions whose notification the user swiped away count as ended (never restarted). */
    private fun forgetDismissed() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val visible = runCatching { manager.activeNotifications.filter { it.tag == tag }.map { it.id }.toSet() }.getOrNull() ?: return
        for (s in sessions()) {
            if (notificationId(s.activityId) !in visible) {
                markFinished(s.activityId)
                forgetStart(s.activityId)
                remove(s.activityId)
                Analytics.track("live_activity_ended", mapOf("kind" to s.kind, "reason" to "dismissed"))
            }
        }
    }

    // MARK: Notification

    private fun post(attributes: PleadCaseActivityAttributes, state: CourtSessionState, ongoing: Boolean, timeout: Duration? = null) {
        if (!activitiesEnabled()) return
        ensureChannel(context)
        val stale = timeout ?: Duration.between(now(), LiveActivityPlanner.staleDate(attributes.kind, state, now()))
        val notification = build(context, attributes, state, now(), ongoing, stale)
        runCatching {
            @Suppress("MissingPermission") // activitiesEnabled() checks POST_NOTIFICATIONS
            NotificationManagerCompat.from(context).notify(tag, notificationId(LiveActivityPlanner.key(attributes.caseId, attributes.kind)), notification)
        }
    }

    private fun cancel(activityId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(tag, notificationId(activityId)) }
    }

    // MARK: Bookkeeping (UserDefaults: running sessions, first-seen time per session, finished case/kind keys)

    fun sessions(): List<Session> {
        val raw = defaults.objectForKey(sessionsKey) ?: return emptyList()
        return runCatching { json.decodeFromJsonElement(ListSerializer(Session.serializer()), raw) }.getOrDefault(emptyList())
    }

    private fun session(activityId: String): Session? = sessions().firstOrNull { it.activityId == activityId }

    private fun save(s: Session) {
        val list = sessions().filter { it.activityId != s.activityId } + s
        defaults.set(json.encodeToJsonElement(ListSerializer(Session.serializer()), list), sessionsKey)
    }

    private fun remove(activityId: String) {
        val list = sessions().filter { it.activityId != activityId }
        defaults.set(json.encodeToJsonElement(ListSerializer(Session.serializer()), list), sessionsKey)
    }

    val finished: Set<String> get() = defaults.stringArray(finishedKey)?.toSet() ?: emptySet()

    private fun markFinished(key: String) {
        val list = defaults.stringArray(finishedKey)?.toMutableList() ?: mutableListOf()
        if (list.contains(key)) return
        list.add(key)
        defaults.set(list.takeLast(200), finishedKey)
    }

    private fun startedAt(id: String): Instant? {
        val seconds = defaults.dictionary(startedKey)?.get(id)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
        return seconds?.let { Instant.ofEpochMilli((it * 1000).toLong()) }
    }

    private fun recordStart(id: String): Instant {
        val at = now()
        val map = startedMap().toMutableMap()
        map[id] = at.toEpochMilli() / 1000.0
        defaults.setDoubles(map, startedKey)
        return at
    }

    private fun forgetStart(id: String) {
        val map = startedMap().toMutableMap()
        map.remove(id)
        defaults.setDoubles(map, startedKey)
    }

    private fun startedMap(): Map<String, Double> = defaults.dictionary(startedKey)
        ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.let { k to it } }?.toMap()
        ?: emptyMap()

    private fun encode(state: CourtSessionState): JsonElement =
        PleadCaseActivityAttributes.json.encodeToJsonElement(CourtSessionState.serializer(), state)

    companion object {
        /** Same keys as `LiveActivityService` (UserDefaults.standard). */
        const val startedKey = "liveActivity.startedAt"
        const val finishedKey = "liveActivity.finished"

        /** The running sessions (Android only: ActivityKit keeps them for iOS). */
        const val sessionsKey = "courtSession.running"

        /** Notification tag of every court-session notification. */
        const val tag = "court_session"

        /**
         * Its own channel. Default importance, so the session sits with the alerting notifications and keeps its
         * status-bar icon (the iOS Live Activity is prominent on the Lock Screen), but without sound or vibration on
         * the channel and `setSilent` / `setOnlyAlertOnce` on every post: it never makes a noise or pops a heads-up
         * (the alert pushes do that). `_v2` because channel settings are immutable once created: the first build's
         * low-importance channel [legacyChannelIds] is deleted (at launch, `PleadApplication.setUpNotificationChannels`).
         * CONTRACTS-v2 amendment ba (2026-10-02).
         */
        const val channelId = "court_session_v2"
        const val channelName = "Court in session"
        const val channelImportance = NotificationManager.IMPORTANCE_DEFAULT

        /** Earlier ids of this channel (`court_session`: IMPORTANCE_LOW, filed under "Silent"). */
        val legacyChannelIds = listOf("court_session")

        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** The process's presenter (AppModel's `courtSession`, and the FCM service when the app is not running). */
        val shared: CourtSessionNotification by lazy { CourtSessionNotification(PleadApplication.instance) }

        fun notificationId(activityId: String): Int = activityId.hashCode()

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            legacyChannelIds.forEach { old -> if (manager.getNotificationChannel(old) != null) manager.deleteNotificationChannel(old) }
            if (manager.getNotificationChannel(channelId) != null) return
            manager.createNotificationChannel(channel())
        }

        /** The channel as created (default importance, silent: no sound, no vibration, no badge). */
        fun channel(): NotificationChannel = NotificationChannel(channelId, channelName, channelImportance).apply {
            // Lock-screen-safe by construction (generic headline; the case title only with the opt-in).
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            vibrationPattern = null
        }

        /** "021" (Swift `LAFormat.number`). */
        fun number(n: Int): String = if (n < 1000) "%03d".format(n) else n.toString()

        /** The detail line, unless empty or the same as the headline (Swift `LAFormat.detail`). */
        fun detail(state: CourtSessionState): String? {
            val d = state.detail?.trim().orEmpty()
            return if (d.isEmpty() || d == state.headline) null else d
        }

        /** The live deadline and its label, while it is ahead of `now` (Swift `LACountdown`). */
        fun countdown(state: CourtSessionState, now: Instant): Pair<String, Instant>? {
            val deadline = state.deadlineAt ?: return null
            if (!deadline.isAfter(now)) return null
            val label = PleadActivityCopy.countdownLabel(state.phase) ?: return null
            return label to deadline
        }

        /**
         * The banner (Swift `PleadActivityLockScreenView`) as RemoteViews; also rendered by the preview harness.
         * `compact` is the collapsed shade row (Android caps a collapsed custom view at ~48 dp): a smaller judge, the
         * headline on one line and the timer alone, without the case / detail lines or the countdown label.
         */
        fun contentView(context: Context, attributes: PleadCaseActivityAttributes, state: CourtSessionState, now: Instant, compact: Boolean = false): RemoteViews {
            val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
            return RemoteViews(context.packageName, R.layout.court_session_notification).apply {
                val judge = PleadPixelSprites.render(context, PixelJudgeGlyph.Kind.judge, if (compact) 30.dp else 48.dp)
                setImageViewBitmap(R.id.court_session_judge, judge.bitmap)
                setTextViewText(R.id.court_session_headline, state.headline.uppercase(Locale.getDefault()))
                if (attributes.caseNumber > 0) {
                    setTextViewText(R.id.court_session_case, "Case #${number(attributes.caseNumber)}")
                    setViewVisibility(R.id.court_session_case, View.VISIBLE)
                } else {
                    // Started from a push with no case number: the line is left out rather than showing "#000".
                    setViewVisibility(R.id.court_session_case, View.GONE)
                }
                val detail = detail(state)
                setTextViewText(R.id.court_session_detail, detail.orEmpty())
                setViewVisibility(R.id.court_session_detail, if (detail == null) View.GONE else View.VISIBLE)
                val countdown = countdown(state, now)
                if (countdown != null) {
                    setTextViewText(R.id.court_session_countdown_label, countdown.first)
                    val remaining = Duration.between(now, countdown.second).toMillis()
                    setChronometer(R.id.court_session_timer, SystemClock.elapsedRealtime() + remaining, null, true)
                    setChronometerCountDown(R.id.court_session_timer, true)
                    setViewVisibility(R.id.court_session_countdown, View.VISIBLE)
                    setViewVisibility(R.id.court_session_gavel, View.GONE)
                } else {
                    setViewVisibility(R.id.court_session_countdown, View.GONE)
                    val gavel = PleadPixelSprites.bitmap(PixelJudgeGlyph.Kind.gavel, PleadPixelSprites.cellPixels(PixelJudgeGlyph.Kind.gavel, 28f, density))
                    setImageViewBitmap(R.id.court_session_gavel, gavel)
                    setViewVisibility(R.id.court_session_gavel, View.VISIBLE)
                }
                setContentDescription(R.id.court_session_headline, PleadActivityCopy.accessibilityLabel(attributes.caseNumber, state, now))
                if (compact) {
                    val px = { dp: Float -> (dp * density).toInt() }
                    setViewPadding(R.id.court_session_root, px(16f), px(4f), px(12f), px(4f))
                    setInt(R.id.court_session_headline, "setMaxLines", 1)
                    setViewVisibility(R.id.court_session_case, View.GONE)
                    setViewVisibility(R.id.court_session_detail, View.GONE)
                    setViewVisibility(R.id.court_session_countdown_label, View.GONE)
                }
            }
        }

        /** The notification for a session state. `timeout` = until the content goes stale (or the linger after a plea). */
        fun build(
            context: Context,
            attributes: PleadCaseActivityAttributes,
            state: CourtSessionState,
            now: Instant,
            ongoing: Boolean,
            timeout: Duration?,
        ): Notification {
            val link = PleadActivityCopy.link(attributes.caseId, attributes.kind, state.phase)
            val tap = PendingIntent.getActivity(
                context, notificationId(LiveActivityPlanner.key(attributes.caseId, attributes.kind)), tapIntentFor(context, link),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val view = contentView(context, attributes, state, now)
            val collapsed = contentView(context, attributes, state, now, compact = true)
            val countdown = countdown(state, now)
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_plead)
                .setColor(PleadWidgetPalette.courtBurgundy.argb)
                .setContentTitle(state.headline)
                .setContentText(detail(state) ?: PleadActivityCopy.shortWord(state.phase))
                .setSubText(if (attributes.caseNumber > 0) "Case #${number(attributes.caseNumber)}" else null)
                .setTicker(PleadActivityCopy.accessibilityLabel(attributes.caseNumber, state, now))
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsed)
                .setCustomBigContentView(view)
                .setCustomHeadsUpContentView(view)
                .setContentIntent(tap)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            if (countdown != null) {
                builder.setUsesChronometer(true).setChronometerCountDown(true).setWhen(countdown.second.toEpochMilli()).setShowWhen(true)
            } else {
                builder.setShowWhen(false)
            }
            if (timeout != null && !timeout.isNegative && !timeout.isZero) builder.setTimeoutAfter(timeout.toMillis())
            return builder.build()
        }
    }
}
