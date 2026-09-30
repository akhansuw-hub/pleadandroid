// Port of ArgueWin/Features/Home/HomeView.swift (the "Store → plan" extension and `ActiveCaseCard` live in HomePlan.kt).
//
// Home (CONTRACTS-v2 amendment af): action-led. Header + greeting, then the primary case file (the
// earliest thing the user must do, `HomePlan.lead`), a one-case docket preview with "All open cases",
// and a smaller "Bring a new case" panel, promoted to the top when nothing is urgent or active.
// Outstanding judgements / agreements stay below. The all-time record lives on Us.
package app.plead.android.features.home

import android.os.Build
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.app.AppTab
import app.plead.android.app.DemoHarness
import app.plead.android.app.TabNavHost
import app.plead.android.designsystem.AWButton
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.AWButtonStyle
import app.plead.android.designsystem.AvatarPair
import app.plead.android.designsystem.CaseFileAction
import app.plead.android.designsystem.CaseFileCard
import app.plead.android.designsystem.Countdown
import app.plead.android.designsystem.CourtTabHeader
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.awBackground
import app.plead.android.designsystem.awCard
import app.plead.android.designsystem.awCourtFile
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.features.casedetail.CaseDetailView
import app.plead.android.features.casedetail.CaseDialog
import app.plead.android.features.casedetail.CaseType
import app.plead.android.features.casedetail.DialogAction
import app.plead.android.features.casedetail.OutstandingJudgementCardSlot
import app.plead.android.features.casedetail.RecordLabel
import app.plead.android.features.casedetail.ScrollAnchors
import app.plead.android.features.casedetail.scrollAnchor
import app.plead.android.features.casedetail.shareText
import app.plead.android.features.casedetail.symbolIcon
import app.plead.android.features.cases.SettlementDocket
import app.plead.android.features.cases.courtFileButtonStyle
import app.plead.android.features.cases.openSettlement
import app.plead.android.models.Case
import app.plead.android.models.EdgeError
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.services.AppConfig
import app.plead.android.services.CaseAction
import app.plead.android.services.CaseStore
import app.plead.android.services.EdgeErrors
import app.plead.android.services.caseFileParties
import app.plead.android.services.caseFileStatus
import app.plead.android.services.docketTitle
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Home tab: Home with case records pushed on top (iOS `NavigationStack(path: $router.homePath)`).
 * The integrator mounts this in place of the Home `TabNavHost` in `MainTabScreen`.
 */
