// Port of ArgueWinTests/ModelDecodingTests.swift (the suites whose subjects exist after wave 1).
// Owed by later waves, with their subjects: encodesAvatarForProfileUpdate (ProfileService.AvatarUpdate) and
// edgeErrorEnvelope (EdgeFunctions) → 2a; ExhibitLabelTests.draftsGetSequentialLabels (DraftExhibit) → 2a;
// CaseFlowTests, DeepLinkTests (DeepLink / CaseFlow) → 2a; ExhibitDockCopyTests (CourtroomLogic) → 3a;
// AccountDeletionTests → 3e; DeepLinkTests.websiteDomain's PaywallCopy URLs → 3c.
package app.plead.android.models

import app.plead.android.services.JSONCoding
import app.plead.android.services.SupabaseDate
import java.util.UUID
import kotlin.math.abs
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fixtures shaped exactly like Supabase rows (snake_case, Postgres timestamptz strings). */
class ModelDecodingTests {
    private val json = JSONCoding.arguewin

    companion object {
        val caseJSON = """
        {
          "id": "aaaaaaaa-0000-0000-0000-000000000014",
          "couple_id": "33333333-3333-3333-3333-333333333333",
          "case_number": 14,
          "title": "The Thermostat Incident",
          "plaintiff_id": "11111111-1111-1111-1111-111111111111",
          "defendant_id": "22222222-2222-2222-2222-222222222222",
          "status": "awaiting_verdict",
          "phase": "cross_examination",
          "phase_turn_owner": "defendant",
          "charge": "17°C. In September.",
          "remedy_requested": "21°C for a week",
          "counter_claim": null,
          "plea": "not_guilty",
          "proposed_trial_at": "2026-09-25T19:00:00+00:00",
          "proposal_count": 1,
          "trial_at": "2026-09-25T19:00:00.123456+00:00",
          "verdict_at": null,
          "deadline_at": "2026-09-24T07:00:00.5Z",
          "panel_progress": 3,
          "deliberating_at": "2026-09-25T18:00:00+00:00",
          "created_at": "2026-09-23 17:00:00.654321+00",
          "updated_at": "2026-09-23T17:05:00.000Z",
          "closed_at": null
        }
        """.trimIndent()
    }

    @Test fun decodesCaseRow() {
        val c = json.decodeFromString<Case>(caseJSON)
        assertEquals(14, c.caseNumber)
        assertEquals(CaseStatus.awaitingVerdict, c.status)
        assertEquals(TrialPhase.crossExamination, c.phase)
        assertEquals(Role.defendant, c.phaseTurnOwner)
        assertEquals(Plea.notGuilty, c.plea)
        assertEquals(1, c.proposalCount)
        assertNull(c.counterClaim)
        val trial = c.trialAt ?: return fail("trialAt")
        assertTrue(abs(trial.toEpochMilli() / 1000.0 - 1_790_362_800.123) < 0.01)
        assertTrue(c.createdAt < c.updatedAt)
        assertEquals(3, c.panelProgress)
        assertNotNull(c.deliberatingAt)
        assertEquals(Role.defendant, c.role(of = UUID.fromString("22222222-2222-2222-2222-222222222222")))
    }

    @Test fun decodesProfileAndCouple() {
        val profile = json.decodeFromString<Profile>(
            """
            {"id":"11111111-1111-1111-1111-111111111111","display_name":"Sam",
             "avatar_json":{"skin":4,"hair":5,"hairstyle":"ponytail","top":7,"outfit":"suit","version":1},
             "couple_id":null,"push_token":null,"timezone":"Europe/London","created_at":"2026-09-01T10:00:00+00:00"}
            """,
        )
        assertEquals("Sam", profile.displayName)
        assertEquals(Avatar(skin = 4, hair = 5, hairstyle = Avatar.Hairstyle.ponytail, top = 7, outfit = Avatar.Outfit.suit), profile.avatar)
        assertNull(profile.coupleId)

        val couple = json.decodeFromString<Couple>(
            """
            {"id":"33333333-3333-3333-3333-333333333333","invite_code":"KX7P2Q","invite_expires_at":"2026-09-30T10:00:00+00:00",
             "premium_until":"2099-01-01T00:00:00+00:00","payer_user_id":"11111111-1111-1111-1111-111111111111",
             "judge_persona":"wigsworth","linked_at":"2026-09-02T10:00:00+00:00","created_at":"2026-09-01T10:00:00+00:00"}
            """,
        )
        assertTrue(couple.isPremium)
        assertTrue(couple.isLinked)
        assertEquals(JudgePersona.wigsworth, couple.judgePersona)
    }

