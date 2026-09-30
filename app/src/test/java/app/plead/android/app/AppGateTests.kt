// Port of ArgueWinTests' pure gate suites: PaywallGateTests.AppGateTests, OnboardingTests.OnboardingGateTests and
// PartnerCodeJoinTests.PartnerCodeGateTests.
package app.plead.android.app

import app.plead.android.app.AppGate.Destination
import app.plead.android.app.AppGate.Input
import org.junit.Assert.assertEquals
import org.junit.Test

/** The paid-app gate: signed out / not onboarded → onboarding · no couple → link · unpaid → paywall · premium → tabs. */
class AppGateTests {
    private fun dest(i: Input) = AppGate.destination(i)

    @Test fun launchAndAuth() {
        assertEquals(Destination.launching, dest(Input(authResolved = false)))
        assertEquals(Destination.onboarding, dest(Input(signedIn = false)))
        assertEquals(Destination.launching, dest(Input(storeLoaded = false)))
    }

    @Test fun profileStep() {
        assertEquals(Destination.onboarding, dest(Input(hasProfile = false)))
        assertEquals(Destination.loadFailed, dest(Input(hasProfile = false, loadFailed = true)))
    }

    @Test fun noCoupleIsLinkStepEvenIfLinkStepWasSkippedBefore() {
        assertEquals(Destination.linkCouple, dest(Input(hasCouple = false, linkStepDone = true)))
    }

    @Test fun unpaidLinkedCoupleHitsTheGate() {
        assertEquals(Destination.paywall, dest(Input(coupleLinked = true, couplePremium = false)))
    }

    @Test fun premiumGoesToTabs() {
        assertEquals(Destination.tabs, dest(Input(couplePremium = true)))
    }

    @Test fun soloWithInviteStaysOnLinkStepUntilContinue() {
        assertEquals(Destination.linkCouple, dest(Input(coupleLinked = false, linkStepDone = false)))
        assertEquals(Destination.paywall, dest(Input(coupleLinked = false, linkStepDone = true)))
    }

    @Test fun closingTheGateReturnsToLinkStepNeverTabs() {
        assertEquals(Destination.linkCouple, dest(Input(couplePremium = false, atLinkStep = true)))
        assertEquals(Destination.linkCouple, dest(Input(coupleLinked = false, couplePremium = false, atLinkStep = true)))
    }

    @Test fun partnerPaidWhileLatched() {
        assertEquals(Destination.partnerPaid, dest(Input(couplePremium = true, gateLatched = true, unlockedByMe = false)))
        assertEquals(Destination.tabs, dest(Input(couplePremium = true, gateLatched = true, unlockedByMe = true)))
        assertEquals(Destination.tabs, dest(Input(couplePremium = true, gateLatched = false)))
    }

    @Test fun joinLinkRespectsTheGate() {
        assertEquals(Destination.linkCouple, dest(Input(coupleLinked = false, linkStepDone = true, pendingJoin = true)))
        assertEquals(Destination.linkCouple, dest(Input(hasCouple = false, pendingJoin = true)))
        assertEquals(Destination.paywall, dest(Input(coupleLinked = true, couplePremium = false, pendingJoin = true)))
    }

    /** Amendment p: login after the paywall. The decision table for the session kind × payment. */
    @Test fun anonymousSessionsSecureTheAccountAfterTheGate() {
        assertEquals(Destination.paywall, dest(Input(couplePremium = false, anonymous = true)))
        assertEquals(Destination.secureAccount, dest(Input(couplePremium = true, anonymous = true)))
        assertEquals(Destination.tabs, dest(Input(couplePremium = true, anonymous = false)))
        assertEquals(Destination.paywall, dest(Input(couplePremium = false, anonymous = false)))
    }

    @Test fun secureAccountComesAfterPartnerPaidAndOnboarding() {
        assertEquals(Destination.partnerPaid, dest(Input(couplePremium = true, gateLatched = true, unlockedByMe = false, anonymous = true)))
        assertEquals(Destination.secureAccount, dest(Input(couplePremium = true, gateLatched = false, anonymous = true)))
        assertEquals(Destination.secureAccount, dest(Input(couplePremium = true, gateLatched = true, unlockedByMe = true, anonymous = true)))
        assertEquals(Destination.onboarding, dest(Input(onboardingDone = false, couplePremium = true, anonymous = true)))
        assertEquals(Destination.linkCouple, dest(Input(coupleLinked = false, couplePremium = true, linkStepDone = false, anonymous = true)))
    }

