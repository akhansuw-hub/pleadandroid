// Port of ArgueWin/Courtroom/CourtEntranceLive.swift: the shared court entrance in the live courtroom (CONTRACTS-v2
// amendment ac). `CourtroomScene` owns one `CourtLiveEntrance` per case:
//   - first time a case opens in the courtroom (during the trial): the full sequence plays, and
//     `courtEntranceSeen.<caseId>` is stored in UserDefaults as it starts;
//   - any later open (or a case past the trial): `restoreOccupied()` — the occupied court, no gavel;
//   - a side that has not joined (`CourtroomState.isPresent`) never enters and its podium stays empty; when it appears
//     in a later state it walks in once (`walkIn`), without replaying anything else;
//   - a tap on the stage finishes the entrance; leaving the screen snaps it to the ready court.
// Amendment ad: it also owns the case call (`caseCall`, a `CourtCaseCallDirector` built from the live case with
// `CourtCaseCall.live`). The call plays after the entrance reaches ready, or at once on a restored court that has not
// been called yet (`courtCaseCallSeen.<caseId>`, stored as it starts), and only while nobody has testified ("Sam, you
// may begin" must still be true): in trial, not deliberating, no safety stop, no pending settlement, no party turn
// yet. A later open, or a case that has moved on, skips it. Taps go to the entrance first, then to the call (card →
// introduction → reveal complete → opening).
@file:Suppress("ClassName")

package app.plead.android.courtroom

import app.plead.android.models.CaseStatus
import app.plead.android.models.Role
import app.plead.android.models.Speaker
import app.plead.android.services.UserDefaults
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay

/** How the scene treats the entrance (debug capture flags pick the non-default ones). */
sealed class CourtEntranceMode {
    /** Play once per case (persisted), restore afterwards. */
    data object auto : CourtEntranceMode()

    /** Play on every open (`AWCourtEntrance replay`). */
    data object replay : CourtEntranceMode()

    /** Freeze the sequence this many seconds in (`AWCourtEntrance 0.6`), for stills. The case is not called. */
    data class hold(val seconds: Double) : CourtEntranceMode()

    /**
     * Amendment ad stills: the ready court held on the NOW HEARING card (`AWCourtEntrance caseCall`) or on the judge's
     * fully revealed introduction (`AWCourtEntrance introduction`).
     */
    data object holdCaseCall : CourtEntranceMode()
    data object holdIntroduction : CourtEntranceMode()
}

