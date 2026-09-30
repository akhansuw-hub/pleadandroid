// Port of ArgueWinTests/SecurityAlignmentTests.swift (backend security fixes 20260924000600…000900): profile column
// grants + `profiles_public`, `register_push` as the only time-zone / token writer, `join_couple` rate limit,
// `leave_couple` closing verdict cases, outage verdicts. `VerdictCard.isProvisional`, `CaseDetailView.defenceEvidenceSealed`
// and `Countdown.clock` are feature / design-system subjects (waves 2b, 3d) and stay with those waves.
package app.plead.android.services

import app.plead.android.models.Avatar
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Couple
import app.plead.android.models.EdgeError
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.Profile
import app.plead.android.models.Verdict
import app.plead.android.models.VerdictKind
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

private object Fixture {
    val me: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val partner: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val couple: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val oldCouple: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
    val closedCase: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    val mistrialCase: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002")

    fun profile(id: UUID, name: String, couple: UUID?, extra: String = ""): String =
        """{"id":"$id","display_name":"$name","avatar_json":{},""" +
            """"couple_id":${couple?.let { "\"$it\"" } ?: "null"},""" +
            """"partner_name_temp":null,"onboarding_completed_at":"2026-09-02T10:00:00+00:00","deleted_at":null,""" +
            """"created_at":"2026-09-01T10:00:00+00:00"$extra}"""

    fun caseRow(id: UUID, status: String, couple: UUID = oldCouple): String =
        """{"id":"$id","couple_id":"$couple","case_number":3,"title":"The Thermostat",""" +
            """"plaintiff_id":"$me","defendant_id":"$partner","status":"$status",""" +
            """"phase":null,"phase_turn_owner":null,"charge":"c","remedy_requested":"r","proposal_count":0,"panel_progress":4,""" +
            """"created_at":"2026-09-10T10:00:00+00:00","updated_at":"2026-09-11T10:00:00+00:00","closed_at":"2026-09-24T09:00:00+00:00"}"""

    const val verdictRow = """{"id":"aaaaaaaa-0000-0000-0000-000000000301","case_id":"aaaaaaaa-0000-0000-0000-000000000001","kind":"ruling",
     "winner_id":"22222222-2222-2222-2222-222222222222","is_tie":false,"recap":"r","findings":[],"sentence":"s",
     "closing_line":"c","panel_round":1,"is_fallback":true,"created_at":"2026-09-20T10:00:00+00:00"}"""
}

// MARK: - Profiles: view reads, explicit column lists

class ProfileServiceSecurityTests {
    @get:Rule val main = MainDispatcherRule()
    private val columns = "id,display_name,avatar_json,couple_id,partner_name_temp,onboarding_completed_at,deleted_at,created_at"

    @Test fun columnListHasNoForbiddenColumns() {
        assertEquals(columns, ProfileService.columns)
        for (forbidden in listOf("push_token", "timezone", "*")) assertFalse(ProfileService.columns.contains(forbidden))
        assertEquals("$columns,past_couple_ids", ProfileService.ownColumns)
        assertEquals("profiles_public", ProfileService.readSource)
    }

    @Test fun ownProfileIsReadFromTheViewWithExplicitColumns() = runTest(main.dispatcher) {
        StubSupabase.install {
            200 to "[" + Fixture.profile(Fixture.me, "Sam", Fixture.couple, extra = ""","past_couple_ids":["44444444-4444-4444-4444-444444444444"]""") + "]"
        }
        val service = ProfileService(StubSupabase.client())
        val own = requireNotNull(service.fetchOwnProfile(Fixture.me))
        assertEquals(Fixture.couple, own.profile.coupleId)
        assertNotNull(own.profile.onboardingCompletedAt)
        assertTrue(own.profile.timezone == null && own.profile.pushToken == null)
        assertEquals(listOf(Fixture.oldCouple), own.pastCoupleIds)

        val req = StubSupabase.requests.first()
        assertEquals("/rest/v1/profiles_public", req.path)
        assertEquals(ProfileService.ownColumns, req.query("select"))
        assertEquals("eq.${Fixture.me.toString().lowercase()}", req.query("id"))
    }

