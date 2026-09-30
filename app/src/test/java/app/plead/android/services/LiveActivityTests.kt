// Port of ArgueWinTests/LiveActivityTests.swift (the state half: the court-session content state, copy, planner),
// NotificationPrefsTests, PushPayloadTests and PermissionRoutingTests (CONTRACTS-v2 amendment o).
package app.plead.android.services

import app.plead.android.models.CaseStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private val stateJson = PleadCaseActivityAttributes.json

private fun decodeState(raw: String): CourtSessionState = stateJson.decodeFromString(ContentStateSerializer, raw)

class LiveActivityJSONTests {
    @Test fun contentStateDecodesBackendPayload() {
        val state = decodeState("""{"phase":"summoned","headline":"You've been summoned","detail":"The court awaits your plea","deadlineAt":"2026-09-25T10:00:00Z"}""")
        assertEquals(CourtSessionPhase.summoned, state.phase)
        assertEquals("You've been summoned", state.headline)
        assertEquals("The court awaits your plea", state.detail)
        assertEquals(Instant.ofEpochSecond(1_790_330_400), state.deadlineAt)
    }

    @Test fun contentStateAcceptsEveryPhaseAndDateShape() {
        for (phase in listOf("summoned", "pleaEntered", "deliberating", "verdictReady", "ended")) {
            val s = decodeState("""{"phase":"$phase","headline":"x"}""")
            assertEquals(phase, s.phase.name)
            assertTrue(s.deadlineAt == null && s.detail == null)
        }
        val unix = decodeState("""{"phase":"deliberating","headline":"x","deadlineAt":1790330400}""")
        assertEquals(Instant.ofEpochSecond(1_790_330_400), unix.deadlineAt)
        val fractional = decodeState("""{"phase":"deliberating","headline":"x","deadlineAt":"2026-09-25T10:00:00.250+00:00"}""")
        assertEquals(1_790_330_400_250L, fractional.deadlineAt!!.toEpochMilli())
        // Unknown future phases never crash.
        assertEquals(CourtSessionPhase.ended, decodeState("""{"phase":"appeal","headline":"x"}""").phase)
    }

    @Test fun contentStateRoundTripsWithBackendKeys() {
        val deadline = Instant.ofEpochSecond(1_790_330_400)
        val state = CourtSessionState(phase = CourtSessionPhase.verdictReady, headline = "The judge has ruled", detail = "Your verdict is ready", deadlineAt = deadline)
        val text = stateJson.encodeToString(ContentStateSerializer, state)
        val keys = (stateJson.parseToJsonElement(text) as JsonObject).keys
        assertEquals(setOf("phase", "headline", "detail", "deadlineAt"), keys)
        assertEquals(state, decodeState(text))
    }

    @Test fun attributesUseBackendKeys() {
        val id = UUID.randomUUID()
        val attrs = PleadCaseActivityAttributes(caseId = id, caseNumber = 21, kind = PleadCaseActivityAttributes.Kind.verdict)
        val obj = stateJson.parseToJsonElement(stateJson.encodeToString(PleadCaseActivityAttributes.serializer(), attrs)) as JsonObject
        assertEquals(setOf("caseId", "caseNumber", "kind"), obj.keys)
        assertEquals("verdict", (obj["kind"] as JsonPrimitive).content)
        val back = stateJson.decodeFromString(PleadCaseActivityAttributes.serializer(), """{"caseId":"${id.uuidString}","caseNumber":21,"kind":"summons"}""")
        assertTrue(back.caseId == id && back.caseNumber == 21 && back.kind == PleadCaseActivityAttributes.Kind.summons)
    }

