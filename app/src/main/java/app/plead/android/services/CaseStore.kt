// Port of ArgueWin/Services/CaseStore.swift.
package app.plead.android.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.BuildConfig
import app.plead.android.models.Avatar
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Couple
import app.plead.android.models.EdgeError
import app.plead.android.models.Exhibit
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementOptionSet
import app.plead.android.models.JudgementStatus
import app.plead.android.models.JurorReview
import app.plead.android.models.JurorRole
import app.plead.android.models.Limits
import app.plead.android.models.ObjectionReason
import app.plead.android.models.Plea
import app.plead.android.models.Profile
import app.plead.android.models.Role
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSource
import app.plead.android.models.SettlementStatus
import app.plead.android.models.SettlementSuggestion
import app.plead.android.models.Speaker
import app.plead.android.models.Turn
import app.plead.android.models.Verdict
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.plead.android.designsystem.shortTitle

/**
 * Single source of truth for the couple's data. Initial fetch + Supabase Realtime.
 * Every state transition goes through an edge function; the returned case row is
 * merged immediately and Realtime reconciles the rest (turns, exhibits, verdicts).
 *
 * Every property is Compose snapshot state (Swift `@Observable`); every method runs on the main thread (Swift
 * `@MainActor`). Non-Compose observers use `snapshotFlow { … }` (see [WidgetSnapshotStore]).
 */
class CaseStore private constructor(val backend: Backend?, preview: Boolean) {
    // MARK: State
    var me: Profile? by mutableStateOf(null)
        private set
    var partner: Profile? by mutableStateOf(null)
        private set
    var couple: Couple? by mutableStateOf(null)
        private set
    var cases: List<Case> by mutableStateOf(emptyList())
        private set
    var exhibits: List<Exhibit> by mutableStateOf(emptyList())
        private set
    var turns: List<Turn> by mutableStateOf(emptyList())
        private set
    var verdicts: List<Verdict> by mutableStateOf(emptyList())
        private set

    /** The three juror reviews per revealed case (hidden by RLS until the verdict is revealed). */
    var jurorReviews: List<JurorReview> by mutableStateOf(emptyList())
        private set

    /** Signed URLs for exhibits with files, keyed by exhibit id. */
    var exhibitURLs: Map<UUID, URI> by mutableStateOf(emptyMap())
        private set

    /**
     * Couples I have left (`profiles_public.past_couple_ids`, own row only). Their cases stay readable
     * and are shown in the docket as history (closed with their verdict, or mistrial).
     */
    var pastCoupleIds: List<UUID> by mutableStateOf(emptyList())
        private set

    /** Ex-partners on those cases (`profiles_public`: id, name, avatar only), for names on the record. */
    var formerPartners: List<Profile> by mutableStateOf(emptyList())
        private set

    /**
     * Court judgements (amendment j): one row per revealed case, keyed by case id. Couple-readable,
     * written by edge functions only; kept live via Realtime.
     */
    var judgements: Map<UUID, Judgement> by mutableStateOf(emptyMap())
        private set

    /**
     * The latest generated option set per case. RLS: readable only by the chooser, so the other
     * party never sees the unchosen options.
     */
    var judgementOptionSets: Map<UUID, JudgementOptionSet> by mutableStateOf(emptyMap())
        private set

    /**
     * Settle Outside Court (amendment n): the latest settlement attempt per case, keyed by case id.
     * Couple-readable, written by edge functions only; kept live via Realtime.
     */
    var settlements: Map<UUID, Settlement> by mutableStateOf(emptyMap())
        private set

    /** Negotiation history, keyed by settlement id, in round order. */
    var offers: Map<UUID, List<SettlementOffer>> by mutableStateOf(emptyMap())
        private set

    /** The neutral one-line context from `generate_settlement_options.summary`, by case id. */
    var settlementSummaries: Map<UUID, String> by mutableStateOf(emptyMap())
        private set

    /** Settlement ids whose "SETTLED OUT OF COURT" moment has been shown on this device. */
    var celebratedSettlementIds: Set<UUID> by mutableStateOf(if (preview) emptySet() else loadCelebrated())
        private set

    var hasLoaded: Boolean by mutableStateOf(false)
        private set
    var loadError: String? by mutableStateOf(null)
        private set

    /** Set when `couple.linkedAt` flips from null to a date during this session. */
    var linkCelebration: Boolean by mutableStateOf(false)

    // MARK: Dependencies
    class Backend(val client: SupabaseClient) {
        val edge = EdgeFunctions(client)
        val profiles = ProfileService(client)
        val storage = StorageService(client)
    }

    private var userId: UUID? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var coupleChannel: RealtimeChannel? = null
    private var caseChannel: RealtimeChannel? = null
    private var caseChannelIds: Set<UUID> = emptySet()
    private var judgementChannel: RealtimeChannel? = null
    private var judgementChannelIds: Set<UUID> = emptySet()
    private var judgementTasks: List<Job> = emptyList()
    private var settlementChannel: RealtimeChannel? = null
    private var settlementChannelKey: Set<UUID> = emptySet()
    private var settlementTasks: List<Job> = emptyList()
    private var coupleTasks: List<Job> = emptyList()
    private var caseTasks: List<Job> = emptyList()

    /** Called after my profile row is created/updated (AppModel retries `register_push`). */
    var onProfileSaved: (suspend () -> Unit)? = null

    /** DEBUG demo mode (no backend): plays the partner and the judge locally. Null in release builds. */
    var demo: DemoTrialSimulator? = null
        private set

    /** Live store. With no Supabase project configured it behaves like a backend-less (demo) store. */
    constructor(backend: Backend? = if (SupabaseService.isConfigured) Backend(SupabaseService.shared) else null) : this(backend, preview = false) {
        if (BuildConfig.DEMO_HARNESS && backend == null) demo = DemoTrialSimulator(store = this)
    }

    /** Preview/test store with fixed data and no network. */
    constructor(
        preview: Profile?,
        partner: Profile?,
        couple: Couple?,
        cases: List<Case> = emptyList(),
        exhibits: List<Exhibit> = emptyList(),
        turns: List<Turn> = emptyList(),
        verdicts: List<Verdict> = emptyList(),
        jurorReviews: List<JurorReview> = emptyList(),
        exhibitURLs: Map<UUID, URI> = emptyMap(),
        judgements: List<Judgement> = emptyList(),
        judgementOptions: List<JudgementOptionSet> = emptyList(),
        settlements: List<Settlement> = emptyList(),
        settlementOffers: List<SettlementOffer> = emptyList(),
    ) : this(null, preview = true) {
        this.me = preview; this.partner = partner; this.couple = couple; this.cases = cases
        this.exhibits = exhibits; this.turns = turns; this.verdicts = verdicts; this.jurorReviews = jurorReviews
        this.exhibitURLs = exhibitURLs
        this.judgements = judgements.associateBy { it.caseId }
        val sets = mutableMapOf<UUID, JudgementOptionSet>()
        for (s in judgementOptions) {
            val old = sets[s.caseId]
            sets[s.caseId] = if (old != null && old.round >= s.round) old else s
        }
        this.judgementOptionSets = sets
        var map = emptyMap<UUID, Settlement>()
        for (row in settlements) map = keepLatest(row, map)
        this.settlements = map
        this.offers = settlementOffers.groupBy { it.settlementId }.mapValues { (_, v) -> v.sortedBy { it.roundNumber } }
        this.userId = preview?.id
        this.hasLoaded = true
        if (BuildConfig.DEMO_HARNESS) demo = DemoTrialSimulator(store = this)
        // Widget snapshot (amendment o): only the DEBUG `AWDemo` store registers; tests / previews don't.
        WidgetSnapshotStore.shared.register(this, preview = true)
    }

    // MARK: Derived

    val isPremium: Boolean get() = couple?.isPremium ?: false

    /** Single tier: Plead is a paid app; unpaid couples never get past the gate. */
    val limits: Limits get() = Limits.subscribed

    /** No linked partner yet: can browse, cannot file. */
    val isSolo: Boolean get() = partner == null || couple?.isLinked != true

    val openCases: List<Case> get() = cases.filter { it.status.isOpen }.sortedByDescending { it.updatedAt }
    val closedCases: List<Case> get() = cases.filter { it.status.isClosed }.sortedByDescending { it.closedAt ?: it.updatedAt }

    /** The open case that most needs attention: my move first, then most recently updated. */
    val currentCase: Case?
        get() {
            if (me == null) return openCases.firstOrNull()
            return openCases.firstOrNull { nextAction(it).isActionable } ?: openCases.firstOrNull()
        }

    // MARK: Judgement (amendment j)

    fun judgement(caseId: UUID): Judgement? = judgements[caseId]

    /** The latest option set for the chooser (null while the court is still generating, or for the other party). */
    fun judgementOptions(caseId: UUID): JudgementOptionSet? = judgementOptionSets[caseId]

