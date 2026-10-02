// Port of ArgueWin/Services/DeepLinkRouter.swift. Links arrive from MainActivity through `LaunchLinks.links`
// (iOS `.onOpenURL` / universal links); `AppModel.start` feeds them to [DeepLinkRouter.handle].
@file:Suppress("EnumEntryName")

package app.plead.android.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.app.AppTab
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Screens a push or link can land on for a case. Matches push payload `screen` values. */
enum class CaseScreen(val rawValue: String) {
    summons("summons"), defence("defence"), scheduling("scheduling"), court("court"), deliberation("deliberation"),
    verdict("verdict"), detail("detail"),

    /** Judgement selected / accepted / due soon / served (amendment j): lands on the record's status card. */
    judgement("judgement"),

    /** Settlement proposed / countered / accepted / rejected / due soon / fulfilled (amendment n). */
    settlement("settlement");

    companion object {
        /** Swift `CaseScreen(pushValue:)`: unknown or missing values land on the record. */
        fun fromPush(pushValue: String?): CaseScreen = entries.firstOrNull { it.rawValue == pushValue } ?: detail
    }
}

data class CaseRoute(val caseId: UUID, val screen: CaseScreen)

sealed class DeepLink {
    data class join(val code: String) : DeepLink()
    data class caseRoute(val route: CaseRoute) : DeepLink()
    data class authCallback(val url: URI) : DeepLink()

    /** `plead://home`, `plead://cases` (widgets, pushes). */
    data class tab(val tab: AppTab) : DeepLink()

    companion object {
        /**
         * `https://www.plead-app.com/join/ABC123`, `plead://join/ABC123`,
         * `plead://case/<uuid>?screen=court`, `plead://login-callback#...`,
         * and the amendment-o scheme: `plead://case/<uuid>/plea|turn|settlement|verdict|judgement|deliberation`,
         * `plead://cases`, `plead://home`.
         */
        fun parse(url: URI): DeepLink? {
            // Amendment ah: one custom scheme. `plead://` carries the auth callback, join codes, `?screen=` case
            // links and the amendment-o path-segment links; universal links keep the host check.
            if (url.scheme?.lowercase() == "plead") return parsePlead(url)
            val host = url.host?.lowercase() ?: return null
            val parts = pathComponents(url)
            if (host != AppConfig.universalLinkHost && !AppConfig.legacyUniversalLinkHosts.contains(host)) return null
            val first = parts.firstOrNull()?.lowercase() ?: return null
            return when (first) {
                "join" -> {
                    if (parts.size < 2) return null
                    val code = parts[1].uppercase().filter { it.isLetterOrDigit() }
                    if (code.length == 6) join(code) else null
                }
                "case" -> {
                    if (parts.size < 2) return null
                    val id = parseUUID(parts[1]) ?: return null
                    caseRoute(CaseRoute(id, CaseScreen.fromPush(query(url)["screen"])))
                }
                else -> null
            }
        }

        /** Parses a raw link string (`URL(string:)` then [parse]); null when it isn't a URL. */
        fun parse(raw: String): DeepLink? = runCatching { URI(raw) }.getOrNull()?.let(::parse)

        /** `plead://…` (widgets, the court notification, push `data.link`). Unknown screens land on the case record. */
        fun parsePlead(url: URI): DeepLink? {
            val parts = pathComponents(url).map { it.lowercase() }.toMutableList()
            val host = url.host?.lowercase() ?: url.rawAuthority?.lowercase()
            if (!host.isNullOrEmpty()) parts.add(0, host)
            val first = parts.firstOrNull() ?: return tab(AppTab.home)
            return when (first) {
                "login-callback" -> authCallback(url)
                "home" -> tab(AppTab.home)
                "cases" -> tab(AppTab.cases)
                "court" -> tab(AppTab.court)
                "join" -> {
                    if (parts.size < 2) return null
                    val code = parts[1].uppercase().filter { it.isLetterOrDigit() }
                    if (code.length == 6) join(code) else null
                }
                "case" -> {
                    if (parts.size < 2) return null
                    val id = parseUUID(parts[1]) ?: return null
                    if (parts.size >= 3) return caseRoute(CaseRoute(id, pleadScreen(parts[2])))
                    // `plead://case/<id>?screen=verdict` (pushes) → the push screen values.
                    caseRoute(CaseRoute(id, CaseScreen.fromPush(query(url)["screen"])))
                }
                else -> null
            }
        }

        /** `plead://case/{id}/<segment>` → the existing route screens. */
        fun pleadScreen(segment: String?): CaseScreen = when (segment) {
            "plea", "summons" -> CaseScreen.summons
            "turn", "court" -> CaseScreen.court
            "deliberation" -> CaseScreen.deliberation
            "verdict" -> CaseScreen.verdict
            "settlement" -> CaseScreen.settlement
            "judgement" -> CaseScreen.judgement
            "defence" -> CaseScreen.defence
            "scheduling" -> CaseScreen.scheduling
            else -> CaseScreen.detail
        }

        /**
         * Push payload: `data.link` (`plead://…`, amendment o) when present, else `{case_id, screen}`
         * (top level or nested under `data`).
         */
        fun parse(push: Map<String, String>): DeepLink? {
            push["link"]?.let { raw -> parse(raw)?.let { return it } }
            val id = parseUUID(push["case_id"]) ?: return null
            return caseRoute(CaseRoute(id, CaseScreen.fromPush(push["screen"])))
        }

        /** Swift `url.pathComponents.filter { $0 != "/" }` (percent-decoded). */
        private fun pathComponents(url: URI): List<String> = (url.path ?: "").split("/").filter { it.isNotEmpty() }

        private fun query(url: URI): Map<String, String> {
            val raw = url.rawQuery ?: return emptyMap()
            return raw.split("&").mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else URLDecoder.decode(part.substring(0, i), "UTF-8") to URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }.toMap()
        }
    }
}

/** Holds deep links until the app is ready to act on them (signed in, store loaded). */
class DeepLinkRouter {
    /** Invite code from `www.plead-app.com/join/CODE`, consumed by the link step / Home invite. */
    var pendingJoinCode: String? by mutableStateOf(null)

    /** Case to open, consumed by the tab shell once data is loaded. */
    var pendingCaseRoute: CaseRoute? by mutableStateOf(null)

    /** Tab to show (`plead://home`, `plead://cases`), consumed by the tab shell. */
    var pendingTab: AppTab? by mutableStateOf(null)

    var onAuthCallback: (suspend (URI) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun handle(url: URI) {
        val link = DeepLink.parse(url) ?: return
        handle(link)
    }

    fun handle(link: DeepLink) {
        when (link) {
            is DeepLink.join -> pendingJoinCode = link.code
            is DeepLink.caseRoute -> pendingCaseRoute = link.route
            is DeepLink.tab -> pendingTab = link.tab
            is DeepLink.authCallback -> scope.launch { onAuthCallback?.invoke(link.url) }
        }
    }
}