    @Test fun copyAndLinks() {
        val id = UUID.fromString("4F2A4C6E-1111-4222-8333-444455556666")
        assertEquals("You've been summoned", PleadActivityCopy.headline(CourtSessionPhase.summoned))
        assertEquals("The court awaits your plea", PleadActivityCopy.genericDetail(CourtSessionPhase.summoned))
        assertEquals("The court is deliberating", PleadActivityCopy.headline(CourtSessionPhase.deliberating))
        assertEquals("The judge has ruled", PleadActivityCopy.headline(CourtSessionPhase.verdictReady))
        assertEquals("Your verdict is ready", PleadActivityCopy.genericDetail(CourtSessionPhase.verdictReady))
        assertEquals("Plea entered", PleadActivityCopy.headline(CourtSessionPhase.pleaEntered))
        assertEquals("Court adjourned", PleadActivityCopy.headline(CourtSessionPhase.ended))
        assertEquals("Summoned", PleadActivityCopy.shortWord(CourtSessionPhase.summoned))
        assertEquals("Deliberating", PleadActivityCopy.shortWord(CourtSessionPhase.deliberating))
        assertEquals("Verdict", PleadActivityCopy.shortWord(CourtSessionPhase.verdictReady))
        val base = "plead://case/4f2a4c6e-1111-4222-8333-444455556666"
        assertEquals("$base/plea", PleadActivityCopy.link(id, PleadCaseActivityAttributes.Kind.summons, CourtSessionPhase.summoned))
        assertEquals("$base/deliberation", PleadActivityCopy.link(id, PleadCaseActivityAttributes.Kind.verdict, CourtSessionPhase.deliberating))
        assertEquals("$base/verdict", PleadActivityCopy.link(id, PleadCaseActivityAttributes.Kind.verdict, CourtSessionPhase.verdictReady))
    }

    @Test fun privacyGenericUnlessDetailed() {
        val generic = PleadActivityCopy.state(CourtSessionPhase.summoned, null, "The Dinner Incident", detailed = false)
        assertEquals("The court awaits your plea", generic.detail)
        val detailed = PleadActivityCopy.state(CourtSessionPhase.summoned, null, "The Dinner Incident", detailed = true)
        assertEquals("The Dinner Incident", detailed.detail)
        // No countdown once the moment has passed.
        assertNull(PleadActivityCopy.state(CourtSessionPhase.verdictReady, Instant.now()).deadlineAt)
    }

    @Test fun registrationBody() {
        val caseId = UUID.randomUUID()
        val body = LiveActivityRegistration(LiveActivityRegistration.Kind.update, "abcd", "A1", caseId, "development").body()
        assertEquals("update", (body["kind"] as JsonPrimitive).content)
        assertEquals("abcd", (body["token"] as JsonPrimitive).content)
        assertEquals("A1", (body["activity_id"] as JsonPrimitive).content)
        assertEquals(caseId, parseUUID((body["case_id"] as JsonPrimitive).content))
        assertEquals("development", (body["environment"] as JsonPrimitive).content)
        val start = LiveActivityRegistration(LiveActivityRegistration.Kind.pushToStart, "ff").body()
        assertEquals(setOf("kind", "token", "environment"), start.keys)
        assertEquals("push_to_start", (start["kind"] as JsonPrimitive).content)
    }
}

class LiveActivityPlannerTests {
    private val now: Instant = Instant.ofEpochSecond(1_790_000_000)
    private val caseId: UUID = UUID.randomUUID()
    private val P = LiveActivityPlanner
    private val summons = PleadCaseActivityAttributes.Kind.summons
    private val verdict = PleadCaseActivityAttributes.Kind.verdict

    private fun input(
        status: CaseStatus, defendant: Boolean = true, createdAgo: Long = 600, readingIn: Long? = null,
        revealedAgo: Long? = null, opened: Boolean = false, settlement: Boolean = false,
    ) = LiveActivityPlanner.CaseInput(
        caseId = caseId, caseNumber = 21, title = "The Dinner Incident", status = status, iAmDefendant = defendant,
        settlementPending = settlement, createdAt = now.minusSeconds(createdAgo),
        deadlineAt = now.plusSeconds(20 * 3600), readingAt = readingIn?.let { now.plusSeconds(it) },
        revealedAt = revealedAgo?.let { now.minusSeconds(it) }, verdictOpened = opened,
    )

    private fun running(kind: PleadCaseActivityAttributes.Kind, phase: CourtSessionPhase, startedAgo: Long = 600, id: String = "A", stale: Boolean = false) =
        LiveActivityPlanner.Running(activityId = id, caseId = caseId, kind = kind, phase = phase, startedAt = now.minusSeconds(startedAgo), isStale = stale)

    private fun plan(cases: List<LiveActivityPlanner.CaseInput>, running: List<LiveActivityPlanner.Running> = emptyList(), allowStart: Boolean = true, finished: Set<String> = emptySet()) =
        P.plan(cases, running, now, allowStart, finished)

    @Test fun summonsStartsForTheDefendantOnly() {
        val actions = plan(listOf(input(CaseStatus.summoned)))
        val start = actions.first() as LiveActivityPlanner.Action.start
        assertTrue(actions.size == 1 && start.caseId == caseId && start.caseNumber == 21 && start.kind == summons)
        assertTrue(start.state.phase == CourtSessionPhase.summoned && start.state.headline == "You've been summoned")
        assertEquals(now.plusSeconds(20 * 3600), start.state.deadlineAt)
        assertTrue(plan(listOf(input(CaseStatus.summoned, defendant = false))).isEmpty())
        assertTrue(plan(listOf(input(CaseStatus.summoned, settlement = true))).isEmpty())
    }