    /**
     * True when I choose this case's judgement (the winner; the plaintiff on a guilty plea / default).
     * Never on a tie: the court chooses (amendment l, `chooser_id` null).
     */
    fun isChooser(caseId: UUID): Boolean = judgements[caseId]?.let(::isMine) ?: false

    /** I am this judgement's chooser (false for a court-chosen resolution, and when signed out). */
    private fun isMine(j: Judgement): Boolean {
        val me = me ?: return false
        val chooser = j.chooserId ?: return false
        return chooser == me.id
    }

    /** The case's next step for me, with the court judgement layered on top. */
    fun nextAction(kase: Case): CaseAction {
        val me = me ?: return CaseAction.viewRecord
        return kase.nextAction(me.id, judgement(kase.id), settlement(kase.id), latestOffer(kase.id))
    }

    /**
     * Home's "Outstanding judgement" cards (amendment l), separate from the active (open) case card:
     * every delivered / accepted judgement on my cases (to act on, or to watch), plus a choice I still
     * owe on a case that has already closed (open cases carry that step on the active card).
     * Served / declined drop out. Soonest due first.
     */
    val outstandingJudgementCases: List<Case>
        get() {
            val me = me ?: return emptyList()
            return cases.filter { c ->
                val j = judgements[c.id]
                if (c.role(me.id) == null || j == null) false
                else j.isOutstanding || (j.status == JudgementStatus.pendingSelection && c.status.isClosed && isMine(j))
            }.sortedWith { a, b ->
                val da = judgements[a.id]?.dueAt ?: Instant.MAX
                val db = judgements[b.id]?.dueAt ?: Instant.MAX
                if (da != db) da.compareTo(db) else b.updatedAt.compareTo(a.updatedAt)
            }
        }

    /** The loser: the party the judgement is addressed to. null on a court-chosen tie (it binds both). */
    fun judgementRecipientId(kase: Case): UUID? {
        val j = judgements[kase.id] ?: return null
        val chooser = j.chooserId ?: return null
        return if (chooser == kase.plaintiffId) kase.defendantId else kase.plaintiffId
    }

    /**
     * The case shown in the Court tab when none is routed explicitly. With concurrent cases the most
     * urgent wins: a verdict waiting to be read, then a trial where it is my turn, then any trial,
     * then deliberation; ties break on recency.
     */
    val courtroomCase: Case?
        get() {
            fun rank(c: Case): Int = when (c.status) {
                CaseStatus.verdict -> 0
                CaseStatus.trial -> if (me?.let { c.phaseTurnOwner == c.role(it.id) } == true) 1 else 2
                CaseStatus.deliberating, CaseStatus.awaitingVerdict -> 3
                else -> 9
            }
            return cases.filter { it.status.isInCourtroom }
                .sortedWith(compareBy<Case> { rank(it) }.thenByDescending { it.updatedAt })
                .firstOrNull()
        }

    /**
     * A summons waiting for my plea (drives the full-screen cover). A summons with a pending settlement
     * stays hidden; it shows again, unchanged, if the settlement is rejected, withdrawn or expires.
     */
    val pendingSummons: Case?
        get() {
            val me = me ?: return null
            return cases.filter { it.status == CaseStatus.summoned && it.defendantId == me.id && !hasPendingSettlement(it.id) }
                .maxByOrNull { it.createdAt }
        }

    /** Me vs my current partner: cases of the current couple only (history with an ex stays out). */
    val winTally: WinTally
        get() {
            val t = WinTally()
            // A settled case is neither a win nor a loss (amendment n).
            for (c in cases) {
                if (!((c.status.isClosed || c.status == CaseStatus.verdict) && c.status != CaseStatus.closedSettled &&
                        (couple?.let { it.id == c.coupleId } ?: true))) continue
                when (outcome(c)) {
                    CaseOutcome.won -> t.mine += 1
                    CaseOutcome.lost -> t.partners += 1
                    CaseOutcome.tied -> t.ties += 1
                    null -> Unit
                }
            }
            return t
        }

    fun myRole(kase: Case): Role? = me?.let { kase.role(it.id) }
    fun caseById(id: UUID): Case? = cases.firstOrNull { it.id == id }

    fun exhibits(caseId: UUID): List<Exhibit> =
        exhibits.filter { it.caseId == caseId }
            .sortedWith(compareBy<Exhibit> { it.ownerId.uuidString }.thenBy { it.sort }.thenBy { it.label.index })

    fun exhibits(caseId: UUID, owner: UUID): List<Exhibit> =
        exhibits.filter { it.caseId == caseId && it.ownerId == owner }.sortedWith(compareBy<Exhibit> { it.sort }.thenBy { it.label.index })

    fun turns(caseId: UUID): List<Turn> = turns.filter { it.caseId == caseId }.sortedBy { it.createdAt }

    /** Latest verdict for a case (an appeal would add a second one). */
    fun verdict(caseId: UUID): Verdict? = verdicts.filter { it.caseId == caseId }.maxByOrNull { it.createdAt }

    /** Juror reviews for the latest panel round, in juror order (01 Evidence, 02 Consistency, 03 Fairness). */
    fun jurorReviews(caseId: UUID): List<JurorReview> {
        val list = jurorReviews.filter { it.caseId == caseId }
        val round = list.maxOfOrNull { it.panelRound } ?: return emptyList()
        val order = JurorRole.entries
        return list.filter { it.panelRound == round }.sortedBy { order.indexOf(it.jurorRole) }
    }

    /**
     * The defendant's written defence: `cases.defence_statement`, with a fallback to a pre-trial
     * defendant turn (phase = null) for older rows.
     */
    fun defenceStatement(caseId: UUID): String? {
        cases.firstOrNull { it.id == caseId }?.defenceStatement?.takeIf { it.isNotEmpty() }?.let { return it }
        val pre = turns(caseId).filter { it.speaker == Speaker.defendant && it.phase == null }
        return (pre.firstOrNull { it.meta["kind"]?.stringValue == "defence" } ?: pre.firstOrNull())?.body
    }

    fun profile(userId: UUID): Profile? {
        if (me?.id == userId) return me
        if (partner?.id == userId) return partner
        return formerPartners.firstOrNull { it.id == userId }
    }

    /** The other party on a case, from my point of view. */
    fun opponentId(kase: Case): UUID? {
        val me = me ?: return null
        return if (kase.plaintiffId == me.id) kase.defendantId else kase.plaintiffId
    }

    /** Outcome from my point of view (null while undecided, for a mistrial, or settled out of court). */
    fun outcome(kase: Case): CaseOutcome? {
        val me = me ?: return null
        if (kase.status == CaseStatus.closedSettled) return null
        verdict(kase.id)?.let { v ->
            if (v.isTie) return CaseOutcome.tied
            v.winnerId?.let { w -> return if (w == me.id) CaseOutcome.won else CaseOutcome.lost }
        }
        return when (kase.status) {
            CaseStatus.closedGuilty, CaseStatus.closedDefault -> if (kase.plaintiffId == me.id) CaseOutcome.won else CaseOutcome.lost
            else -> null
        }
    }

    /** "Alex won" / "You won" / "Tied" / "Mistrial" line for docket rows. */
    fun outcomeLine(kase: Case): String? {
        if (kase.status == CaseStatus.mistrial) return "Mistrial"
        if (kase.status == CaseStatus.closedSettled) return "Settled out of court"
        val o = outcome(kase) ?: return if (kase.status.isOpen) kase.statusTitle else null
        return when (o) {
            CaseOutcome.tied -> "Tied"
            CaseOutcome.won -> "You won"
            CaseOutcome.lost -> "${opponentId(kase)?.let { name(it, fallback = "Your partner") } ?: "Your partner"} won"
        }
    }

    /** Court-file ribbon: the status while open, the outcome once closed ("You won", "Tied"). */
    fun ribbonTitle(kase: Case): String {
        if (!kase.status.isClosed || kase.status == CaseStatus.mistrial) return kase.status.shortTitle
        return outcomeLine(kase) ?: kase.status.shortTitle
    }

    /** Whose display name to show for a side, or a role fallback. */
    fun name(of: UUID, fallback: String): String = profile(of)?.displayName ?: fallback

    /**
     * Why a new case can't be filed right now, or null. Since amendment ag there is no open-case cap,
     * so this only fires if a future tier ever sets one.
     */
    val filingBlocker: String?
        get() {
            val cap = limits.openCases ?: return null
            if (openCases.size < cap) return null
            return "You have $cap open cases. Close one before filing another."
        }

    // MARK: Loading

    suspend fun load(userId: UUID) {
        this.userId = userId
        // Widget snapshot (amendment o): observe this store and keep the App Group snapshot current.
        WidgetSnapshotStore.shared.register(this, preview = false)
        val backend = backend ?: run { hasLoaded = true; return }
        loadError = null
        try {
            applyOwn(backend.profiles.fetchOwnProfile(userId))
            loadCouple()
            hasLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "We couldn't reach the court. Check your connection and try again."
            hasLoaded = true
        }
    }