    @Test fun decodesExhibitTurnAndVerdict() {
        val exhibit = json.decodeFromString<Exhibit>(
            """
            {"id":"aaaaaaaa-0000-0000-0000-000000000101","case_id":"aaaaaaaa-0000-0000-0000-000000000014",
             "owner_id":"11111111-1111-1111-1111-111111111111","label":"B","type":"receipt","storage_path":null,
             "caption":"Timeline","body":"12 Sept, 21:14: said he'd be home by 9","objection_reason":"out_of_context",
             "objection_ruling":"sustained","objection_note":null,"weight":1,"presented_at":null,"sort":1,
             "created_at":"2026-09-23T17:00:00+00:00"}
            """,
        )
        assertEquals(ExhibitLabel.B, exhibit.label)
        assertEquals(ExhibitType.receipt, exhibit.type)
        assertEquals(ObjectionReason.outOfContext, exhibit.objectionReason)
        assertEquals(ObjectionRuling.sustained, exhibit.objectionRuling)

        val turn = json.decodeFromString<Turn>(
            """
            {"id":"aaaaaaaa-0000-0000-0000-000000000201","case_id":"aaaaaaaa-0000-0000-0000-000000000014",
             "phase":"cross_examination","speaker":"judge","body":"Two questions.","exhibit_id":null,"ai_call":"cross_examine",
             "meta":{"questions":["Why 17?","Who owns the hat?"],"side":"defendant"},"created_at":"2026-09-23T17:00:00.1+00:00"}
            """,
        )
        assertEquals(AICall.crossExamine, turn.aiCall)
        assertEquals(listOf("Why 17?", "Who owns the hat?"), turn.questions)
        assertEquals(Role.defendant, turn.crossSide)
        assertFalse(turn.isObjection)

        val verdict = json.decodeFromString<Verdict>(
            """
            {"id":"aaaaaaaa-0000-0000-0000-000000000301","case_id":"aaaaaaaa-0000-0000-0000-000000000014","kind":"ruling",
             "winner_id":"11111111-1111-1111-1111-111111111111","is_tie":false,"recap":"r",
             "findings":[{"exhibit_id":"aaaaaaaa-0000-0000-0000-000000000101","label":"Exhibit B","side":"plaintiff","finding":"Damning.","weight":3}],
             "sentence":"s","closing_line":"The court notes.","share_image_path":null,
             "panel_round":1,"panel_split":"2-1","panel_votes":{"plaintiff":2,"defendant":1,"tie":0},
             "confidence_label":"medium","majority_conflict":null,"model_ref":"claude-sonnet-5","prompt_version":"verdict-v2",
             "created_at":"2026-09-23T17:00:00+00:00"}
            """,
        )
        assertEquals(3, verdict.findings.first().weight)
        assertEquals(1, verdict.panelRound)
        assertEquals("2-1", verdict.panelSplit)
        assertEquals(2.0, verdict.panelVotes?.get("plaintiff")?.numberValue)
        assertEquals("medium", verdict.confidenceLabel)
        assertNull(verdict.majorityConflict)
        assertEquals("2-1 PANEL DECISION", verdict.panelSplitHeadline)
        assertEquals(Role.plaintiff, verdict.findings.first().side)
        assertNotNull(verdict.findings.first().exhibitId)
    }