@Composable
fun HomeTab(model: AppModel, modifier: Modifier = Modifier) {
    val router = model.router
    val store = model.store
    Box(modifier.fillMaxSize()) {
        TabNavHost(
            path = router.homePath,
            onPathChange = { router.homePath = it },
            root = { HomeView(store, router) },
            caseDetail = { id ->
                CaseDetailView(id, store, router, modifier = Modifier.statusBarsPadding(), backTitle = "Home") {
                    router.homePath = router.homePath.dropLast(1)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(store: CaseStore, router: AppRouter, modifier: Modifier = Modifier) {
    val reduceMotion = accessibilityReduceMotion()
    val scope = rememberCoroutineScope()
    var blocker by remember { mutableStateOf<String?>(null) }
    var confirmDefaultFor by remember { mutableStateOf<Case?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val anchors = remember { ScrollAnchors() }
    var viewport by remember { mutableIntStateOf(0) }

    val now = Instant.now()
    val items = HomePlan.items(store, now)
    val lead = HomePlan.lead(items)
    val preview = HomePlan.preview(items, lead)
    val partnerName = store.partner?.displayName ?: "your partner"

    fun summon() {
        val b = store.filingBlocker
        if (b != null) blocker = b else router.sheet = AppSheet.fileCase
    }

    fun perform(a: HomePlan.CardAction, kase: Case) {
        actionError = null
        when (val route = a.route) {
            is HomePlan.CardAction.Route.settlement -> router.openSettlement(route.route, kase.id)
            is HomePlan.CardAction.Route.court ->
                if (!router.open(route.action, kase)) {
                    if (route.action == CaseAction.requestDefault) confirmDefaultFor = kase else router.showRecord(kase.id)
                }
        }
    }

    LaunchedEffect(Unit) {
        SettlementDocket.dismissLaunchPromptIfAsked(router)
        // Demo harness: `AWScroll outstanding` jumps to the outstanding-judgement cards for screenshots.
        // `AWScroll agreement` jumps to the outstanding-agreement card (amendment n).
        val anchor = DemoHarness.scroll
        if (anchor != null && anchor in listOf("outstanding", "agreement")) {
            delay(600)
            anchors.scrollTo(anchor, scroll, viewport, center = true)
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; store.refresh(); refreshing = false } },
        modifier = modifier.fillMaxSize().awBackground().statusBarsPadding().onSizeChanged { viewport = it.height },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(top = PleadSpacing.xs)
                .padding(horizontal = PleadSpacing.l)
                .padding(bottom = PleadSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        ) {
            // The header pads 20; line its content up with the column.
            CourtTabHeader(
                title = null,
                showsWordmark = true,
                modifier = Modifier.horizontalBleed(PleadSpacing.l + 4.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .testTag("home.settings")
                        .courtFileButtonStyle(onClick = { router.sheet = AppSheet.settings }, onClickLabel = "Settings")
                        .semantics { contentDescription = "Settings" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(symbolIcon("gearshape"), contentDescription = null, tint = PleadColor.cocoa, modifier = Modifier.size(22.dp))
                }
            }

            HomeGreeting(store.me?.displayName)

            if (store.isSolo) {
                SoloPanel(store)
            } else if (!store.hasLoaded && store.cases.isEmpty() && store.me == null) {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PleadColor.burgundy, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            } else {
                AnimatedContent(
                    targetState = lead,
                    contentKey = { HomePlan.key(it) },
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(tween(200, easing = PleadMotion.easeInOut)) togetherWith fadeOut(tween(200, easing = PleadMotion.easeInOut))
                        } else {
                            (fadeIn(tween(220, easing = PleadMotion.easeOut)) + slideInVertically(tween(220, easing = PleadMotion.easeOut)) { 30 }) togetherWith
                                fadeOut(tween(220, easing = PleadMotion.easeOut))
                        }
                    },
                    label = "homeLead",
                ) { l ->
                    when (l) {
                        is HomePlan.Lead.action -> LeadSection(l.item, urgent = l.item.needsMe, now, store, router, partnerName, actionError, ::perform)
                        is HomePlan.Lead.waiting -> LeadSection(l.item, urgent = l.item.needsMe, now, store, router, partnerName, actionError, ::perform)
                        HomePlan.Lead.newCase -> NewCasePanel(promoted = true, hasOpenCase = store.openCases.isNotEmpty(), store = store, onSummon = ::summon)
                    }
                }

                val previewCase = preview?.let { store.caseById(it.caseId) }
                if (previewCase != null) {
                    DocketPreview(previewCase, store, router)
                } else if (store.openCases.isNotEmpty()) {
                    AllOpenCasesLink(store, router)
                }

                if (lead != HomePlan.Lead.newCase) {
                    NewCasePanel(promoted = false, hasOpenCase = true, store = store, onSummon = ::summon)
                }

                // Amendment l / n: outstanding judgements and agreements (the lead's own card is not repeated).
                val leadId = (lead as? HomePlan.Lead.action)?.item?.takeIf { it.kind != HomeItem.Kind.openCase }?.caseId
                val judgementCases = store.outstandingJudgementCases
                judgementCases.filter { it.id != leadId }.forEach { kase ->
                    store.judgement(kase.id)?.let { judgement ->
                        val anchor = if (kase.id == judgementCases.firstOrNull()?.id) Modifier.scrollAnchor(anchors, "outstanding") else Modifier
                        OutstandingJudgementCardSlot(store, router, kase, judgement, modifier = anchor)
                    }
                }
                val settlementCases = store.outstandingSettlementCases
                settlementCases.filter { it.id != leadId }.forEach { kase ->
                    store.settlement(kase.id)?.let { settlement ->
                        val anchor = if (kase.id == settlementCases.firstOrNull()?.id) Modifier.scrollAnchor(anchors, "agreement") else Modifier
                        OutstandingAgreementCard(kase, settlement, store.latestOffer(kase.id), store, router, modifier = anchor)
                    }
                }

                store.loadError?.let { e -> OfflineNotice(e) { scope.launch { store.refresh() } } }
            }
        }
    }

    blocker?.let { b ->
        CaseDialog("Too many open cases", b, listOf(DialogAction("OK", cancel = true)), onDismiss = { blocker = null })
    }
    confirmDefaultFor?.let { kase ->
        CaseDialog(
            "Request a default judgment?",
            "The defendant missed the 72-hour deadline. The court will rule in your favour.",
            listOf(
                DialogAction("Request default judgment", destructive = true) {
                    actionError = null
                    scope.launch {
                        try { store.requestDefault(kase) } catch (e: Exception) { actionError = (e as? EdgeError)?.message ?: "Couldn't reach the court." }
                    }
                },
            ),
            onDismiss = { confirmDefaultFor = null },
        )
    }
}

/** Swift `.padding(.horizontal, -x)`: lay the child out [bleed] wider on each side than its column. */
private fun Modifier.horizontalBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth + 2 * extra).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth,
        ),
    )
    layout(placeable.width - 2 * extra, placeable.height) { placeable.place(-extra, 0) }
}