    @Test fun partnerIsReadFromTheViewAndToleratesMissingColumns() = runTest(main.dispatcher) {
        // A view row may carry nulls (ex-partner shape) or omit columns entirely: both decode.
        StubSupabase.install {
            200 to """[{"id":"22222222-2222-2222-2222-222222222222","display_name":"Alex","avatar_json":{},"created_at":"2026-09-01T10:00:00+00:00"}]"""
        }
        val service = ProfileService(StubSupabase.client())
        val partner = requireNotNull(service.fetchPartner(Fixture.couple, Fixture.me))
        assertEquals("Alex", partner.displayName)
        assertNull(partner.coupleId)
        assertNull(partner.onboardingCompletedAt)

        val req = StubSupabase.requests.first()
        assertEquals("/rest/v1/profiles_public", req.path)
        assertEquals(columns, req.query("select"))
        assertEquals("eq.${Fixture.couple}", req.query("couple_id"))
        assertEquals("neq.${Fixture.me}", req.query("id"))
    }

    @Test fun upsertUpdatesThenInsertsWithoutTimezone() = runTest(main.dispatcher) {
        StubSupabase.install { req ->
            when (req.method) {
                "PATCH" -> 200 to "[]" // no row yet
                "POST" -> 201 to Fixture.profile(Fixture.me, "Sam", null)
                else -> 500 to "{}"
            }
        }
        val service = ProfileService(StubSupabase.client())
        val row = service.upsertProfile(Fixture.me, "Sam", Avatar.default)
        assertEquals("Sam", row.displayName)

        val reqs = StubSupabase.requests
        assertEquals(listOf("PATCH", "POST"), reqs.map { it.method })
        for (req in reqs) {
            assertEquals("/rest/v1/profiles", req.path)
            assertEquals(columns, req.query("select"))
            assertNull(req.json?.get("timezone"))
            assertNull(req.json?.get("push_token"))
        }
        assertEquals(setOf("display_name", "avatar_json"), reqs[0].json?.keys)
        assertEquals(setOf("id", "display_name", "avatar_json"), reqs[1].json?.keys)
        assertNull(reqs[0].query("on_conflict"))
    }

    @Test fun existingRowIsOnlyPatched() = runTest(main.dispatcher) {
        StubSupabase.install { 200 to "[" + Fixture.profile(Fixture.me, "Sam", null) + "]" }
        val service = ProfileService(StubSupabase.client())
        service.upsertProfile(Fixture.me, "Sam", Avatar.default)
        assertEquals(listOf("PATCH"), StubSupabase.requests.map { it.method })
    }

    @Test fun everyProfileWriteSelectsTheExplicitColumns() = runTest(main.dispatcher) {
        StubSupabase.install { 200 to Fixture.profile(Fixture.me, "Sam", null) }
        val service = ProfileService(StubSupabase.client())
        service.setAvatar(Fixture.me, Avatar.default)
        service.setPartnerNameTemp(Fixture.me, "Alex")
        service.markOnboardingCompleted(Fixture.me)
        val reqs = StubSupabase.requests
        assertEquals(3, reqs.size)
        for (req in reqs) {
            assertEquals("PATCH", req.method)
            assertEquals("/rest/v1/profiles", req.path)
            assertEquals(columns, req.query("select"))
        }
        // An explicit null clears the temporary name.
        service.setPartnerNameTemp(Fixture.me, null)
        assertEquals(JsonNull, StubSupabase.requests.last().json?.get("partner_name_temp"))
    }
}

// MARK: - CaseStore: history after an unlink

class CaseStoreSecurityTests {
    @get:Rule val main = MainDispatcherRule()