    /** Refetch everything (foreground, pull to refresh). Realtime can miss events while suspended. */
    suspend fun refresh() {
        val userId = userId ?: return
        val backend = backend ?: return
        runCatchingNonCancel { backend.profiles.fetchOwnProfile(userId) }?.let(::applyOwn)
        loadCouple()
    }

    /** My row from `profiles_public` (null = no profile yet). */
    private fun applyOwn(own: ProfileService.OwnProfile?) {
        me = own?.profile
        pastCoupleIds = own?.pastCoupleIds ?: emptyList()
    }

    /** Current couple first, then couples I have left (their cases are read-only history). */
    val historyCoupleIds: List<UUID>
        get() {
            val ids = mutableListOf<UUID>()
            for (id in listOfNotNull(me?.coupleId) + pastCoupleIds) if (!ids.contains(id)) ids.add(id)
            return ids
        }

    private suspend fun loadCouple() {
        val backend = backend ?: return
        val me = me ?: return
        try {
            val coupleId = me.coupleId
            if (coupleId != null) {
                val fetched = backend.profiles.fetchCouple(coupleId)
                applyCouple(fetched)
                partner = backend.profiles.fetchPartner(coupleId, me.id)
            } else {
                couple = null; partner = null
            }
            // Cases of my current couple and of couples I have left (RLS: `my_couple_ids()`); after an
            // unlink the old couple's cases are closed (verdict kept) or mistrials.
            val coupleIds = historyCoupleIds
            val rows: List<Case> = if (coupleIds.isEmpty()) emptyList() else backend.client.from("cases").select {
                filter { isIn("couple_id", coupleIds.map(UUID::toString)) }
                order("created_at", Order.DESCENDING)
            }.list(Case.serializer())
            cases = rows
            if (rows.isEmpty()) {
                exhibits = emptyList(); turns = emptyList(); verdicts = emptyList(); jurorReviews = emptyList()
                judgements = emptyMap(); judgementOptionSets = emptyMap(); settlements = emptyMap(); offers = emptyMap()
            } else {
                loadChildren(rows.map { it.id })
                loadJurorReviews(rows.filter { it.isRevealed }.map { it.id })
            }
            loadFormerPartners()
            if (me.coupleId != null) {
                if (coupleChannel == null) startRealtime(me.coupleId) else syncCaseChannel()
            } else {
                stopRealtime()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "Some of your cases couldn't be loaded. Pull to refresh."
        }
    }

    /** Names/avatars for the other party on history cases (not me, not my current partner). */
    private suspend fun loadFormerPartners() {
        val backend = backend ?: return
        val me = me ?: return
        val known = setOfNotNull(me.id, partner?.id)
        val ids = mutableSetOf<UUID>()
        for (c in cases) for (id in listOf(c.plaintiffId, c.defendantId)) if (!known.contains(id)) ids.add(id)
        if (ids.isEmpty()) {
            formerPartners = emptyList(); return
        }
        runCatchingNonCancel { backend.profiles.fetchProfiles(ids.toList()) }?.let { formerPartners = it }
    }

    private suspend fun loadChildren(caseIds: List<UUID>) {
        val backend = backend ?: return
        if (caseIds.isEmpty()) {
            exhibits = emptyList(); turns = emptyList(); verdicts = emptyList(); jurorReviews = emptyList(); return
        }
        val ids = caseIds.map(UUID::toString)
        val (e, t, v) = coroutineScope {
            val ex = async { backend.client.from("exhibits").select { filter { isIn("case_id", ids) } }.list(Exhibit.serializer()) }
            val tu = async {
                backend.client.from("turns").select {
                    filter { isIn("case_id", ids) }
                    order("created_at", Order.ASCENDING)
                }.list(Turn.serializer())
            }
            val ve = async { backend.client.from("verdicts").select { filter { isIn("case_id", ids) } }.list(Verdict.serializer()) }
            Triple(ex.await(), tu.await(), ve.await())
        }
        val idSet = caseIds.toSet()
        exhibits = exhibits.filter { !idSet.contains(it.caseId) } + e
        turns = turns.filter { !idSet.contains(it.caseId) } + t
        verdicts = verdicts.filter { !idSet.contains(it.caseId) } + v
        resolveExhibitURLs(e)
        loadJudgements(caseIds.filter { id -> cases.firstOrNull { it.id == id }?.isRevealed ?: true })
        loadSettlements(caseIds)
    }

    /** `judgements` rows for revealed cases. Tolerant: a failure here never blocks the docket. */
    private suspend fun loadJudgements(caseIds: List<UUID>) {
        val backend = backend ?: return
        if (caseIds.isEmpty()) return
        val rows = runCatchingNonCancel {
            backend.client.from("judgements").select { filter { isIn("case_id", caseIds.map(UUID::toString)) } }.list(Judgement.serializer())
        } ?: return
        judgements = judgements + rows.associateBy { it.caseId }
        for (row in rows) {
            if (row.status == JudgementStatus.pendingSelection && isMine(row) && judgementOptionSets[row.caseId] == null) {
                loadJudgementOptions(row.caseId)
            }
        }
    }

    /** Re-reads one judgement row (after an edge call, in case Realtime lags). */
    private suspend fun refreshJudgement(caseId: UUID) {
        val backend = backend ?: return
        val rows = runCatchingNonCancel {
            backend.client.from("judgements").select { filter { eq("case_id", caseId.toString()) } }.list(Judgement.serializer())
        } ?: return
        val row = rows.firstOrNull() ?: return
        judgements = judgements + (caseId to row)
    }

    /**
     * The latest `judgement_options` round for a case (chooser only). Returns the cached set when
     * nothing newer is readable, so callers can poll while the court generates round 0.
     */
    suspend fun loadJudgementOptions(caseId: UUID): JudgementOptionSet? {
        val backend = backend ?: return judgementOptionSets[caseId]
        val rows = runCatchingNonCancel {
            backend.client.from("judgement_options").select {
                filter { eq("case_id", caseId.toString()) }
                order("round", Order.DESCENDING)
                limit(1)
            }.list(JudgementOptionSet.serializer())
        }
        val latest = rows?.firstOrNull() ?: return judgementOptionSets[caseId]
        judgementOptionSets = judgementOptionSets + (caseId to latest)
        return latest
    }

    private suspend fun refreshChildren(caseId: UUID) {
        runCatchingNonCancel { loadChildren(listOf(caseId)) }
        if (caseById(caseId)?.isRevealed == true) loadJurorReviews(listOf(caseId))
    }

    /** `juror_reviews` are only readable once a case is revealed; fetched then (and on reveal). */
    private suspend fun loadJurorReviews(caseIds: List<UUID>) {
        val backend = backend ?: return
        if (caseIds.isEmpty()) return
        val rows = runCatchingNonCancel {
            backend.client.from("juror_reviews").select { filter { isIn("case_id", caseIds.map(UUID::toString)) } }.list(JurorReview.serializer())
        } ?: return
        val idSet = caseIds.toSet()
        jurorReviews = jurorReviews.filter { !idSet.contains(it.caseId) } + rows
    }

    private suspend fun resolveExhibitURLs(list: List<Exhibit>) {
        val backend = backend ?: return
        val missing = list.filter { it.storagePath != null && exhibitURLs[it.id] == null }
        if (missing.isEmpty()) return
        val urls = backend.storage.signedURLs(missing.mapNotNull { it.storagePath })
        val next = exhibitURLs.toMutableMap()
        for (e in missing) {
            val p = e.storagePath ?: continue
            urls[p]?.let { next[e.id] = it }
        }
        exhibitURLs = next
    }

    suspend fun reset() {
        demo?.cancelAll()
        stopRealtime()
        me = null; partner = null; couple = null; cases = emptyList(); exhibits = emptyList(); turns = emptyList()
        verdicts = emptyList(); jurorReviews = emptyList()
        judgements = emptyMap(); judgementOptionSets = emptyMap(); settlements = emptyMap(); offers = emptyMap()
        pastCoupleIds = emptyList(); formerPartners = emptyList()
        exhibitURLs = emptyMap(); hasLoaded = false; loadError = null; linkCelebration = false; userId = null
        backend?.storage?.clearCache()
    }

    // MARK: Realtime

    private fun <T : PostgresAction> RealtimeChannel.changes(flow: Flow<T>, handle: suspend (T) -> Unit): Job =
        scope.launch { flow.collect { handle(it) } }

    private suspend fun startRealtime(coupleId: UUID) {
        val backend = backend ?: return
        stopRealtime()
        val channel = backend.client.channel("couple-${coupleId.toString().lowercase()}")
        val caseChanges = channel.postgresChangeFlow<PostgresAction>("public") {
            table = "cases"; filter("couple_id", FilterOperator.EQ, coupleId.toString())
        }
        val coupleChanges = channel.postgresChangeFlow<PostgresAction>("public") {
            table = "couples"; filter("id", FilterOperator.EQ, coupleId.toString())
        }
        val profileChanges = channel.postgresChangeFlow<PostgresAction>("public") {
            table = "profiles"; filter("couple_id", FilterOperator.EQ, coupleId.toString())
        }
        coupleChannel = channel
        coupleTasks = listOf(
            channel.changes(caseChanges) { applyCaseChange(it) },
            channel.changes(coupleChanges) { applyCoupleChange(it) },
            channel.changes(profileChanges) { applyProfileChange(it) },
        )
        runCatchingNonCancel { channel.subscribe(blockUntilSubscribed = false) }
        syncCaseChannel()
    }

    /** The PostgREST `in` list for a Realtime filter: `(id1,id2,…)`. */
    private fun inList(ids: Set<UUID>): String = ids.map { it.toString().lowercase() }.sorted().joinToString(",", "(", ")")

    /**
     * Turns / exhibits / verdicts have no couple_id, so they're filtered by the ids of the
     * couple's live cases. Re-subscribes whenever that id set changes.
     */
    private suspend fun syncCaseChannel() {
        val backend = backend ?: return
        syncJudgementChannel()
        syncSettlementChannel()
        val ids = cases.filter { it.status.isOpen }.map { it.id }.toSet()
        if (ids == caseChannelIds && caseChannel != null) return
        caseTasks.forEach { it.cancel() }; caseTasks = emptyList()
        caseChannel?.let { runCatchingNonCancel { backend.client.realtime.removeChannel(it) } }
        caseChannel = null
        caseChannelIds = ids
        if (ids.isEmpty()) return
        val filterValue = inList(ids)
        val channel = backend.client.channel("cases-${UUID.randomUUID().toString().lowercase()}")
        val turnChanges = channel.postgresChangeFlow<PostgresAction>("public") { table = "turns"; filter("case_id", FilterOperator.IN, filterValue) }
        val exhibitChanges = channel.postgresChangeFlow<PostgresAction>("public") { table = "exhibits"; filter("case_id", FilterOperator.IN, filterValue) }
        val verdictChanges = channel.postgresChangeFlow<PostgresAction>("public") { table = "verdicts"; filter("case_id", FilterOperator.IN, filterValue) }
        caseChannel = channel
        caseTasks = listOf(
            channel.changes(turnChanges) { a -> turns = applyRow(a, turns, Turn.serializer()) { it.id } },
            channel.changes(exhibitChanges) { applyExhibitChange(it) },
            channel.changes(verdictChanges) { a -> verdicts = applyRow(a, verdicts, Verdict.serializer()) { it.id } },
        )
        runCatchingNonCancel { channel.subscribe(blockUntilSubscribed = false) }
    }

    /**
     * `judgements` outlive the case (verdict → closed after 24 h, a judgement can be due in 7 days), so
     * they get their own channel over live cases plus every case whose judgement is still in play.
     */
    private suspend fun syncJudgementChannel() {
        val backend = backend ?: return
        val ids = cases.filter { it.status.isOpen }.map { it.id }.toMutableSet()
        for ((id, j) in judgements) if (j.status != JudgementStatus.served && j.status != JudgementStatus.declined) ids.add(id)
        if (ids == judgementChannelIds && judgementChannel != null) return
        judgementTasks.forEach { it.cancel() }; judgementTasks = emptyList()
        judgementChannel?.let { runCatchingNonCancel { backend.client.realtime.removeChannel(it) } }
        judgementChannel = null
        judgementChannelIds = ids
        if (ids.isEmpty()) return
        val channel = backend.client.channel("judgements-${UUID.randomUUID().toString().lowercase()}")
        val changes = channel.postgresChangeFlow<PostgresAction>("public") { table = "judgements"; filter("case_id", FilterOperator.IN, inList(ids)) }
        judgementChannel = channel
        judgementTasks = listOf(channel.changes(changes) { applyJudgementChange(it) })
        runCatchingNonCancel { channel.subscribe(blockUntilSubscribed = false) }
    }

    private suspend fun applyJudgementChange(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert, is PostgresAction.Update -> {
                val row = action.decoded(Judgement.serializer()) ?: return
                val previous = judgements[row.caseId]
                judgements = judgements + (row.caseId to row)
                // Delivery writes a judge turn; closed cases are outside the turns channel, so pull it.
                if (previous?.status != row.status && row.status == JudgementStatus.delivered) {
                    runCatchingNonCancel { loadChildren(listOf(row.caseId)) }
                }
                if (row.status == JudgementStatus.pendingSelection && isMine(row) && judgementOptionSets[row.caseId] == null) {
                    loadJudgementOptions(row.caseId)
                }
            }
            is PostgresAction.Delete -> action.oldRecord.uuid("case_id")?.let { judgements = judgements - it }
            else -> Unit
        }
    }