// MARK: - Lead

/** Urgent (burgundy) or calm (walnut) label, supporting line, then the file (or the follow-up card). */
@Composable
private fun LeadSection(
    item: HomeItem,
    urgent: Boolean,
    now: Instant,
    store: CaseStore,
    router: AppRouter,
    partnerName: String,
    actionError: String?,
    perform: (HomePlan.CardAction, Case) -> Unit,
) {
    val overdue = item.deadline?.let { !it.isAfter(now) } ?: false
    val label = if (urgent) {
        HomePlan.urgentLabel(item.action, overdue) ?: HomePlan.waitingLabel(item.action, partnerName)
    } else {
        HomePlan.waitingLabel(item.action, partnerName)
    }
    val tint = if (urgent) PleadColor.burgundy else PleadColor.walnut
    Column(Modifier.testTag("home.primary"), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        Column(
            Modifier.testTag("home.primary.label").semantics(mergeDescendants = true) { if (urgent) heading() },
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                Text(label.uppercase(), style = PleadType.labelCapsTracked, color = tint, modifier = Modifier.weight(1f))
                // A calm, continuous countdown, only when the case really has a deadline.
                val deadline = item.deadline
                if (item.kind == HomeItem.Kind.openCase && deadline != null && deadline.isAfter(now)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = "${Countdown.spoken((deadline.toEpochMilli() - now.toEpochMilli()) / 1000.0)} left"
                        },
                    ) {
                        Icon(symbolIcon("clock"), contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                        Countdown(target = deadline, font = PleadType.timerSmall, color = tint)
                    }
                }
            }
            val line = HomePlan.supportingLine(item.action, partnerName)
            if (line != null && item.kind == HomeItem.Kind.openCase) {
                Text(line, style = PleadType.body, color = PleadColor.cocoa)
            }
        }

        when (item.kind) {
            HomeItem.Kind.openCase -> store.caseById(item.caseId)?.let { kase ->
                // The primary case file with its real action, opening the existing flow directly. A case that is
                // waiting on someone else gets no burgundy action: the file itself opens the case.
                val action = if (urgent) HomePlan.cardAction(kase, store) else null
                HomeCaseFile(kase, store, router, action?.let { a -> CaseFileAction(a.label) { perform(a, kase) } })
            }
            HomeItem.Kind.judgement -> {
                val kase = store.caseById(item.caseId)
                val j = kase?.let { store.judgement(it.id) }
                if (kase != null && j != null) OutstandingJudgementCardSlot(store, router, kase, j)
            }
            HomeItem.Kind.agreement -> {
                val kase = store.caseById(item.caseId)
                val s = kase?.let { store.settlement(it.id) }
                if (kase != null && s != null) OutstandingAgreementCard(kase, s, store.latestOffer(kase.id), store, router)
            }
        }
        InlineError(actionError)
    }
}

@Composable
private fun HomeCaseFile(kase: Case, store: CaseStore, router: AppRouter, action: CaseFileAction?, modifier: Modifier = Modifier) {
    CaseFileCard(
        number = kase.caseNumber,
        title = kase.title,
        parties = store.caseFileParties(kase),
        status = store.caseFileStatus(kase),
        modifier = modifier,
        me = store.me?.avatar,
        partner = store.partner?.avatar,
        myRole = store.myRole(kase),
        action = action,
        onOpen = { router.showRecord(kase.id) },
    )
}