    /**
     * After `leave_couple` I have no couple; the old couple's cases load from `past_couple_ids`, the
     * verdict case shows as closed with its (provisional) verdict, and the ex-partner's name comes
     * from `profiles_public`.
     */
    @Test fun unlinkedHistoryShowsClosedCasesWithTheirVerdict() = runTest(main.dispatcher) {
        StubSupabase.install { req ->
            when (req.path) {
                "/rest/v1/profiles_public" ->
                    if (req.query("id")?.startsWith("eq.") == true) {
                        200 to "[" + Fixture.profile(Fixture.me, "Sam", null, extra = ""","past_couple_ids":["44444444-4444-4444-4444-444444444444"]""") + "]"
                    } else {
                        200 to """[{"id":"22222222-2222-2222-2222-222222222222","display_name":"Alex","avatar_json":{},"couple_id":null,"created_at":"2026-09-01T10:00:00+00:00"}]"""
                    }
                "/rest/v1/cases" -> 200 to "[" + Fixture.caseRow(Fixture.closedCase, "closed") + "," + Fixture.caseRow(Fixture.mistrialCase, "mistrial") + "]"
                "/rest/v1/verdicts" -> 200 to "[" + Fixture.verdictRow + "]"
                else -> 200 to "[]"
            }
        }
        val store = CaseStore(CaseStore.Backend(StubSupabase.client()))
        store.load(Fixture.me)

        assertNull(store.loadError)
        assertNull(store.me?.coupleId)
        assertTrue(store.couple == null && store.partner == null)
        assertEquals(listOf(Fixture.oldCouple), store.pastCoupleIds)
        assertEquals(2, store.closedCases.size)
        assertTrue(store.openCases.isEmpty())
        val closed = requireNotNull(store.caseById(Fixture.closedCase))
        assertEquals(CaseStatus.closed, closed.status)
        assertEquals(true, store.verdict(closed.id)?.isFallback)
        assertEquals(CaseOutcome.lost, store.outcome(closed))
        assertEquals("Alex won", store.outcomeLine(closed))
        assertEquals("Alex won", store.ribbonTitle(closed))
        assertEquals("Mistrial", store.outcomeLine(requireNotNull(store.caseById(Fixture.mistrialCase))))

        val reqs = StubSupabase.requests
        assertFalse("profiles is never read directly", reqs.any { it.path == "/rest/v1/profiles" })
        val caseQuery = reqs.first { it.path == "/rest/v1/cases" }
        assertEquals("in.(${Fixture.oldCouple})", caseQuery.query("couple_id"))
        store.reset()
    }

    @Test fun tallyCountsOnlyTheCurrentCouple() {
        val me = Profile(id = Fixture.me, displayName = "Sam", coupleId = Fixture.couple)
        val couple = Couple(id = Fixture.couple, inviteCode = "ABCDEF", inviteExpiresAt = Instant.now(), linkedAt = Instant.now())
        fun kase(couple: UUID) = Case(
            id = UUID.randomUUID(), coupleId = couple, caseNumber = 1, title = "t", plaintiffId = Fixture.me, defendantId = Fixture.partner,
            status = CaseStatus.closed, charge = "c", remedyRequested = "r",
        )
        val current = kase(Fixture.couple)
        val old = kase(Fixture.oldCouple)
        fun win(c: Case) = Verdict(
            id = UUID.randomUUID(), caseId = c.id, kind = VerdictKind.ruling, winnerId = Fixture.me, isTie = false, recap = "",
            findings = emptyList(), sentence = "", closingLine = "",
        )
        val store = CaseStore(preview = me, partner = null, couple = couple, cases = listOf(current, old), verdicts = listOf(win(current), win(old)))
        assertEquals(1, store.winTally.mine)
        assertEquals(2, store.closedCases.size)
    }
}

// MARK: - Edge functions: register_push, join_couple 429, leave_couple

class EdgeFunctionSecurityTests {
    @get:Rule val main = MainDispatcherRule()

    private fun body(r: PushRegistration): String = JSONCoding.json.encodeToString(JsonObject.serializer(), JsonObject(r.body().toSortedMap()))