    @Test fun noLocalStartInBackgroundOrWhenFinishedOrStale() {
        assertTrue(plan(listOf(input(CaseStatus.summoned)), allowStart = false).isEmpty())
        assertTrue(plan(listOf(input(CaseStatus.summoned)), finished = setOf(P.key(caseId, summons))).isEmpty())
        assertTrue(plan(listOf(input(CaseStatus.summoned, createdAgo = 25 * 3600))).isEmpty())
    }

    @Test fun summonsRunningStaysThenEndsOnPlea() {
        assertTrue(plan(listOf(input(CaseStatus.summoned)), listOf(running(summons, CourtSessionPhase.summoned))).isEmpty())
        val ended = plan(listOf(input(CaseStatus.defence)), listOf(running(summons, CourtSessionPhase.summoned)))
        val end = ended.first() as LiveActivityPlanner.Action.end
        assertTrue(end.activityId == "A" && end.reason == LiveActivityPlanner.EndReason.pleaEntered && end.state.phase == CourtSessionPhase.pleaEntered)
        // Plea entered while the app was closed: no restart.
        assertTrue(plan(listOf(input(CaseStatus.defence))).isEmpty())
    }

    @Test fun summonsEndsAfter24Hours() {
        val actions = plan(listOf(input(CaseStatus.summoned)), listOf(running(summons, CourtSessionPhase.summoned, startedAgo = 24 * 3600 + 60)))
        assertEquals(1, actions.size)
        assertEquals(LiveActivityPlanner.EndReason.expired, (actions.first() as LiveActivityPlanner.Action.end).reason)
    }

    @Test fun verdictStartsWithinOneHourOfTheReading() {
        assertTrue(plan(listOf(input(CaseStatus.deliberating, readingIn = 2 * 3600))).isEmpty())
        val start = plan(listOf(input(CaseStatus.awaitingVerdict, readingIn = 40 * 60))).first() as LiveActivityPlanner.Action.start
        assertTrue(start.kind == verdict && start.state.phase == CourtSessionPhase.deliberating && start.state.deadlineAt == now.plusSeconds(40 * 60))
        // No schedule: nothing to count down to.
        assertTrue(plan(listOf(input(CaseStatus.deliberating))).isEmpty())
    }

    @Test fun verdictUpdatesOnRevealThenEndsWhenOpenedOrAfterTwoHours() {
        val update = plan(listOf(input(CaseStatus.verdict, revealedAgo = 60)), listOf(running(verdict, CourtSessionPhase.deliberating)))
        val u = update.first() as LiveActivityPlanner.Action.update
        assertTrue(update.size == 1 && u.activityId == "A" && u.state.phase == CourtSessionPhase.verdictReady && u.state.headline == "The judge has ruled")
        // Already verdictReady: no churn.
        assertTrue(plan(listOf(input(CaseStatus.verdict, revealedAgo = 60)), listOf(running(verdict, CourtSessionPhase.verdictReady))).isEmpty())
        val opened = plan(listOf(input(CaseStatus.verdict, revealedAgo = 60, opened = true)), listOf(running(verdict, CourtSessionPhase.verdictReady)))
        assertEquals(LiveActivityPlanner.EndReason.verdictOpened, (opened.first() as LiveActivityPlanner.Action.end).reason)
        val late = plan(listOf(input(CaseStatus.closed, revealedAgo = 2 * 3600 + 60)), listOf(running(verdict, CourtSessionPhase.verdictReady)))
        assertEquals(LiveActivityPlanner.EndReason.expired, (late.first() as LiveActivityPlanner.Action.end).reason)
        // A revealed verdict never starts a new session locally.
        assertTrue(plan(listOf(input(CaseStatus.verdict, revealedAgo = 60))).isEmpty())
    }