    @Test fun identityRequiredShowsSecureAccountOnlyWhileAnonymous() {
        assertEquals(Destination.secureAccount, dest(Input(couplePremium = false, anonymous = true, identityRequired = true)))
        assertEquals(Destination.tabs, dest(Input(couplePremium = true, anonymous = false, identityRequired = true)))
    }

    @Test fun lapsedSubscriptionReturnsToGate() {
        assertEquals(Destination.paywall, dest(Input(coupleLinked = true, couplePremium = false, linkStepDone = true)))
    }
}

/** Onboarding inside the root gate (CONTRACTS-v2 amendment g: onboarding → gate → tabs). */
class OnboardingGateTests {
    private fun dest(i: Input) = AppGate.destination(i)

    @Test fun notOnboardedGoesToOnboardingBeforeAnythingElse() {
        assertEquals(Destination.onboarding, dest(Input(onboardingDone = false, couplePremium = true)))
        assertEquals(Destination.onboarding, dest(Input(onboardingDone = false, hasCouple = false)))
        assertEquals(Destination.onboarding, dest(Input(onboardingDone = false, coupleLinked = true, couplePremium = false)))
    }

    @Test fun freshSignInMidOnboardingDoesNotFlashTheSplash() {
        assertEquals(Destination.onboarding, dest(Input(storeLoaded = false, onboardingInProgress = true)))
        assertEquals(Destination.launching, dest(Input(storeLoaded = false, onboardingInProgress = false)))
    }

    @Test fun inviteLinkMidOnboardingJoinsFirst() {
        assertEquals(Destination.onboarding, dest(Input(onboardingDone = false, hasCouple = false, coupleLinked = false, pendingJoin = true)))
        assertEquals(Destination.onboarding, dest(Input(onboardingDone = false, hasCouple = true, coupleLinked = false, pendingJoin = true)))
        assertEquals(Destination.linkCouple, dest(Input(onboardingDone = false, hasCouple = false, coupleLinked = false, atLinkStep = true)))
        assertEquals(Destination.onboarding, dest(Input(hasProfile = false, onboardingDone = false, coupleLinked = false, pendingJoin = true)))
        assertEquals(Destination.onboarding, dest(Input(onboardingDone = false, coupleLinked = true, pendingJoin = true)))
    }

    @Test fun afterOnboardingTheExistingRulesApply() {
        assertEquals(Destination.paywall, dest(Input(onboardingDone = true, coupleLinked = false, couplePremium = false, linkStepDone = true)))
        assertEquals(Destination.tabs, dest(Input(onboardingDone = true, couplePremium = true)))
    }
}

/** The gate after joining on the partner step (pure routing). */
class PartnerCodeGateTests {
    @Test fun joinedPaidCoupleEndsOnPartnerPaidNotThePaywall() {
        val joined = Input(onboardingDone = true, coupleLinked = true, couplePremium = true, linkStepDone = true, gateLatched = true, unlockedByMe = false, anonymous = true)
        assertEquals(Destination.partnerPaid, AppGate.destination(joined))
        val entered = joined.copy(gateLatched = false)
        assertEquals(Destination.secureAccount, AppGate.destination(entered))
        assertEquals(Destination.tabs, AppGate.destination(entered.copy(anonymous = false)))
    }

    @Test fun joinedUnpaidCoupleMeetsThePaywall() {
        assertEquals(Destination.paywall, AppGate.destination(Input(onboardingDone = true, coupleLinked = true, couplePremium = false, linkStepDone = true, anonymous = true)))
    }

    @Test fun stillOnboardingAfterTheJoin() {
        assertEquals(Destination.onboarding, AppGate.destination(Input(onboardingDone = false, coupleLinked = true, couplePremium = true, gateLatched = true, anonymous = true)))
    }
}