// MARK: - Docket preview

@Composable
private fun DocketPreview(kase: Case, store: CaseStore, router: AppRouter) {
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        Text("ON THE DOCKET", style = PleadType.labelCapsTracked, color = PleadColor.walnut, modifier = Modifier.semantics { heading() })
        HomeCaseFile(kase, store, router, action = null, modifier = Modifier.testTag("home.preview"))
        AllOpenCasesLink(store, router)
    }
}

@Composable
private fun AllOpenCasesLink(store: CaseStore, router: AppRouter) {
    val count = store.openCases.size
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .testTag("home.allOpenCases")
            .courtFileButtonStyle(onClick = { router.tab = AppTab.cases })
            .clearAndSetSemantics { contentDescription = "All open cases, $count" },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("All open cases", style = PleadType.uiButtonSecondary, color = PleadColor.burgundy)
        Text("$count", style = PleadType.metadataMedium.monospacedDigit(), color = PleadColor.walnut)
        Spacer(Modifier.weight(1f))
        Icon(symbolIcon("chevron.right"), contentDescription = null, tint = PleadColor.burgundy, modifier = Modifier.size(16.dp))
    }
}

// MARK: - Pieces

/** "Good evening, Sam" from the device clock (re-evaluated each minute so it turns over on its own). */
@Composable
private fun HomeGreeting(name: String?) {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(60_000 - (System.currentTimeMillis() % 60_000))
            value = Instant.now()
        }
    }
    Text(HomePlan.greeting(name, now), style = PleadType.titleM, color = PleadColor.cocoa, modifier = Modifier.testTag("home.greeting"))
}

/**
 * "Bring a new case" + Summon your partner. Leads (with a short intro to Plead) when nothing is urgent
 * or active; otherwise a quieter panel below the docket.
 */
@Composable
private fun NewCasePanel(promoted: Boolean, hasOpenCase: Boolean, store: CaseStore, onSummon: () -> Unit) {
    val intro = when {
        !promoted -> "Something else needs settling? Open a new file."
        hasOpenCase -> "Nothing needs you right now. When something needs settling, open a file."
        else -> "Plead is a small court for small disputes. Present your evidence, and let the AI court decide."
    }
    Column(
        Modifier.awCard(if (promoted) PleadSpacing.xl else PleadSpacing.l).testTag("home.newCase"),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
                Text("Bring a new case", style = PleadType.titleL, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
                Text(intro, style = PleadType.body, color = PleadColor.subtleText)
            }
            val me = store.me
            val partner = store.partner
            if (promoted && me != null && partner != null) {
                AvatarPair(me.avatar, partner.avatar, modifier = Modifier.clearAndSetSemantics { }, size = 40.dp, showHeart = false)
            }
        }
        PrimaryButton(
            "Summon your partner",
            modifier = Modifier.testTag("home.summon"),
            systemImage = if (promoted) "arrow.right" else null,
            kind = if (promoted) AWButtonKind.primary else AWButtonKind.secondary,
            action = onSummon,
        )
    }
}

/** No partner yet: the invite, never an empty pair card. */
@Composable
private fun SoloPanel(store: CaseStore) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.awCard(PleadSpacing.xl), verticalArrangement = Arrangement.spacedBy(PleadSpacing.l)) {
        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
            AvatarPair(store.me?.avatar, null, size = 56.dp, markYou = true)
            Text("Court needs two", style = PleadType.titleL, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })
            Text("Invite your partner to start filing cases.", style = PleadType.body, color = PleadColor.subtleText)
        }
        val couple = store.couple
        if (couple != null) {
            AWButton(
                onClick = {
                    shareText(context, "You've been summoned. Join me on Plead with code ${couple.inviteCode}.", AppConfig.inviteURL(couple.inviteCode))
                },
                modifier = Modifier.testTag("home.invite"),
                style = AWButtonStyle.aw(AWButtonKind.primary),
            ) {
                Icon(symbolIcon("paperplane.fill"), contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Invite your partner")
            }
        } else {
            PrimaryButton("Invite your partner", modifier = Modifier.testTag("home.invite"), systemImage = "paperplane.fill", isLoading = working) {
                working = true
                scope.launch {
                    try { store.createCouple() } catch (e: Exception) { error = (e as? EdgeError)?.message ?: "Couldn't create an invite." }
                    working = false
                }
            }
        }
        InlineError(error)
    }
    SoloInviteCard(store)
}