    /** Amendment az: an Android token carries `platform: "fcm"`; the zone-only and clear bodies are unchanged. */
    @Test fun registerPushBodies() {
        assertEquals("""{"timezone":"Europe/London"}""", body(PushRegistration.timezone("Europe/London")))
        assertEquals("""{"platform":"fcm","timezone":"Europe/London","token":"abcd"}""", body(PushRegistration.token("abcd", "Europe/London")))
        assertEquals("""{"token":null}""", body(PushRegistration.clear))
    }

    @Test fun registerPushSendsTheBodyAndDecodesLeniently() = runTest(main.dispatcher) {
        StubSupabase.install { 200 to """{"ok":true,"push":{"registered":false,"timezone":"Europe/London"}}""" }
        val edge = EdgeFunctions(StubSupabase.client())
        val result = edge.registerPush(PushRegistration.clear)
        assertEquals(false, result.push?.registered)
        assertEquals("Europe/London", result.push?.timezone)
        val req = StubSupabase.requests.first()
        assertEquals("/functions/v1/register_push", req.path)
        assertEquals(listOf("token"), req.json?.keys?.sorted())
        assertEquals(JsonNull, req.json?.get("token"))

        StubSupabase.install { 200 to """{"ok":true}""" }
        assertNull(edge.registerPush(PushRegistration.timezone("Asia/Tokyo")).push)
        StubSupabase.install { 200 to """{"ok":true,"push":{"registered":"yes"}}""" }
        assertNull(edge.registerPush(PushRegistration.timezone("Asia/Tokyo")).push)
    }

    @Test fun joinCoupleRateLimitMapsToFriendlyCopy() = runTest(main.dispatcher) {
        StubSupabase.install {
            429 to """{"ok":false,"code":"too_many_attempts","message":"Too many invite codes tried. Wait an hour, or ask your partner for a fresh link."}"""
        }
        val edge = EdgeFunctions(StubSupabase.client())
        try {
            edge.joinCouple("abcdef")
            fail("expected a 429")
        } catch (e: EdgeError) {
            assertEquals("too_many_attempts", e.code)
            assertTrue(e.isTooManyAttempts)
            assertEquals("Too many attempts. Try again in an hour.", EdgeErrors.joinMessage(e))
        }
        val req = StubSupabase.requests.first()
        assertEquals("ABCDEF", (req.json?.get("code") as JsonPrimitive).content)
    }

    @Test fun joinMessages() {
        assertEquals("Too many attempts. Try again in an hour.", EdgeErrors.joinMessage(EdgeError("http_429", "x")))
        assertEquals("That code doesn't match an open invite.", EdgeErrors.joinMessage(EdgeError("invalid_code", "x")))
        // Amendment as: couple_join's wrong_state details read as plain copy.
        assertEquals("That invite has already been used. Ask your partner for a fresh code.", EdgeErrors.joinMessage(EdgeError("wrong_state", "Already linked")))
        assertEquals("Couldn't join. Try again.", EdgeErrors.joinMessage(java.net.SocketTimeoutException()))
    }

    @Test fun joinPartnerPayloadWithSafeColumnsDecodes() = runTest(main.dispatcher) {
        StubSupabase.install {
            200 to """{"ok":true,"couple":{"id":"33333333-3333-3333-3333-333333333333","invite_code":"KX7P2Q","invite_expires_at":"2026-09-30T10:00:00+00:00","judge_persona":"wigsworth","linked_at":"2026-09-24T10:00:00+00:00","created_at":"2026-09-01T10:00:00+00:00"},"partner":{"id":"22222222-2222-2222-2222-222222222222","display_name":"Alex","avatar_json":{}}}"""
        }
        val result = EdgeFunctions(StubSupabase.client()).joinCouple("KX7P2Q")
        assertEquals(true, result.couple?.isLinked)
    }

