// Port of ArgueWin/Courtroom/CourtroomState.swift: the courtroom boundary types (docs/CONTRACTS.md, "iOS ↔ Courtroom
// boundary"). Owned by the courtroom. The app builds these and mounts `CourtroomScene`.
package app.plead.android.courtroom

import app.plead.android.models.Case
import app.plead.android.models.Exhibit
import app.plead.android.models.JudgePersona
import app.plead.android.models.Judgement
import app.plead.android.models.ObjectionReason
import app.plead.android.models.Profile
import app.plead.android.models.Role
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.Turn
import app.plead.android.models.Verdict
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Everything the courtroom scene renders. The scene is a pure function of this value: "whose turn / what can I do"
 * is derived by `CourtroomLogic` from `kase.phase`, `kase.phaseTurnOwner`, `myRole` and `turns`.
 *
 * Additive fields beyond the contract (safe to leave at their defaults):
 * - `verdict`: the `verdicts` row for this case, once revealed. When `kase.status == verdict` and this is non-null,
 *   `CourtroomScene` presents `VerdictMomentView` itself.
 * - `judgePersona`: judge sprite, bench nameplate and bubble author name.
 * - `deliberationProgress`: mirror of `cases.panel_progress` (0 = not started, 1–3 jurors done, 4 = presiding judge
 *   done). Drives the deliberation overlay. `kase.panelProgress` is used as a fallback, so leaving this at 0 is safe.
 * - `judgement` (amendment j): the case's `judgements` row, once the verdict is revealed.
 * - `settlement`, `settlementOffer`, `canProposeSettlement` (amendment n, Settle Outside Court).
 * - `presentRoles` (amendment ac): the sides in the room; null = both.
 *
 * Swift mutates copies (`var s = …; s.presentRoles = …`); Kotlin uses `copy(…)`.
 */
data class CourtroomState(
    val kase: Case,
    val turns: List<Turn>,
    val exhibits: List<Exhibit>,
    val me: Profile,
    val partner: Profile,
    val myRole: Role?,
    /** Signed URLs for photo/screenshot exhibits, keyed by exhibit id. */
    val exhibitURLs: Map<UUID, URI> = emptyMap(),
    /** Clock used for pure logic (deadline passed etc.). The dock countdown ticks live. */
    val now: Instant = Instant.now(),
    /** Additive: the revealed verdict, if any. */
    val verdict: Verdict? = null,
    /** Additive: the judge persona (sprite, nameplate, bubble author). */
    val judgePersona: JudgePersona = JudgePersona.wigsworth,
    /** Additive (v2): deliberation theatre progress, 0...4 (mirror of `cases.panel_progress`). */
    val deliberationProgress: Int = 0,
    /** Additive (amendment j): the winner-selected court judgement, if any. */
    val judgement: Judgement? = null,
    /** Additive (amendment n): the case's settlement (pending, or accepted / fulfilled once settled). */
    val settlement: Settlement? = null,
    /** Additive (amendment n): the settlement's latest offer. */
    val settlementOffer: SettlementOffer? = null,
    /** Additive (amendment n): the store says I may propose a settlement now. */
    val canProposeSettlement: Boolean = false,
    /**
     * Additive (amendment ac): the sides in the room. null = both (fixtures, previews). A side not in the set has
     * not joined: its podium stays empty (name tag only) and it never walks in with the entrance; when it appears
     * later it gets a short walk-in. See `CourtroomLogic.presentRoles`.
     */
    val presentRoles: Set<Role>? = null,
) {
    /** Is this side in the room (amendment ac)? */
    fun isPresent(role: Role): Boolean = presentRoles?.contains(role) ?: true

    /** Effective panel progress, clamped to 0...4. */
    val panelProgress: Int get() = min(4, max(0, max(deliberationProgress, kase.panelProgress)))

    /** Profile for a side of the case (Swift `profile(for: Role)`). */
    fun profile(role: Role): Profile = if (kase.userId(role) == me.id) me else partner

    /** Swift `profile(for: UUID?)`. */
    fun profile(id: UUID?): Profile? {
        id ?: return null
        if (id == me.id) return me
        if (id == partner.id) return partner
        return null
    }

    fun exhibit(id: UUID?): Exhibit? {
        id ?: return null
        return exhibits.firstOrNull { it.id == id }
    }
}

/**
 * Side effects the scene can request. Both map 1:1 onto edge functions.
 * - `submitTurn(body, exhibitId)` → `submit_turn`. In an exhibits phase, a turn with a null exhibit id means "I rest".
 * - `raiseObjection(exhibitId, reason)` → `raise_objection`; a null reason means pass.
 * Additive (amendment j), all defaulted to no-ops: `chooseJudgement()`, `respondJudgement(accept)`, `markServed()`.
 * Additive (amendment n), defaulted to no-ops: `proposeSettlement()`, `openSettlement()`, `backToDocket()`.
 */
class CourtroomActions(
    val submitTurn: suspend (String, UUID?) -> Unit,
    /** null reason = pass. */
    val raiseObjection: suspend (UUID, ObjectionReason?) -> Unit,
    val chooseJudgement: () -> Unit = {},
    val respondJudgement: suspend (Boolean) -> Unit = {},
    val markServed: suspend () -> Unit = {},
    val proposeSettlement: () -> Unit = {},
    val openSettlement: () -> Unit = {},
    val backToDocket: () -> Unit = {},
) {
    companion object {
        /** No-op actions for previews. */
        val noop: CourtroomActions get() = CourtroomActions(submitTurn = { _, _ -> }, raiseObjection = { _, _ -> })
    }
}
