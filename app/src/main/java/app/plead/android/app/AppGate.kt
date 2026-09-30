// Port of ArgueWin/App/AppGate.swift.
@file:Suppress("EnumEntryName")

package app.plead.android.app

/**
 * The app's top-level routing decision, as a pure function (unit-tested in `AppGateTests`).
 *
 * signed out or not onboarded → the onboarding flow (which owns sign-in, identity and the couple) ·
 * no couple → link step · couple not premium → paywall gate (full-screen root state; closing it
 * returns to the link step) · premium but the session is still anonymous → SecureAccountView
 * (amendment p: login after the paywall; not skippable) · premium → tabs. Plead is a paid app: nothing
 * gets past the gate without `couple.isPremium`. Order (amendments g, p): onboarding → gate → secure → tabs.
 */
object AppGate {
    enum class Destination { launching, loadFailed, onboarding, linkCouple, paywall, partnerPaid, secureAccount, tabs }

    data class Input(
        val authResolved: Boolean = true,
        val signedIn: Boolean = true,
        val storeLoaded: Boolean = true,
        val hasProfile: Boolean = true,
        /** `profiles.onboarding_completed_at` (or the local mirror), or an account with case history. */
        val onboardingDone: Boolean = true,
        /** The onboarding flow is on screen this session (keeps it up while a fresh sign-in loads). */
        val onboardingInProgress: Boolean = false,
        val loadFailed: Boolean = false,
        val hasCouple: Boolean = true,
        val coupleLinked: Boolean = true,
        val couplePremium: Boolean = false,
        /** The user finished the link step ("Continue on my own", or the linked celebration). */
        val linkStepDone: Boolean = true,
        /** The user closed the gate (or opened a join link) and is back at the link step this session. */
        val atLinkStep: Boolean = false,
        /**
         * The user reached the gate (paywall, or joined an already-premium couple) and has not yet
         * continued into the app. A couple that turns premium while latched was paid by the partner.
         */
        val gateLatched: Boolean = false,
        /** This user bought / restored (this session) or is the couple's recorded payer. */
        val unlockedByMe: Boolean = false,
        /** An invite link is waiting to be used. */
        val pendingJoin: Boolean = false,
        /** The session is a Supabase anonymous user (JWT `is_anonymous`): no identity linked yet. */
        val anonymous: Boolean = false,
        /**
         * An edge call answered 403 `identity_required` this session (a server call slipped through
         * while anonymous): secure the account first.
         */
        val identityRequired: Boolean = false,
    )

    fun destination(i: Input): Destination {
        if (!i.authResolved) return Destination.launching
        if (!i.signedIn) return Destination.onboarding
        if (!i.storeLoaded) return if (i.onboardingInProgress) Destination.onboarding else Destination.launching
        if (!i.hasProfile && i.loadFailed) return Destination.loadFailed
        if (!i.onboardingDone || !i.hasProfile) {
            // Amendment as: an invite link during onboarding opens the partner step's code entry (prefilled), so
            // `pendingJoin` keeps the user in onboarding. Only an explicit link step leaves it.
            if (i.hasProfile && !i.coupleLinked && i.atLinkStep) return Destination.linkCouple
            return Destination.onboarding
        }
        if (i.anonymous && i.identityRequired) return Destination.secureAccount
        if (!i.hasCouple) return Destination.linkCouple
        if (i.pendingJoin && !i.coupleLinked) return Destination.linkCouple
        if (i.couplePremium) {
            if (i.gateLatched && !i.unlockedByMe) return Destination.partnerPaid
            if (!i.coupleLinked && !i.linkStepDone) return Destination.linkCouple
            // The gate has resolved (bought / restored, or the partner paid): secure the account first.
            if (i.anonymous) return Destination.secureAccount
            return Destination.tabs
        }
        if (i.atLinkStep) return Destination.linkCouple
        if (!i.coupleLinked && !i.linkStepDone) return Destination.linkCouple
        return Destination.paywall
    }
}