    @Test fun leaveCoupleDecodesClosedCaseIds() = runTest(main.dispatcher) {
        StubSupabase.install {
            200 to """{"ok":true,"couple":null,"left_couple_id":"44444444-4444-4444-4444-444444444444","mistrial_case_ids":["aaaaaaaa-0000-0000-0000-000000000002"],"closed_case_ids":["aaaaaaaa-0000-0000-0000-000000000001"]}"""
        }
        val result = EdgeFunctions(StubSupabase.client()).leaveCouple()
        assertNull(result.couple)
        assertEquals(Fixture.oldCouple, result.leftCoupleId)
        assertEquals(listOf(Fixture.closedCase), result.closedCaseIds)
        assertEquals(listOf(Fixture.mistrialCase), result.mistrialCaseIds)

        StubSupabase.install { 200 to """{"ok":true}""" }
        assertTrue(EdgeFunctions(StubSupabase.client()).leaveCouple().closedCaseIds.isEmpty())
    }

    /** The `{ok:false}` envelope in a 200 is an error too, and 402 announces the paywall. */
    @Test fun okFalseEnvelopeAndPremiumRequired() = runTest(main.dispatcher) {
        StubSupabase.install { 200 to """{"ok":false,"code":"wrong_state","message":"The case has moved on."}""" }
        val edge = EdgeFunctions(StubSupabase.client())
        try {
            edge.acceptTime(Fixture.closedCase)
            fail("expected an error")
        } catch (e: EdgeError) {
            assertEquals("wrong_state", e.code)
        }
        StubSupabase.install { 402 to "Payment Required" }
        try {
            edge.fileCase("t", "c", "r", emptyList())
            fail("expected a 402")
        } catch (e: EdgeError) {
            assertTrue(e.isPremiumRequired)
        }
        val body = StubSupabase.requests.last().json
        assertEquals(setOf("title", "charge", "remedy_requested", "exhibits", "exhibit_ids"), body?.keys)
    }
}

// MARK: - register_push scheduling

private class PushHarness {
    var now: Instant = Instant.ofEpochSecond(1_790_000_000)
    var zone = "Europe/London"
    val sent = mutableListOf<PushRegistration>()
    var failNext = false
    val service: PushService = PushService(now = { now }, timeZone = { zone }, context = null).also { s ->
        s.sender = { reg ->
            sent.add(reg)
            if (failNext) {
                failNext = false
                throw EdgeError(code = "network", message = "offline")
            }
        }
    }
}

class PushSchedulingTests {
    @get:Rule val main = MainDispatcherRule()
    private val user = Fixture.me

    @Test fun launchAndForegroundCoalesceWithinSixtySeconds() = runTest(main.dispatcher) {
        val h = PushHarness()
        h.service.userChanged(user) // launch (auth resolved)
        h.service.retryPending() // activity resumed right after
        assertEquals(listOf<PushRegistration>(PushRegistration.timezone("Europe/London")), h.sent)
        h.now = h.now.plusSeconds(59)
        h.service.retryPending()
        assertEquals(1, h.sent.size)
        h.now = h.now.plusSeconds(2) // 61 s after the first call
        h.service.retryPending()
        assertEquals(listOf<PushRegistration>(PushRegistration.timezone("Europe/London"), PushRegistration.timezone("Europe/London")), h.sent)
    }

    @Test fun zoneChangeIsNotCoalesced() = runTest(main.dispatcher) {
        val h = PushHarness()
        h.service.userChanged(user)
        h.zone = "America/New_York"; h.now = h.now.plusSeconds(5)
        h.service.syncTimezone()
        assertEquals(listOf<PushRegistration>(PushRegistration.timezone("Europe/London"), PushRegistration.timezone("America/New_York")), h.sent)
    }

    @Test fun failureIsRetriedOnNextForeground() = runTest(main.dispatcher) {
        val h = PushHarness()
        h.failNext = true
        h.service.userChanged(user)
        h.now = h.now.plusSeconds(1)
        h.service.retryPending()
        assertEquals(2, h.sent.size)
    }

    @Test fun tokenCarriesTheZoneAndIsSentOnce() = runTest(main.dispatcher) {
        val h = PushHarness()
        h.service.didReceive("abc123") // before sign-in: held
        assertTrue(h.sent.isEmpty())
        h.service.userChanged(user)
        assertEquals(listOf<PushRegistration>(PushRegistration.token("abc123", "Europe/London")), h.sent) // zone sync coalesced into it
        h.service.didReceive("abc123")
        assertEquals(1, h.sent.size)
        h.service.didReceive("def456") // rotated
        assertEquals(PushRegistration.token("def456", "Europe/London"), h.sent.last())
    }