    private suspend fun stopRealtime() {
        coupleTasks.forEach { it.cancel() }; coupleTasks = emptyList()
        caseTasks.forEach { it.cancel() }; caseTasks = emptyList()
        judgementTasks.forEach { it.cancel() }; judgementTasks = emptyList()
        settlementTasks.forEach { it.cancel() }; settlementTasks = emptyList()
        backend?.let { backend ->
            for (c in listOfNotNull(settlementChannel, coupleChannel, caseChannel, judgementChannel)) {
                runCatchingNonCancel { backend.client.realtime.removeChannel(c) }
            }
        }
        coupleChannel = null; caseChannel = null; caseChannelIds = emptySet()
        judgementChannel = null; judgementChannelIds = emptySet()
        settlementChannel = null; settlementChannelKey = emptySet()
    }

    private suspend fun applyCaseChange(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert, is PostgresAction.Update -> {
                val row = action.decoded(Case.serializer()) ?: return
                val previous = caseById(row.id)
                upsert(row)
                // `cases.settlement_id` flips when a settlement opens or resolves: pull it if Realtime lags.
                if (previous?.settlementId != row.settlementId || (previous?.status != row.status && row.status == CaseStatus.closedSettled)) {
                    loadSettlements(listOf(row.id))
                }
                // panel_progress ticks arrive here too; they only need the row itself.
                if (previous == null || previous.status != row.status || previous.phase != row.phase) {
                    refreshChildren(row.id)
                }
                syncCaseChannel()
            }
            is PostgresAction.Delete -> action.oldRecord.uuid("id")?.let { id -> cases = cases.filter { it.id != id } }
            else -> Unit
        }
    }

    private fun applyCoupleChange(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert, is PostgresAction.Update -> action.decoded(Couple.serializer())?.let(::applyCouple)
            is PostgresAction.Delete -> scope.launch { refresh() }
            else -> Unit
        }
    }

    private fun applyCouple(new: Couple?) {
        val wasLinked = couple?.isLinked ?: false
        val hadCouple = couple != null
        couple = new
        if (hadCouple && !wasLinked && new?.isLinked == true) {
            linkCelebration = true
            scope.launch { refreshPartner() }
        }
    }

    /**
     * Realtime payloads for `profiles` carry only the columns this role may read (never push token /
     * time zone), and may omit others; rather than trust the payload, re-read the row through
     * `profiles_public`.
     */
    private suspend fun applyProfileChange(action: PostgresAction) {
        val backend = backend ?: return
        val me = me ?: return
        val id: UUID? = when (action) {
            is PostgresAction.Insert -> action.record.uuid("id")
            is PostgresAction.Update -> action.record.uuid("id")
            is PostgresAction.Delete -> action.oldRecord.uuid("id")
            else -> null
        }
        if (id == me.id) {
            val fresh = runCatchingNonCancel { backend.profiles.fetchOwnProfile(me.id) } ?: return
            val coupleChanged = fresh.profile.coupleId != me.coupleId
            applyOwn(fresh)
            if (coupleChanged) loadCouple()
        } else {
            refreshPartner()
        }
    }

    private suspend fun applyExhibitChange(action: PostgresAction) {
        exhibits = applyRow(action, exhibits, Exhibit.serializer()) { it.id }
        action.decoded(Exhibit.serializer())?.let { resolveExhibitURLs(listOf(it)) }
    }

    private fun <T> applyRow(action: PostgresAction, list: List<T>, serializer: KSerializer<T>, id: (T) -> UUID): List<T> =
        when (action) {
            is PostgresAction.Insert, is PostgresAction.Update -> {
                val row = action.decoded(serializer)
                if (row == null) list
                else {
                    val i = list.indexOfFirst { id(it) == id(row) }
                    if (i >= 0) list.toMutableList().also { it[i] = row } else list + row
                }
            }
            is PostgresAction.Delete -> action.oldRecord.uuid("id")?.let { gone -> list.filter { id(it) != gone } } ?: list
            else -> list
        }

    private suspend fun refreshPartner() {
        val backend = backend ?: return
        val me = me ?: return
        val coupleId = couple?.id ?: me.coupleId ?: return
        partner = runCatchingNonCancel { backend.profiles.fetchPartner(coupleId, me.id) }
    }

    private fun upsert(row: Case) {
        val i = cases.indexOfFirst { it.id == row.id }
        cases = if (i >= 0) cases.toMutableList().also { it[i] = row } else listOf(row) + cases
    }

    /** Merge an edge-function result and pull the rows it produced. */
    private suspend fun merge(row: Case): Case {
        upsert(row)
        refreshChildren(row.id)
        syncCaseChannel()
        return row
    }

    // MARK: Profile & couple

    suspend fun saveProfile(displayName: String, avatar: Avatar) {
        val userId = userId ?: return
        val backend = backend ?: run {
            me = Profile(id = userId, displayName = displayName, avatar = avatar, coupleId = me?.coupleId)
            return
        }
        me = backend.profiles.upsertProfile(userId, displayName, avatar)
        onProfileSaved?.invoke()
    }

    /** Updates my own `profiles.avatar_json` (allowed solo too). */
    suspend fun setAvatar(avatar: Avatar) {
        val me = me ?: return
        val backend = backend ?: run { this.me = me.copy(avatar = avatar); return }
        this.me = backend.profiles.setAvatar(me.id, avatar)
    }

    /**
     * My partner's name for display: their real display name once linked, else null (callers show generic
     * copy). Amendment aw: onboarding no longer asks for a name, so `profiles.partner_name_temp` is not read.
     */
    val partnerDisplayName: String?
        get() {
            val p = partner?.displayName
            if (couple?.isLinked == true && !p.isNullOrEmpty()) return p
            return null
        }

    /** Onboarding partner step: "together since" (couple). Amendment aw: no temporary partner name is written. */
    suspend fun saveTogetherSince(togetherSince: Instant?) {
        val backend = backend ?: run {
            couple = couple?.copy(togetherSince = togetherSince)
            return
        }
        val couple = couple
        if (couple != null && couple.togetherSince != togetherSince) {
            this.couple = backend.profiles.setTogetherSince(couple.id, togetherSince)
        }
    }

    /** Onboarding screen 9: `profiles.onboarding_completed_at`. */
    suspend fun markOnboardingCompleted() {
        val me = me ?: return
        if (me.onboardingCompletedAt != null) return
        val backend = backend ?: run { this.me = me.copy(onboardingCompletedAt = Instant.now()); return }
        this.me = backend.profiles.markOnboardingCompleted(me.id)
    }

    suspend fun createCouple(): Couple {
        couple?.let { return it }
        val backend = backend ?: run {
            // Demo mode: a fresh, unlinked, unpaid couple with an invite code.
            val me = me
            if (demo != null && me != null) {
                val made = Couple(id = UUID.randomUUID(), inviteCode = "PLD4X9", inviteExpiresAt = Instant.now().plusSeconds(7 * 86_400))
                couple = made
                this.me = me.copy(coupleId = made.id)
                return made
            }
            throw EdgeError(code = "preview", message = "Preview")
        }
        val result = backend.edge.createCouple()
        couple = result.couple
        me = me?.copy(coupleId = result.couple.id)
        startRealtime(result.couple.id)
        return result.couple
    }

    /**
     * `join_couple`. `celebrate: false` (the onboarding partner step, amendment as) skips the full-screen
     * "legally bound" cover: that step shows its own linked state.
     */
    suspend fun joinCouple(code: String, celebrate: Boolean = true) {
        val backend = backend ?: run {
            if (demo != null && me != null) {
                demoJoin(code, celebrate); return
            }
            throw EdgeError(code = "preview", message = "Preview")
        }
        val previousCoupleId = couple?.id
        backend.edge.joinCouple(code)
        userId?.let { applyOwn(backend.profiles.fetchOwnProfile(it)) }
        val wasLinked = couple?.isLinked ?: false
        // Joined from my own (empty) invite couple, which the server removed: listen to the new couple instead.
        if (previousCoupleId != null && me?.coupleId != previousCoupleId) stopRealtime()
        loadCouple()
        // The joiner gets the celebration directly (their couple row arrives already linked).
        if (celebrate && !wasLinked && couple?.isLinked == true) linkCelebration = true
    }

    /** Demo `join_couple` outcome (amendment as): `AWDemoJoin paid|unpaid|invalid|limited`, default paid. */
    var demoJoinOutcome: String? = app.plead.android.app.DemoHarness.demoJoin

    /**
     * Demo mode (no backend): joins Alex's couple (paid by Alex unless `unpaid`), replacing my own solo invite
     * the way `couple_join` does, or fails like the server would.
     */
    private suspend fun demoJoin(code: String, celebrate: Boolean) {
        delay(400)
        val couple = couple
        if (couple != null && couple.inviteCode == code.uppercase()) {
            throw EdgeError(code = "wrong_state", message = "you cannot join your own invite")
        }
        if (couple?.isLinked == true) throw EdgeError(code = "wrong_state", message = "leave your current couple first")
        when (demoJoinOutcome) {
            "invalid" -> throw EdgeError(code = "invalid_code", message = "invite code unknown or expired")
            "limited" -> throw EdgeError(code = "too_many_attempts", message = "Too many invite codes tried.")
        }
        val now = Instant.now()
        var joined = PreviewData.couple.copy(
            id = UUID.randomUUID(), inviteCode = code.uppercase(), inviteExpiresAt = now, linkedAt = now,
            createdAt = now.minusSeconds(86_400),
        )
        if (demoJoinOutcome == "unpaid") joined = joined.copy(premiumUntil = null, payerUserId = null)
        val alex = PreviewData.partner.copy(coupleId = joined.id)
        me = me?.copy(coupleId = joined.id)
        this.couple = joined
        partner = alex
        if (celebrate) linkCelebration = true
    }

    /**
     * Unlink. The server closes `verdict`/`appeal` cases (verdict kept) and mistrials the rest; the
     * old couple joins `past_couple_ids`, so the refetch below shows those cases as history.
     */
    suspend fun leaveCouple() {
        val backend = backend ?: return
        val result = backend.edge.leaveCouple()
        stopRealtime()
        userId?.let { applyOwn(backend.profiles.fetchOwnProfile(it)) }
        // Belt and braces: keep the couple just left in the history even if the profile read raced.
        val left = result.leftCoupleId ?: couple?.id
        if (left != null && !pastCoupleIds.contains(left) && me?.coupleId != left) pastCoupleIds = pastCoupleIds + left
        loadCouple()
    }

    // MARK: Account

    /**
     * `delete_account` (CONTRACTS-v2 amendment i): the server anonymises this user's side of the
     * shared history and deletes the auth user. Demo / preview mode (no backend) simulates success.
     */
    suspend fun deleteAccount() {
        val backend = backend ?: run { delay(900); return }
        backend.edge.deleteAccount()
    }

    // MARK: Case transitions

    /** Uploads image exhibits to `{couple}/pending/{uuid}`, returns inline payloads. */
    private suspend fun stage(drafts: List<DraftExhibit>): List<ExhibitPayload> {
        val backend = backend ?: return emptyList()
        val coupleId = couple?.id ?: return emptyList()
        val payloads = mutableListOf<ExhibitPayload>()
        for ((i, d) in drafts.withIndex()) {
            var path: String? = null
            val data = d.imageData
            if (d.needsUpload && data != null) {
                path = backend.storage.upload(data, StorageService.pendingPath(coupleId, d.id))
            }
            payloads.add(
                ExhibitPayload(
                    label = drafts.label(i), type = d.type, caption = d.caption, body = d.body, storagePath = path,
                    occurredAt = if (DraftExhibit.supportsDate(d.type)) d.occurredAt else null,
                ),
            )
        }
        return payloads
    }

    suspend fun fileCase(title: String, charge: String, remedy: String, exhibits: List<DraftExhibit>): Case {
        val backend = backend ?: run {
            demo?.let { return it.fileCase(title, charge, remedy, exhibits) }
            throw EdgeError(code = "preview", message = "Preview")
        }
        val payloads = stage(exhibits)
        return merge(backend.edge.fileCase(title, charge, remedy, payloads))
    }

    suspend fun enterPlea(plea: Plea, kase: Case) {
        val backend = backend ?: run { demo?.enterPlea(plea, kase.id); return }
        merge(backend.edge.enterPlea(kase.id, plea))
    }

    suspend fun fileDefence(kase: Case, statement: String, counterClaim: String?, exhibits: List<DraftExhibit>, proposedTrialAt: Instant) {
        val backend = backend ?: run {
            demo?.fileDefence(kase.id, statement, counterClaim, exhibits, proposedTrialAt); return
        }
        val payloads = stage(exhibits)
        merge(backend.edge.fileDefence(kase.id, statement, counterClaim, payloads, proposedTrialAt))
    }

    suspend fun proposeTime(date: Instant, kase: Case) {
        val backend = backend ?: run { demo?.proposeTime(date, kase.id); return }
        merge(backend.edge.proposeTime(kase.id, date))
    }

    suspend fun acceptTime(kase: Case) {
        val backend = backend ?: run { demo?.acceptTime(kase.id); return }
        merge(backend.edge.acceptTime(kase.id))
    }

    suspend fun withdraw(kase: Case) {
        val backend = backend ?: return
        merge(backend.edge.withdrawCase(kase.id))
    }

    suspend fun requestDefault(kase: Case) {
        val backend = backend ?: return
        merge(backend.edge.requestDefault(kase.id))
    }

    suspend fun submitTurn(caseId: UUID, body: String, exhibitId: UUID?) {
        val backend = backend ?: run { demo?.submitTurn(caseId, body, exhibitId); return }
        merge(backend.edge.submitTurn(caseId, body, exhibitId))
    }

    suspend fun raiseObjection(caseId: UUID, exhibitId: UUID, reason: ObjectionReason?) {
        val backend = backend ?: run { demo?.raiseObjection(caseId, exhibitId, reason); return }
        merge(backend.edge.raiseObjection(caseId, exhibitId, reason))
    }

    // MARK: Judgement transitions (amendment j)

    /** SUGGEST ANOTHER: a fresh option set (max `Judgement.maxRerolls`; `limit_rerolls` after that). */
    suspend fun rerollJudgement(caseId: UUID): JudgementOptionSet? {
        val set: JudgementOptionSet?
        val backend = backend
        if (backend != null) {
            val result = backend.edge.rerollJudgement(caseId)
            result.options?.let { judgementOptionSets = judgementOptionSets + (caseId to it) }
            // The table is the source of truth for the round the server will accept.
            set = loadJudgementOptions(caseId)
            val j = judgements[caseId]
            val round = set?.round
            if (j != null && round != null && round > j.rerolls) judgements = judgements + (caseId to j.copy(rerolls = round))
            refreshJudgement(caseId)
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            set = demo.rerollJudgement(caseId)
        }
        trackJudgement("judgement_rerolled", caseId, mapOf("round" to (set?.round ?: judgements[caseId]?.rerolls ?: 0).toString()))
        return set
    }

    /** DELIVER JUDGEMENT: persists the chooser's pick; the judge reads it in the courtroom. */
    suspend fun selectJudgement(caseId: UUID, optionId: String) {
        val before = judgements[caseId]
        val option = judgementOptionSets[caseId]?.options?.firstOrNull { it.id == optionId }
        val backend = backend
        if (backend != null) {
            val row = backend.edge.selectJudgement(caseId, optionId)
            judgements = judgements + (caseId to row)
            runCatchingNonCancel { loadChildren(listOf(caseId)) } // the delivery turn
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            demo.selectJudgement(caseId, optionId)
        }
        val selected = judgements[caseId]?.selected ?: option
        trackJudgement("judgement_option_selected", caseId, mapOf("option_type" to (selected?.type?.rawValue ?: "unknown")))
        trackJudgement("judgement_delivered", caseId)
        elapsedSeconds(before?.createdAt, judgements[caseId]?.selectedAt ?: Instant.now())?.let {
            trackJudgement("time_verdict_to_selection", caseId, mapOf("seconds" to it))
        }
    }

    /** ACCEPT JUDGEMENT / Decline (the other party, once delivered). */
    suspend fun respondJudgement(caseId: UUID, accept: Boolean) {
        val backend = backend
        if (backend != null) {
            judgements = judgements + (caseId to backend.edge.respondJudgement(caseId, accept))
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            demo.respondJudgement(caseId, accept)
        }
        trackJudgement(if (accept) "judgement_accepted" else "judgement_declined", caseId)
    }

    /** MARK AS SERVED (either partner, once delivered or accepted). */
    suspend fun markServed(caseId: UUID) {
        val backend = backend
        if (backend != null) {
            judgements = judgements + (caseId to backend.edge.markServed(caseId))
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            demo.markServed(caseId)
        }
        trackJudgement("judgement_marked_served", caseId)
        val j = judgements[caseId]
        if (j != null) {
            elapsedSeconds(j.selectedAt, j.servedAt ?: Instant.now())?.let {
                trackJudgement("time_selection_to_served", caseId, mapOf("seconds" to it))
            }
        }
    }

    private fun trackJudgement(event: String, caseId: UUID, props: Map<String, String> = emptyMap()) {
        val all = props.toMutableMap()
        all["case_id"] = caseId.toString().lowercase()
        judgements[caseId]?.theme?.let { all["case_theme"] = it }
        Analytics.track(event, all)
    }

    /** The server answered `premium_required`: drop the stale premium flag so the gate shows. */
    fun markPremiumLapsed() {
        val c = couple ?: return
        if (!c.isPremium) return
        couple = c.copy(premiumUntil = null)
    }

    /** After a purchase, the webhook updates `couples.premium_until`; poll briefly until it lands. */
    suspend fun awaitPremium(timeout: Duration = Duration.ofSeconds(20)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!isPremium && System.nanoTime() < deadline) {
            delay(2_000)
            val backend = backend
            val id = couple?.id
            if (backend != null && id != null) runCatchingNonCancel { backend.profiles.fetchCouple(id) }?.let { couple = it }
        }
    }

    // MARK: - Settle Outside Court (CONTRACTS-v2 amendment n)

    // MARK: Store API (consumed by the docket / courtroom)

    /** The latest settlement attempt on a case (pending, or how the last one ended). */
    fun settlement(caseId: UUID): Settlement? = settlements[caseId]

    /** The latest attempt's offers, in round order. */
    fun settlementOffers(caseId: UUID): List<SettlementOffer> {
        val s = settlements[caseId] ?: return emptyList()
        return offers[s.id] ?: emptyList()
    }

    /** The current offer of the latest settlement (the accepted terms once settled). */
    fun latestOffer(caseId: UUID): SettlementOffer? {
        val s = settlements[caseId] ?: return null
        val list = offers[s.id] ?: emptyList()
        val accepted = s.acceptedOfferId
        if (accepted != null) list.firstOrNull { it.id == accepted }?.let { return it }
        return list.maxWithOrNull(compareBy<SettlementOffer> { it.roundNumber }.thenBy { it.createdAt })
    }

    /** An offer awaits my response: the settlement is pending and its latest offer was not proposed by me. */
    fun pendingSettlementForMe(caseId: UUID): Boolean {
        val me = me ?: return false
        val kase = caseById(caseId) ?: return false
        if (!kase.status.isOpen || kase.role(me.id) == null) return false
        return SettlementRules.awaitsResponse(me.id, settlements[caseId], latestOffer(caseId))
    }

    /** Either partner, summoned / defence / scheduling / trial before `plaintiff_closing`, no pending settlement. */
    fun canProposeSettlement(caseId: UUID): Boolean {
        val kase = caseById(caseId) ?: return false
        return SettlementRules.canPropose(kase, me?.id, settlements[caseId])
    }

    /** A settlement is pending on the case (from the settlement row or the case's `settlement_id` pointer). */
    fun hasPendingSettlement(caseId: UUID): Boolean =
        settlements[caseId]?.isPending == true || caseById(caseId)?.settlementId != null

    /** I sent the current offer and am waiting for my partner. */
    fun isAwaitingSettlementResponse(caseId: UUID): Boolean {
        val me = me ?: return false
        val s = settlements[caseId] ?: return false
        if (!s.isPending || caseById(caseId)?.role(me.id) == null) return false
        return !SettlementRules.awaitsResponse(me.id, s, latestOffer(caseId))
    }

    /** The first open case whose settlement offer awaits me (oldest offer first). */
    val settlementPrompt: SettlementPrompt?
        get() = cases.filter { pendingSettlementForMe(it.id) }
            .mapNotNull { c -> settlements[c.id]?.let { c to it } }
            .minByOrNull { it.second.updatedAt }
            ?.let { SettlementPrompt(caseId = it.first.id, settlementId = it.second.id, round = it.second.currentRound) }

    /** Closed cases settled out of court with the agreement still outstanding (Home / docket). */
    val outstandingSettlementCases: List<Case>
        get() {
            val me = me ?: return emptyList()
            return cases.filter { it.status == CaseStatus.closedSettled && it.role(me.id) != null && settlements[it.id]?.status == SettlementStatus.accepted }
                .sortedBy { settlements[it.id]?.dueAt ?: Instant.MAX }
        }

    /** A settlement accepted in the last day whose "SETTLED OUT OF COURT" moment this device hasn't shown. */
    val settlementToCelebrate: Settlement?
        get() {
            val me = me ?: return null
            val since = Instant.now().minusSeconds(86_400)
            return settlements.values
                .filter { s ->
                    s.status == SettlementStatus.accepted && !celebratedSettlementIds.contains(s.id) &&
                        (s.acceptedAt ?: Instant.MIN).isAfter(since) && caseById(s.caseId)?.role(me.id) != null
                }
                .minByOrNull { it.acceptedAt ?: Instant.MIN }
        }

    fun markSettlementCelebrated(settlementId: UUID) {
        if (celebratedSettlementIds.contains(settlementId)) return
        celebratedSettlementIds = celebratedSettlementIds + settlementId
        if (backend != null) {
            UserDefaults.standard.set(celebratedSettlementIds.map { it.uuidString }, celebratedKey)
        }
    }

    // MARK: Transitions

    /**
     * `generate_settlement_options`: three suggestions (quick / fair / peace). Never blocks: the
     * server tops up from a curated pool when the model is unavailable.
     */
    suspend fun generateSettlementOptions(caseId: UUID): List<SettlementSuggestion> {
        val started = Instant.now()
        val options: List<SettlementSuggestion>
        var source: String? = null
        val backend = backend
        if (backend != null) {
            val result = backend.edge.generateSettlementOptions(caseId)
            options = result.options; source = result.source
            result.summary?.let { settlementSummaries = settlementSummaries + (caseId to it) }
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            options = demo.generateSettlementOptions(caseId)
        }
        val fallback = options.isNotEmpty() && options.all { it.generic == true }
        trackSettlement(
            "settlement_options_generated", caseId,
            mapOf(
                "case_category" to (options.firstOrNull()?.category ?: "general"),
                "source" to (source ?: if (fallback) "fallback" else "model"),
                "latency_ms" to Duration.between(started, Instant.now()).toMillis().toString(),
            ),
        )
        return options
    }

    /**
     * `propose_settlement` (either partner). Pauses the court's timer until the offer resolves.
     * `source: .ai` must carry the suggestion's id and its exact body (else the server treats it as
     * custom); custom terms default to 7 days.
     */
    suspend fun proposeSettlement(caseId: UUID, body: String, source: SettlementSource, suggestionId: String? = null, dueDays: Int? = null) {
        val text = if (source == SettlementSource.ai) body else body.trim()
        val days = dueDays ?: if (source == SettlementSource.custom) SettlementRules.customDefaultDueDays else null
        val backend = backend
        if (backend != null) {
            val result = try {
                backend.edge.proposeSettlement(caseId, text, source, suggestionId, days)
            } catch (e: EdgeError) {
                if (e.isSafetyStop) refreshAfterSafetyStop(caseId)
                throw e
            }
            applySettlementResult(result, caseId)
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            demo.proposeSettlement(caseId, text, source, days ?: SettlementRules.customDefaultDueDays)
        }
        trackSettlement(
            "settlement_offer_sent", caseId,
            mapOf(
                "round" to (settlements[caseId]?.currentRound ?: 1).toString(), "source" to source.rawValue,
                "category" to (latestOffer(caseId)?.category ?: "custom"),
            ),
        )
    }

    /** `counter_settlement` (the receiver, below round 3). New terms replace the offer; roles swap. */
    suspend fun counterSettlement(caseId: UUID, body: String, source: SettlementSource, dueDays: Int? = null) {
        val current = settlements[caseId]
        if (current == null || !current.isPending) throw EdgeError(code = "wrong_state", message = "This settlement has moved on.")
        if (!SettlementRules.canCounter(current)) {
            throw EdgeError(code = "limit_rounds", message = EdgeErrors.settlementRoundLimitMessage)
        }
        val text = if (source == SettlementSource.ai) body else body.trim()
        val days = dueDays ?: if (source == SettlementSource.custom) SettlementRules.customDefaultDueDays else null
        val backend = backend
        if (backend != null) {
            val result = try {
                backend.edge.counterSettlement(current.id, text, source, days)
            } catch (e: EdgeError) {
                if (e.isSafetyStop) refreshAfterSafetyStop(caseId)
                throw e
            }
            applySettlementResult(result, caseId)
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            demo.counterSettlement(caseId, text, source, days ?: SettlementRules.customDefaultDueDays)
        }
        val round = (settlements[caseId]?.currentRound ?: (current.currentRound + 1)).toString()
        trackSettlement("settlement_countered", caseId, mapOf("round" to round))
        trackSettlement(
            "settlement_offer_sent", caseId,
            mapOf("round" to round, "source" to source.rawValue, "category" to (latestOffer(caseId)?.category ?: "custom")),
        )
    }

    /**
     * `respond_to_settlement` (the receiver). Accept closes the case as `closed_settled`; reject
     * resumes the court exactly where it paused.
     */
    suspend fun respondToSettlement(caseId: UUID, accept: Boolean) {
        val current = settlements[caseId]
        if (current == null || !current.isPending) throw EdgeError(code = "wrong_state", message = "This settlement has moved on.")
        val returnState = current.pausedFromStatus?.rawValue ?: caseById(caseId)?.status?.rawValue ?: "unknown"
        val backend = backend
        if (backend != null) {
            val result = backend.edge.respondToSettlement(current.id, accept)
            applySettlementResult(result, caseId)
            if (accept && settlements[caseId]?.status != SettlementStatus.accepted) loadSettlements(listOf(caseId))
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            demo.respondToSettlement(caseId, accept)
        }
        if (accept) {
            val props = mutableMapOf("round" to current.currentRound.toString())
            elapsedSeconds(current.createdAt, settlements[caseId]?.acceptedAt ?: Instant.now())?.let { props["time_to_agreement"] = it }
            trackSettlement("settlement_accepted", caseId, props)
        } else {
            trackSettlement("settlement_rejected", caseId, mapOf("round" to current.currentRound.toString(), "return_state" to returnState))
        }
    }

    /** `withdraw_settlement` (the proposer of the current offer). The court resumes. */
    suspend fun withdrawSettlement(caseId: UUID) {
        val current = settlements[caseId]
        if (current == null || !current.isPending) throw EdgeError(code = "wrong_state", message = "This settlement has moved on.")
        val backend = backend
        if (backend != null) {
            val result = backend.edge.withdrawSettlement(current.id)
            applySettlementResult(result, caseId)
            if (settlements[caseId]?.isPending == true) loadSettlements(listOf(caseId))
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            demo.withdrawSettlement(caseId)
        }
        trackSettlement("settlement_withdrawn", caseId, mapOf("round" to current.currentRound.toString()))
    }

    /** `mark_settlement_fulfilled` (either partner, once accepted). Honour-based; one tap is enough. */
    suspend fun markSettlementFulfilled(caseId: UUID) {
        val current = settlements[caseId]
        if (current == null || current.status != SettlementStatus.accepted) throw EdgeError(code = "wrong_state", message = "This settlement has moved on.")
        val backend = backend
        if (backend != null) {
            val result = backend.edge.markSettlementFulfilled(current.id)
            applySettlementResult(result, caseId)
            if (settlements[caseId]?.status != SettlementStatus.fulfilled) loadSettlements(listOf(caseId))
        } else {
            val demo = demo ?: throw EdgeError(code = "preview", message = "Preview")
            demo.markSettlementFulfilled(caseId)
        }
        val props = mutableMapOf<String, String>()
        current.acceptedAt?.let { accepted ->
            val seconds = Duration.between(accepted, settlements[caseId]?.fulfilledAt ?: Instant.now()).seconds
            props["days_to_fulfilment"] = max(0L, seconds / 86_400).toString()
        }
        trackSettlement("settlement_fulfilled", caseId, props)
    }

    /** `settlement_entry_tapped` (Summons third path, courtroom case actions, Home). */
    fun trackSettlementEntry(caseId: UUID, entryPoint: String? = null) {
        val status = caseById(caseId)?.status
        trackSettlement(
            "settlement_entry_tapped", caseId,
            mapOf(
                "case_state" to (status?.rawValue ?: "unknown"),
                "entry_point" to (entryPoint ?: status?.let(SettlementRules::entryPoint) ?: "trial"),
            ),
        )
    }

    fun trackSettlement(event: String, caseId: UUID, props: Map<String, String> = emptyMap()) {
        val all = props.toMutableMap()
        all["case_id"] = caseId.toString().lowercase()
        Analytics.track(event, all)
    }

    /**
     * `safety_stop`: the safety valve turned the case into a mistrial. Re-read the case (and its
     * safety turn) so the app shows the same support flow as everywhere else.
     */
    private suspend fun refreshAfterSafetyStop(caseId: UUID) {
        val backend = backend ?: return
        val rows = runCatchingNonCancel {
            backend.client.from("cases").select { filter { eq("id", caseId.toString()) } }.list(Case.serializer())
        }
        rows?.firstOrNull()?.let { merge(it) }
        loadSettlements(listOf(caseId))
    }

    // MARK: Loading & Realtime

    /** `settlements` + `settlement_offers` for these cases. Tolerant: never blocks the docket. */
    suspend fun loadSettlements(caseIds: List<UUID>) {
        val backend = backend ?: return
        if (caseIds.isEmpty()) return
        val rows = runCatchingNonCancel {
            backend.client.from("settlements").select {
                filter { isIn("case_id", caseIds.map(UUID::toString)) }
                order("created_at", Order.ASCENDING)
            }.list(Settlement.serializer())
        } ?: return
        var map = settlements
        for (row in rows) map = keepLatest(row, map)
        settlements = map
        loadSettlementOffers(caseIds.mapNotNull { settlements[it]?.id })
        syncSettlementChannel()
    }

    private suspend fun loadSettlementOffers(settlementIds: List<UUID>) {
        val backend = backend ?: return
        if (settlementIds.isEmpty()) return
        val rows = runCatchingNonCancel {
            backend.client.from("settlement_offers").select {
                filter { isIn("settlement_id", settlementIds.map(UUID::toString)) }
                order("round_number", Order.ASCENDING)
            }.list(SettlementOffer.serializer())
        } ?: return
        val grouped = rows.groupBy { it.settlementId }
        val next = offers.toMutableMap()
        for (id in settlementIds) next[id] = (grouped[id] ?: emptyList()).sortedBy { it.roundNumber }
        offers = next
    }

    private suspend fun applySettlementResult(result: EdgeFunctions.SettlementResult, caseId: UUID) {
        result.settlement?.let { settlements = keepLatest(it, settlements) }
        result.offer?.let(::upsertOffer)
        val c = result.kase
        if (c != null) merge(c) else syncSettlementChannel() // merge pulls the judge's flavour turn on accept
        val s = result.settlement
        if (s != null && result.offer == null && offers[s.id] == null) loadSettlementOffers(listOf(s.id))
    }

    private fun upsertOffer(o: SettlementOffer) {
        val list = (offers[o.settlementId] ?: emptyList()).toMutableList()
        val i = list.indexOfFirst { it.id == o.id }
        if (i >= 0) list[i] = o else list.add(o)
        offers = offers + (o.settlementId to list.sortedBy { it.roundNumber })
    }

    /** Settlements of live cases and accepted agreements (fulfilment), plus the offers of those attempts. */
    private suspend fun syncSettlementChannel() {
        val backend = backend ?: return
        val caseIds = cases.filter { it.status.isOpen }.map { it.id }.toMutableSet()
        for ((id, s) in settlements) if (s.status == SettlementStatus.accepted) caseIds.add(id)
        val settlementIds = settlements.values.filter { it.isPending || it.status == SettlementStatus.accepted }.map { it.id }.toSet()
        val key = caseIds + settlementIds
        if (key == settlementChannelKey && settlementChannel != null) return
        settlementTasks.forEach { it.cancel() }; settlementTasks = emptyList()
        settlementChannel?.let { runCatchingNonCancel { backend.client.realtime.removeChannel(it) } }
        settlementChannel = null
        settlementChannelKey = key
        if (caseIds.isEmpty()) return
        val channel = backend.client.channel("settlements-${UUID.randomUUID().toString().lowercase()}")
        val settlementChanges = channel.postgresChangeFlow<PostgresAction>("public") {
            table = "settlements"; filter("case_id", FilterOperator.IN, inList(caseIds))
        }
        val tasks = mutableListOf(channel.changes(settlementChanges) { applySettlementChange(it) })
        if (settlementIds.isNotEmpty()) {
            val offerChanges = channel.postgresChangeFlow<PostgresAction>("public") {
                table = "settlement_offers"; filter("settlement_id", FilterOperator.IN, inList(settlementIds))
            }
            tasks.add(channel.changes(offerChanges) { applyOfferChange(it) })
        }
        settlementChannel = channel
        settlementTasks = tasks
        runCatchingNonCancel { channel.subscribe(blockUntilSubscribed = false) }
    }

    private suspend fun applySettlementChange(action: PostgresAction) {
        val row = action.decoded(Settlement.serializer()) ?: return
        val previous = settlements[row.caseId]
        settlements = keepLatest(row, settlements)
        if (previous?.id != row.id || previous.currentRound != row.currentRound || offers[row.id] == null) {
            loadSettlementOffers(listOf(row.id))
        }
        if (previous?.id == row.id && previous.isPending && row.status == SettlementStatus.expired) {
            trackSettlement("settlement_expired", row.caseId, mapOf("entry_point" to (row.entryPoint ?: "unknown")))
        }
        syncSettlementChannel()
    }

    private fun applyOfferChange(action: PostgresAction) {
        val row = action.decoded(SettlementOffer.serializer()) ?: return
        upsertOffer(row)
    }

    // MARK: - Demo hooks (DemoTrialSimulator; demo mode only — the live app mutates via edge functions + Realtime)

    fun demoUpsertCase(row: Case) = upsert(row)

    fun demoUpsertExhibit(row: Exhibit) {
        val i = exhibits.indexOfFirst { it.id == row.id }
        exhibits = if (i >= 0) exhibits.toMutableList().also { it[i] = row } else exhibits + row
    }

    fun demoAppendTurn(row: Turn) {
        turns = turns + row
    }

    fun demoAddVerdict(verdict: Verdict, reviews: List<JurorReview>) {
        verdicts = verdicts + verdict
        jurorReviews = jurorReviews + reviews
    }

    fun demoSetExhibitURL(url: URI, id: UUID) {
        exhibitURLs = exhibitURLs + (id to url)
    }

    fun demoSetJudgement(row: Judgement) {
        judgements = judgements + (row.caseId to row)
    }

    fun demoSetJudgementOptions(set: JudgementOptionSet) {
        judgementOptionSets = judgementOptionSets + (set.caseId to set)
    }

    fun demoSetSettlement(row: Settlement) {
        settlements = keepLatest(row, settlements)
    }

    fun demoUpsertOffer(row: SettlementOffer) = upsertOffer(row)

    /** Demo account switch (amendment p, "sign in instead"): take over another preview store's account. */
    fun demoAdopt(other: CaseStore) {
        me = other.me; partner = other.partner; couple = other.couple; cases = other.cases
        exhibits = other.exhibits; turns = other.turns; verdicts = other.verdicts; jurorReviews = other.jurorReviews
        exhibitURLs = other.exhibitURLs; judgements = other.judgements; judgementOptionSets = other.judgementOptionSets
        settlements = other.settlements; offers = other.offers
        userId = other.me?.id
        hasLoaded = true
    }

    /** Demo purchase / restore: the couple becomes premium, paid by me. */
    fun demoGrantPremium() {
        val c = couple ?: return
        couple = c.copy(premiumUntil = Instant.now().plusSeconds(365L * 86_400), payerUserId = me?.id)
    }

    /** Preview setters for fixtures that the Swift code mutates in place (`store.me?.coupleId = …`). */
    fun demoSetMe(profile: Profile?) {
        me = profile
    }

    fun demoSetCouple(value: Couple?) {
        couple = value
    }

    companion object {
        /**
         * Whole seconds between two moments, as an analytics value (null without a start, or if negative).
         */
        fun elapsedSeconds(start: Instant?, end: Instant): String? {
            if (start == null || end.isBefore(start)) return null
            return (Duration.between(start, end).toMillis() / 1000.0).roundToLong().toString()
        }

        const val celebratedKey = "aw.settlementsCelebrated"

        fun loadCelebrated(defaults: UserDefaults = UserDefaults.standard): Set<UUID> =
            (defaults.stringArray(celebratedKey) ?: emptyList()).mapNotNull(::parseUUID).toSet()

        /** Keeps the newest attempt per case (a rejected attempt is replaced by a later proposal). */
        fun keepLatest(row: Settlement, map: Map<UUID, Settlement>): Map<UUID, Settlement> {
            val old = map[row.caseId]
            if (old != null && old.id != row.id && old.createdAt.isAfter(row.createdAt)) return map
            return map + (row.caseId to row)
        }
    }
}

/** A settlement offer that awaits my response (drives the response sheet on app / case open). */
data class SettlementPrompt(val caseId: UUID, val settlementId: UUID, val round: Int)


/** The new row for inserts/updates, decoded with the app's tolerant date strategy. */
internal fun <T> PostgresAction.decoded(serializer: KSerializer<T>): T? {
    val record: JsonObject = when (this) {
        is PostgresAction.Insert -> record
        is PostgresAction.Update -> record
        else -> return null
    }
    return runCatching { JSONCoding.json.decodeFromJsonElement(serializer, record) }.getOrNull()
}

private fun JsonObject.uuid(key: String): UUID? = parseUUID((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content)

/** Swift `try?` that still lets cancellation through. */
internal suspend inline fun <T> runCatchingNonCancel(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