    /** `avatar_json` defaults to `{}` in Postgres; missing or junk keys fall back to defaults. */
    @Test fun decodesEmptyAndPartialAvatarJSON() {
        val empty = json.decodeFromString<Profile>(
            """
            {"id":"11111111-1111-1111-1111-111111111111","display_name":"Sam","avatar_json":{},
             "couple_id":null,"push_token":null,"timezone":null,"created_at":"2026-09-01T10:00:00+00:00"}
            """,
        )
        assertEquals(Avatar.default, empty.avatar)

        val partial = json.decodeFromString<Avatar>("""{"hairstyle":"mohawk","skin":5,"outfit":"dress"}""")
        assertEquals(Avatar.Hairstyle.short, partial.hairstyle)   // unknown value → default
        assertEquals(5, partial.skin)
        assertEquals(Avatar.Outfit.dress, partial.outfit)
        assertEquals(1, partial.version)
    }

    /**
     * The app writes `avatar_json` with exactly the contract keys. (iOS goes through ProfileService.AvatarUpdate,
     * wave 2a; the Avatar half is checked here.)
     */
    @Test fun encodesAvatarWithContractKeys() {
        val avatar = json.encodeToJsonElement(Avatar.serializer(), Avatar(skin = 0, hair = 1, hairstyle = Avatar.Hairstyle.bun, top = 2, outfit = Avatar.Outfit.tee)).jsonObject
        assertEquals(setOf("skin", "hair", "hairstyle", "top", "outfit", "version"), avatar.keys)
        assertEquals("bun", avatar["hairstyle"]?.jsonPrimitive?.content)
    }

    @Test fun decodesJurorReview() {
        val review = json.decodeFromString<JurorReview>(
            """
            {"id":"aaaaaaaa-0000-0000-0000-000000000401","case_id":"aaaaaaaa-0000-0000-0000-000000000014","panel_round":1,
             "juror_role":"consistency","findings":{"contradictions":[],"notes":[],"preferred_winner":"plaintiff","confidence":0.7,
             "summary":"The stories don't line up."},"preferred_winner_id":"11111111-1111-1111-1111-111111111111",
             "is_tie":false,"confidence":0.7,"model_ref":"m","prompt_version":"p","created_at":"2026-09-23T17:00:00+00:00"}
            """,
        )
        assertEquals(JurorRole.consistency, review.jurorRole)
        assertEquals("02", review.jurorRole.number)
        assertEquals("The stories don't line up.", review.summary)
        assertTrue(abs(review.confidence - 0.7) < 0.001)
    }

    @Test fun decodesExhibitOccurredAt() {
        val exhibit = json.decodeFromString<Exhibit>(
            """
            {"id":"aaaaaaaa-0000-0000-0000-000000000101","case_id":"aaaaaaaa-0000-0000-0000-000000000014",
             "owner_id":"11111111-1111-1111-1111-111111111111","label":"A","type":"screenshot","storage_path":"x/y/z",
             "caption":"The like","body":null,"objection_reason":null,"objection_ruling":null,"objection_note":null,"weight":null,
             "presented_at":null,"occurred_at":"2026-09-20T01:12:00+00:00","sort":0,"created_at":"2026-09-23T17:00:00+00:00"}
            """,
        )
        assertNotNull(exhibit.occurredAt)
    }

    @Test fun parsesSupabaseDates() {
        for (raw in listOf(
            "2026-09-23T17:00:00Z",
            "2026-09-23T17:00:00+00:00",
            "2026-09-23T17:00:00.123+00:00",
            "2026-09-23T17:00:00.123456+00:00",
            "2026-09-23 17:00:00+00",
            "2026-09-23T17:00:00.123456",
            "2026-09-23T18:00:00+0100",
        )) {
            val d = SupabaseDate.parse(raw) ?: return fail("did not parse $raw")
            assertTrue(raw, abs(d.epochSecond - 1_790_182_800L) < 1)
        }
    }

    // Android-only checks of the port's JSON rules (JSONCoding.kt).

    @Test fun unknownEnumValueFailsLikeSwift() {
        val bad = caseJSON.replace("\"awaiting_verdict\"", "\"on_holiday\"")
        assertTrue(runCatching { json.decodeFromString<Case>(bad) }.isFailure)
    }

    @Test fun missingRequiredFieldFailsLikeSwift() {
        val bad = caseJSON.replace("\"panel_progress\": 3,", "")
        assertTrue(runCatching { json.decodeFromString<Case>(bad) }.isFailure)
    }