    @Test fun signOutClearsTheTokenAndStopsSending() = runTest(main.dispatcher) {
        val h = PushHarness()
        h.service.userChanged(user)
        h.service.didReceive("abc123")
        h.service.unregister()
        assertEquals(PushRegistration.clear, h.sent.last())
        val count = h.sent.size
        h.now = h.now.plusSeconds(120)
        h.service.retryPending() // foreground before the auth event lands
        h.service.didReceive("abc123")
        assertEquals(count, h.sent.size)
        h.service.userChanged(null)
        h.service.retryPending()
        assertEquals(count, h.sent.size)
        // The next account on this device registers the token again.
        h.service.userChanged(Fixture.partner)
        assertEquals(PushRegistration.token("abc123", "Europe/London"), h.sent.last())
    }

    @Test fun noClearWhenSignedOut() = runTest(main.dispatcher) {
        val h = PushHarness()
        h.service.unregister()
        assertTrue(h.sent.isEmpty())
    }

    @Test fun failedDeletionResumes() = runTest(main.dispatcher) {
        val h = PushHarness()
        h.service.userChanged(user)
        h.service.didReceive("abc123")
        h.service.unregister()
        h.service.resume()
        assertEquals(PushRegistration.token("abc123", "Europe/London"), h.sent.last())
    }

    @Test fun sendsTimezoneWhateverTheNotificationPermission() = runTest(main.dispatcher) {
        // Nothing in the scheduling consults `authorization`: denied users still get quiet hours right.
        val h = PushHarness()
        assertEquals(NotificationStatus.notDetermined, h.service.authorization)
        h.service.userChanged(user)
        assertEquals(listOf<PushRegistration>(PushRegistration.timezone("Europe/London")), h.sent)
    }
}

// MARK: - Deliberation overrun, sealed exhibits (the store half)

class CourtOutageTests {
    @get:Rule val main = MainDispatcherRule()

    private fun deliberating(trialAt: Instant?) = Case(
        id = UUID.randomUUID(), coupleId = Fixture.couple, caseNumber = 1, title = "t", plaintiffId = Fixture.me, defendantId = Fixture.partner,
        status = CaseStatus.deliberating, charge = "c", remedyRequested = "r", trialAt = trialAt,
    )

    @Test fun overdueDeliberationNeverCountsNegative() {
        val now = Instant.ofEpochSecond(1_790_000_000)
        val ahead = deliberating(now.plusSeconds(600))
        assertFalse(ahead.isDeliberationOverdue(now))
        val late = deliberating(now.minusSeconds(3 * 3600))
        assertTrue(late.isDeliberationOverdue(now))
        assertEquals("The court is still deliberating", CaseCopy.stillDeliberatingLine)
        assertFalse(deliberating(null).isDeliberationOverdue(now))
        assertFalse(late.copy(status = CaseStatus.verdict).isDeliberationOverdue(now))
    }

    @Test fun oneSidedExhibitsDuringDefence() {
        val c = Case(
            id = UUID.randomUUID(), coupleId = Fixture.couple, caseNumber = 1, title = "t", plaintiffId = Fixture.me, defendantId = Fixture.partner,
            status = CaseStatus.defence, charge = "c", remedyRequested = "r",
        )
        val ex = Exhibit(id = UUID.randomUUID(), caseId = c.id, ownerId = Fixture.me, label = ExhibitLabel.A, type = ExhibitType.text, caption = "x", body = "y")
        val store = CaseStore(preview = Profile(id = Fixture.me, displayName = "Sam", coupleId = Fixture.couple), partner = null, couple = null, cases = listOf(c), exhibits = listOf(ex))
        assertTrue(store.exhibits(c.id, owner = Fixture.partner).isEmpty())
        assertEquals(1, store.exhibits(c.id).size)
        assertNull(ex.weight)
    }
}