class CourtLiveEntrance(
    state: CourtroomState,
    reduceMotion: Boolean,
    private val mode: CourtEntranceMode = CourtEntranceMode.auto,
    private val defaults: UserDefaults = UserDefaults.standard,
    sleep: suspend (Double) -> Unit = { delay((it * 1000).toLong()) },
    scope: CoroutineScope = MainScope(),
) {
    val caseId: UUID = state.kase.id

    /** This open plays the sequence (false = restored at once). */
    val plays: Boolean = shouldPlay(state, defaults, mode)
    val director = CourtEntranceDirector(
        hasPlaintiff = state.isPresent(Role.plaintiff), hasDefendant = state.isPresent(Role.defendant),
        reduceMotion = reduceMotion, sleep = sleep, scope = scope,
    )

    /** The case call (amendment ad) that follows the entrance. */
    val caseCall = CourtCaseCallDirector(
        call = CourtCaseCall.live(state), plays = shouldCallCase(state, defaults, mode),
        reduceMotion = reduceMotion, sleep = sleep, scope = scope,
    )

    private var started = false
    private val walkedIn: MutableSet<Role> = mutableSetOf()

    init {
        if (!plays) director.restoreOccupied()
    }

    /** The record waits for the court to be called (amendment ad). */
    val holdsRecord: Boolean get() = caseCall.holdsRecord

    /** The entrance is still between the empty room and the gavel (the judge is not at the bench yet). */
    val isEntering: Boolean
        get() = plays && (director.phase == CourtEntrancePhase.preparing || director.phase == CourtEntrancePhase.entering)

    /** The scene appeared: play (and remember) the first time; nothing on a restored court. */
    fun begin() {
        if (started) return
        started = true
        if (caseCall.plays) {
            when (mode) {
                CourtEntranceMode.holdCaseCall -> caseCall.freeze(CourtOpeningStep.caseCall)
                CourtEntranceMode.holdIntroduction -> caseCall.freeze(CourtOpeningStep.judgeIntroduction)
                CourtEntranceMode.auto -> defaults.set(true, caseCallSeenKey(caseId))
                CourtEntranceMode.replay, is CourtEntranceMode.hold -> Unit
            }
        }
        if (!plays || director.phase != CourtEntrancePhase.preparing) {
            // A restored court that has not been called yet: call it now.
            caseCall.begin()
            return
        }
        // The judge's gavel calls the court to order, then the case is called.
        director.onReady = { caseCall.begin() }
        when (mode) {
            is CourtEntranceMode.hold -> director.seek(mode.seconds)
            CourtEntranceMode.auto -> {
                defaults.set(true, seenKey(caseId))
                director.start()
            }
            CourtEntranceMode.replay -> director.start()
            CourtEntranceMode.holdCaseCall, CourtEntranceMode.holdIntroduction -> Unit
        }
    }

    /** The case changed: a side that just joined walks in (once). */
    fun update(s: CourtroomState) {
        for (r in listOf(Role.plaintiff, Role.defendant)) {
            if (s.isPresent(r) && !director.isPresent(r) && !walkedIn.contains(r)) {
                walkedIn.add(r)
                director.walkIn(plaintiff = r == Role.plaintiff)
            }
        }
        // The trial moved on (someone testified, a safety stop, deliberation…): the call is over.
        if (caseCall.holdsRecord && !caseCall.frozen && !canCallCase(s)) caseCall.finish()
    }

    /** A tap on the stage: finish the entrance now. */
    fun tap() {
        if (mode is CourtEntranceMode.hold) return
        if (director.phase == CourtEntrancePhase.entering) director.finish() else caseCall.tap()
    }

    /** Off screen: snap to the ready court (a return shows it occupied). */
    fun end() {
        if (mode is CourtEntranceMode.hold) return
        if (director.isRunning || director.phase == CourtEntrancePhase.entering) director.finish()
        if (!caseCall.frozen) caseCall.finish()
    }

    companion object {
        fun seenKey(caseId: UUID): String = "courtEntranceSeen.${caseId.toString().uppercase()}"

        /** Amendment ad: the case has been called on this device. */
        fun caseCallSeenKey(caseId: UUID): String = "courtCaseCallSeen.${caseId.toString().uppercase()}"

        /**
         * Whether this open of the case should play the sequence: only during the trial (not deliberating, not after a
         * safety stop), and only the first time.
         */
        fun shouldPlay(s: CourtroomState, defaults: UserDefaults, mode: CourtEntranceMode = CourtEntranceMode.auto): Boolean =
            when (mode) {
                CourtEntranceMode.replay, is CourtEntranceMode.hold -> true
                CourtEntranceMode.holdCaseCall, CourtEntranceMode.holdIntroduction -> false
                CourtEntranceMode.auto -> {
                    if (s.kase.status != CaseStatus.trial || CourtroomLogic.isDeliberating(s) || s.turns.lastOrNull()?.isSafetyNotice == true) {
                        false
                    } else {
                        !defaults.bool(seenKey(s.kase.id))
                    }
                }
            }

        /**
         * The case can still be called: in trial, not deliberating, no safety stop or pending settlement, and no party
         * has spoken yet (the judge hands the floor to the plaintiff, so testimony must not have started).
         */
        fun canCallCase(s: CourtroomState): Boolean =
            s.kase.status == CaseStatus.trial && !CourtroomLogic.isDeliberating(s) && s.turns.lastOrNull()?.isSafetyNotice != true &&
                !CourtroomLogic.isSettlementPending(s) && s.turns.none { it.speaker != Speaker.judge }

        /** Whether this open calls the case (amendment ad): once per case, while it can still be called. */
        fun shouldCallCase(s: CourtroomState, defaults: UserDefaults, mode: CourtEntranceMode = CourtEntranceMode.auto): Boolean =
            when (mode) {
                is CourtEntranceMode.hold -> false
                CourtEntranceMode.holdCaseCall, CourtEntranceMode.holdIntroduction -> true
                CourtEntranceMode.replay -> canCallCase(s)
                CourtEntranceMode.auto -> canCallCase(s) && !defaults.bool(caseCallSeenKey(s.kase.id))
            }
    }
}