    @Test fun staleAndOrphanedActivitiesEndOnReconcile() {
        val orphan = plan(emptyList(), listOf(running(summons, CourtSessionPhase.summoned)))
        assertEquals(LiveActivityPlanner.EndReason.caseMissing, (orphan.first() as LiveActivityPlanner.Action.end).reason)
        val settled = plan(listOf(input(CaseStatus.closedSettled)), listOf(running(verdict, CourtSessionPhase.deliberating)))
        assertEquals(LiveActivityPlanner.EndReason.caseClosed, (settled.first() as LiveActivityPlanner.Action.end).reason)
        // Duplicate sessions for the same case/kind: keep the newest.
        val dupes = plan(
            listOf(input(CaseStatus.summoned)),
            listOf(running(summons, CourtSessionPhase.summoned, startedAgo = 100, id = "new"), running(summons, CourtSessionPhase.summoned, startedAgo = 900, id = "old")),
        )
        assertEquals(listOf(LiveActivityPlanner.Action.end("old", P.finalState(CourtSessionPhase.summoned), LiveActivityPlanner.EndReason.duplicate)), dupes)
        // Hard cap, whatever the case says.
        val ancient = plan(listOf(input(CaseStatus.deliberating, readingIn = 600)), listOf(running(verdict, CourtSessionPhase.deliberating, startedAgo = 25 * 3600)))
        assertEquals(LiveActivityPlanner.EndReason.expired, (ancient.first() as LiveActivityPlanner.Action.end).reason)
    }

    @Test fun summonsActivityEndsOnPlea() {
        for (status in listOf(CaseStatus.defence, CaseStatus.trial)) {
            val actions = plan(listOf(input(status)), listOf(running(summons, CourtSessionPhase.summoned)))
            assertEquals(
                "status $status",
                listOf(LiveActivityPlanner.Action.end("A", PleadActivityCopy.state(CourtSessionPhase.pleaEntered, null, "The Dinner Incident"), LiveActivityPlanner.EndReason.pleaEntered)),
                actions,
            )
        }
        assertTrue(plan(listOf(input(CaseStatus.summoned)), listOf(running(summons, CourtSessionPhase.summoned, startedAgo = 23 * 3600))).isEmpty())
    }

    @Test fun verdictOpenedEndsTheVerdictActivityWithinTwoHours() {
        for (revealedAgo in listOf(30L, 60L * 60, 2L * 3600 - 60)) {
            val opened = plan(listOf(input(CaseStatus.verdict, revealedAgo = revealedAgo, opened = true)), listOf(running(verdict, CourtSessionPhase.verdictReady)))
            assertEquals(1, opened.size)
            val end = opened.first() as LiveActivityPlanner.Action.end
            assertTrue(end.activityId == "A" && end.reason == LiveActivityPlanner.EndReason.verdictOpened && end.state.phase == CourtSessionPhase.verdictReady)
            assertTrue(plan(listOf(input(CaseStatus.verdict, revealedAgo = revealedAgo)), listOf(running(verdict, CourtSessionPhase.verdictReady))).isEmpty())
        }
        val direct = plan(listOf(input(CaseStatus.verdict, revealedAgo = 10, opened = true)), listOf(running(verdict, CourtSessionPhase.deliberating)))
        assertEquals(1, direct.size)
        assertEquals(LiveActivityPlanner.EndReason.verdictOpened, (direct.first() as LiveActivityPlanner.Action.end).reason)
        val unopened = plan(listOf(input(CaseStatus.verdict, revealedAgo = 2 * 3600 + 1)), listOf(running(verdict, CourtSessionPhase.verdictReady)))
        assertEquals(LiveActivityPlanner.EndReason.expired, (unopened.first() as LiveActivityPlanner.Action.end).reason)
        assertTrue(plan(listOf(input(CaseStatus.verdict, revealedAgo = 10, opened = true))).isEmpty())
    }

    @Test fun launchReconcileEndsStaleActivities() {
        val stale = running(summons, CourtSessionPhase.summoned, startedAgo = 600, stale = true)
        val actions = plan(listOf(input(CaseStatus.summoned)), listOf(stale), allowStart = false)
        assertEquals(1, actions.size)
        val end = actions.first() as LiveActivityPlanner.Action.end
        assertTrue(end.reason == LiveActivityPlanner.EndReason.expired && end.state.phase == CourtSessionPhase.pleaEntered)
        assertEquals(1, plan(listOf(input(CaseStatus.summoned)), listOf(stale)).size)
        val staleVerdict = running(verdict, CourtSessionPhase.deliberating, stale = true)
        val v = plan(listOf(input(CaseStatus.deliberating, readingIn = -3 * 3600)), listOf(staleVerdict))
        assertEquals(1, v.size)
        val ve = v.first() as LiveActivityPlanner.Action.end
        assertTrue(ve.reason == LiveActivityPlanner.EndReason.expired && ve.state.phase == CourtSessionPhase.ended)
    }