    @Test fun encodesSnakeCaseUpperCaseIdsAndIsoDates() {
        val c = json.decodeFromString<Case>(caseJSON)
        val out = json.encodeToJsonElement(Case.serializer(), c).jsonObject
        assertEquals("AAAAAAAA-0000-0000-0000-000000000014", out["id"]?.jsonPrimitive?.content)
        assertEquals("2026-09-25T19:00:00Z", out["trial_at"]?.jsonPrimitive?.content)
        assertTrue(out.containsKey("remedy_requested"))
        assertFalse(out.containsKey("counter_claim"))   // nil is omitted, as Swift's encodeIfPresent
    }

    @Test fun decodesDateOnlyColumns() {
        val d = SupabaseDate.parse("2024-02-14") ?: return fail("date only")
        assertEquals("2024-02-14T00:00:00Z", SupabaseDate.format(d))
    }
}

class LimitsTests {
    /** Paid app: one tier for every subscribed couple. */
    @Test fun subscribedLimitsMatchContract() {
        val p = Limits.subscribed
        assertNull(p.openCases)   // amendment ag: unlimited
        assertNull(p.casesPerWeek)
        assertTrue(p.appeals)
        assertNull(p.historyCases)
    }
}

class ExhibitLabelTests {
    @Test fun labelsRunPastZ() {
        assertEquals(ExhibitLabel.A, ExhibitLabel.at(0))
        assertEquals(ExhibitLabel.F, ExhibitLabel.at(5))
        assertEquals("Z", ExhibitLabel.at(25).rawValue)
        assertEquals("AA", ExhibitLabel.at(26).rawValue)
        assertEquals("AB", ExhibitLabel.at(27).rawValue)
        assertEquals("AZ", ExhibitLabel.at(51).rawValue)
        assertEquals("BA", ExhibitLabel.at(52).rawValue)
        assertEquals(ExhibitLabel.A, ExhibitLabel.at(-3))
    }

    @Test fun indexRoundTrips() {
        for (i in listOf(0, 1, 25, 26, 27, 99, 100, 701)) assertEquals(i, ExhibitLabel.at(i).index)
    }

    @Test fun orderingIsByIndexNotString() {
        val z = ExhibitLabel.at(25)
        val aa = ExhibitLabel.at(26)
        assertTrue(z < aa)
        assertTrue(ExhibitLabel.A < ExhibitLabel.B)
        assertEquals(listOf(ExhibitLabel.A, ExhibitLabel.C, z, aa), listOf(aa, ExhibitLabel.C, z, ExhibitLabel.A).sorted())
    }

    @Test fun failableInitValidates() {
        assertEquals("AA", ExhibitLabel.from("aa")?.rawValue)
        assertNull(ExhibitLabel.from(""))
        assertNull(ExhibitLabel.from("ABC"))
        assertNull(ExhibitLabel.from("1"))
    }

    @Test fun decodesTwoLetterLabels() {
        val l = kotlinx.serialization.json.Json.decodeFromString<List<ExhibitLabel>>("""["A","AB"]""")
        assertEquals(listOf(ExhibitLabel.A, ExhibitLabel.at(27)), l)
    }

    @Test fun noExhibitCapOnLimits() {
        assertEquals(100, Limits.exhibitsSanityGuard)
    }
}

class TrialPhaseTests {
    @Test fun mvpOrderHasNoRebuttals() {
        var phase: TrialPhase? = TrialPhase.plaintiffOpening
        val order = mutableListOf<TrialPhase>()
        while (phase != null) {
            order.add(phase)
            phase = phase.next
        }
        assertEquals(
            listOf(
                TrialPhase.plaintiffOpening, TrialPhase.defendantOpening, TrialPhase.plaintiffExhibits, TrialPhase.defendantExhibits,
                TrialPhase.crossExamination, TrialPhase.plaintiffClosing, TrialPhase.defendantClosing,
            ),
            order,
        )
        assertNull(TrialPhase.defendantClosing.next)
        assertNull(TrialPhase.crossExamination.speakingSide)
    }
}