/** Couldn't refresh: the last known state stays on screen, with a retry. */
@Composable
private fun OfflineNotice(message: String, retry: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(symbolIcon("wifi.exclamationmark"), contentDescription = null, tint = PleadColor.subtleText, modifier = Modifier.size(15.dp))
        Text(message, style = PleadType.metadata, color = PleadColor.subtleText, modifier = Modifier.weight(1f))
        Box(Modifier.defaultMinSize(minHeight = 44.dp).courtFileButtonStyle(onClick = retry), contentAlignment = Alignment.Center) {
            Text("Try again", style = PleadType.uiButtonSecondary, color = PleadColor.burgundy)
        }
    }
}

/**
 * Home's "Outstanding agreement" card (amendment n), mirroring the outstanding-judgement card: a case
 * settled out of court whose agreement hasn't been marked fulfilled. Either partner marks it; the card
 * drops off once fulfilled. No crown, no winner: it is a mutual agreement.
 */
@Composable
fun OutstandingAgreementCard(
    kase: Case,
    settlement: Settlement,
    offer: SettlementOffer?,
    store: CaseStore,
    router: AppRouter,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val f = SettlementDocket.HomeFulfilment.of(settlement)
    val shape = RoundedCornerShape(PleadRadius.card)
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.awCourtFile(PleadSpacing.l), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .courtFileButtonStyle(onClick = { router.showRecord(kase.id) }, onClickLabel = "Opens the case record")
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs + 2.dp),
            ) {
                RecordLabel("Outstanding agreement", color = PleadColor.walnut)
                Text(kase.docketTitle, style = PleadType.metadataMedium, color = PleadColor.walnut, maxLines = 1)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = PleadColor.walnut)) { append("Agreement: ") }
                        withStyle(SpanStyle(color = PleadColor.cocoa)) { append(offer?.body ?: "Settled out of court") }
                    },
                    style = CaseType.bodyEmphasis,
                )
                Text(
                    SettlementDocket.homeLabel(f),
                    style = CaseType.dueLabel.copy(letterSpacing = PleadType.capsTracking.sp),
                    color = f.tint,
                )
            }
            PrimaryButton(
                "Mark as fulfilled",
                modifier = Modifier.padding(top = PleadSpacing.xs),
                systemImage = "checkmark.seal.fill",
                isLoading = working,
            ) {
                working = true; error = null
                scope.launch {
                    try {
                        store.markSettlementFulfilled(kase.id)
                        // `.sensoryFeedback(.success, trigger: succeeded)`.
                        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                    } catch (e: Exception) {
                        error = EdgeErrors.settlementMessage(e)
                    }
                    working = false
                }
            }
            InlineError(error)
        }
        // Walnut rule down the leading edge (the judgement card's is gold).
        Box(Modifier.matchParentSize().clip(shape)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(PleadColor.walnut.copy(alpha = 0.75f)))
        }
    }
}

/** Solo: the invite code, big and copyable. */
@Composable
private fun SoloInviteCard(store: CaseStore) {
    val couple = store.couple ?: return
    val expires = remember(couple.inviteExpiresAt) {
        DateUtils.getRelativeTimeSpanString(couple.inviteExpiresAt.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString().replaceFirstChar { it.lowercase() }
    }
    Column(Modifier.awCourtFile(PleadSpacing.xl), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        RecordLabel("Your invite code", color = PleadColor.walnut)
        SelectionContainer {
            Text(
                couple.inviteCode,
                style = CaseType.inviteCode.copy(letterSpacing = 6.sp),
                color = PleadColor.cocoa,
                modifier = Modifier.semantics { contentDescription = "Invite code: ${couple.inviteCode.toList().joinToString(" ")}" },
            )
        }
        Text(
            "Your partner enters this code, or taps your link. It expires $expires.",
            style = PleadType.metadata,
            color = PleadColor.subtleText,
        )
        Text("Meanwhile you can edit your avatar in Us.", style = PleadType.metadata, color = PleadColor.subtleText)
    }
}