    @Test fun staleDates() {
        val s = PleadActivityCopy.state(CourtSessionPhase.summoned, null)
        assertEquals(now.plusSeconds(24 * 3600), P.staleDate(summons, s, now))
        val d = PleadActivityCopy.state(CourtSessionPhase.deliberating, now.plusSeconds(1800))
        assertEquals(now.plusSeconds(1800 + 2 * 3600), P.staleDate(verdict, d, now))
    }
}

class NotificationPrefsTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun defaultsArePrivacyFirst() {
        val p = NotificationPrefs.defaults
        assertTrue(p.summons && p.verdict && p.settlement && p.reminders)
        assertFalse(p.lockscreenDetails)
    }

    @Test fun encodesBackendKeys() {
        val p = NotificationPrefs(summons = false, verdict = true, settlement = true, reminders = false, lockscreenDetails = true)
        val obj = JSONCoding.json.encodeToJsonElement(NotificationPrefs.serializer(), p) as JsonObject
        assertEquals(
            mapOf("summons" to false, "verdict" to true, "settlement" to true, "reminders" to false, "lockscreen_details" to true),
            obj.mapValues { (it.value as JsonPrimitive).content.toBoolean() },
        )
    }

    @Test fun decodesWithAndWithoutSnakeConversion() {
        val json = """{"summons":false,"verdict":true,"settlement":false,"reminders":true,"lockscreen_details":true}"""
        val expected = NotificationPrefs(summons = false, verdict = true, settlement = false, reminders = true, lockscreenDetails = true)
        assertEquals(expected, JSONCoding.json.decodeFromString(NotificationPrefs.serializer(), json))
        assertEquals(expected, JSONCoding.json.decodeFromString(NotificationPrefs.serializer(), json.replace("lockscreen_details", "lockscreenDetails")))
        // Missing keys → defaults; a PostgREST row wrapping the column decodes too.
        assertEquals(NotificationPrefs.defaults, JSONCoding.json.decodeFromString(NotificationPrefs.serializer(), "{}"))
        val row = JSONCoding.json.parseToJsonElement("""[{"notification_prefs":{"lockscreen_details":true}}]""") as kotlinx.serialization.json.JsonArray
        assertEquals(NotificationPrefs(lockscreenDetails = true), (row.first() as JsonObject).lenient("notification_prefs", NotificationPrefs.serializer()))
    }

    @Test fun togglesMapToPrefs() {
        assertEquals(listOf("summons", "verdict", "settlement", "reminders", "lockscreen_details"), NotificationPrefs.Toggle.entries.map { it.jsonKey })
        assertEquals(
            listOf("Summons and required actions", "Verdict and judgement", "Settlement updates", "Deadline reminders", "Show case details on Lock Screen"),
            NotificationPrefs.Toggle.entries.map { it.title },
        )
        var p = NotificationPrefs.defaults
        for (t in NotificationPrefs.Toggle.entries) {
            val flipped = p.setting(t, !p[t])
            assertEquals(!p[t], flipped[t])
            for (other in NotificationPrefs.Toggle.entries) if (other != t) assertEquals(p[other], flipped[other])
            p = flipped
        }
    }

    @Test fun settingsToggleWritesProfileAndAppGroup() = runTest(main.dispatcher) {
        val defaults = UserDefaults.inMemory()
        val appGroup = UserDefaults.inMemory()
        val model = NotificationPrefsModel(defaults, appGroup)
        val written = mutableListOf<NotificationPrefs>()
        model.saver = { prefs -> written.add(prefs) }
        model.set(NotificationPrefs.Toggle.lockscreenDetails, true)
        assertTrue(model.prefs.lockscreenDetails)
        assertTrue(appGroup.bool("lockscreenDetails"))
        assertEquals(listOf(NotificationPrefs(lockscreenDetails = true)), written)
        assertTrue(NotificationPrefs.cached(defaults).lockscreenDetails)

        // A failed write reverts that toggle only.
        model.saver = { throw java.io.IOException("offline") }
        model.set(NotificationPrefs.Toggle.reminders, false)
        assertTrue(model.prefs.reminders && model.prefs.lockscreenDetails)
        assertNotNull(model.errorMessage)

        // Load from the profile; sign-out resets to defaults (and the widgets go generic).
        model.loader = { NotificationPrefs(summons = false) }
        model.load()
        assertTrue(!model.prefs.summons && !model.prefs.lockscreenDetails)
        model.reset()
        assertTrue(model.prefs == NotificationPrefs.defaults && !appGroup.bool("lockscreenDetails"))
    }
}

class PushPayloadTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun dataLinkWinsOverLegacyScreen() {
        val id = UUID.randomUUID()
        val info: Map<String, Any?> = mapOf(
            "aps" to mapOf("alert" to mapOf("title" to "All rise"), "category" to "verdict", "thread-id" to id.uuidString),
            "data" to mapOf("link" to "https://plead-drab.vercel.app/case/${id.uuidString}?screen=verdict", "case_id" to id.uuidString, "screen" to "court"),
        )
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.verdict)), PushService.link(info))
        val legacy: Map<String, Any?> = mapOf("case_id" to id.uuidString, "screen" to "summons")
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.summons)), PushService.link(legacy))
        assertEquals("verdict", PushService.analyticsProps(info)["category"])
    }

    @Test fun silentPushDetection() {
        assertTrue(PushService.isSilent(mapOf("aps" to mapOf("content-available" to 1))))
        assertFalse(PushService.isSilent(mapOf("aps" to mapOf("content-available" to 1, "alert" to "x"))))
        assertFalse(PushService.isSilent(mapOf("aps" to mapOf("alert" to "x"))))
        // FCM data-only message.
        assertTrue(PushService.isSilent(mapOf("content_available" to "1")))
        assertFalse(PushService.isSilent(mapOf("content_available" to "1", "title" to "All rise")))
    }

    /** iOS categories have no lock-screen actions; Android: one channel per category, no actions either. */
    @Test fun categoriesHaveNoLockScreenActions() {
        assertTrue(PushCategory.entries.any { it.rawValue == "summons" })
        assertEquals("Court notice", PushCategory.hiddenPreviewsBodyPlaceholder)
    }

    @Test fun permissionAnalytics() {
        assertEquals("notification_permission_granted", NotificationPermissionService.analyticsEvent(NotificationStatus.notDetermined, NotificationStatus.authorized))
        assertEquals("notification_permission_denied", NotificationPermissionService.analyticsEvent(NotificationStatus.notDetermined, NotificationStatus.denied))
        assertEquals("notification_permission_denied", NotificationPermissionService.analyticsEvent(NotificationStatus.authorized, NotificationStatus.denied))
        assertEquals("notification_permission_granted", NotificationPermissionService.analyticsEvent(NotificationStatus.denied, NotificationStatus.authorized))
        assertNull(NotificationPermissionService.analyticsEvent(NotificationStatus.authorized, NotificationStatus.authorized))
        assertNull(NotificationPermissionService.analyticsEvent(NotificationStatus.authorized, NotificationStatus.provisional))
    }
}

/** Port of OnboardingTests.PermissionRoutingTests (the service half). */
class PermissionRoutingTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun notificationCTA() {
        assertEquals(PermissionCTA.requestNative, NotificationPermissionService.cta(NotificationStatus.notDetermined))
        for (s in listOf(NotificationStatus.denied, NotificationStatus.authorized, NotificationStatus.provisional, NotificationStatus.ephemeral)) {
            assertEquals(PermissionCTA.continueOnly, NotificationPermissionService.cta(s))
        }
        assertTrue(NotificationStatus.provisional.isAllowed)
        assertFalse(NotificationStatus.denied.isAllowed)
    }

    @Test fun statusesPersistUnderTheBriefsKeys() {
        assertEquals("notification_status", NotificationPermissionService.storageKey)
        val d = UserDefaults.inMemory()
        d.set("denied", "notification_status")
        assertEquals(NotificationStatus.denied, NotificationPermissionService(push = null, defaults = d, context = null).status)
    }

    /** Amendment az: no ATT on Android — always determined, so the Privacy step's CTA just continues. */
    @Test fun trackingIsNeverAPromptOnAndroid() = kotlinx.coroutines.test.runTest(main.dispatcher) {
        val d = UserDefaults.inMemory()
        val tracking = TrackingPermissionService(d)
        assertEquals(TrackingStatus.authorized, tracking.status)
        assertEquals(PermissionCTA.continueOnly, TrackingPermissionService.cta(tracking.status))
        var answered: TrackingStatus? = null
        tracking.onAnswer = { answered = it }
        assertEquals(TrackingStatus.authorized, tracking.request())
        assertEquals(TrackingStatus.authorized, answered)
        assertEquals("authorized", d.string("att_status"))
    }
}
