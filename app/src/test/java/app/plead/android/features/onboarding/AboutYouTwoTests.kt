// Port of ArgueWinTests/AboutYouTwoTests.swift. CONTRACTS-v2 amendment bd: "About you two" + the Courtroom Oath
// between Welcome and the Mock Trial. Android (amendment az, no ATT): the counted screens are eleven, not twelve.
package app.plead.android.features.onboarding

import app.plead.android.features.onboarding.about.AboutAnswerCodec
import app.plead.android.features.onboarding.about.AboutHeroKind
import app.plead.android.features.onboarding.about.AboutHeroLayout
import app.plead.android.features.onboarding.about.AboutMotion
import app.plead.android.features.onboarding.about.AboutOptionIcon
import app.plead.android.features.onboarding.about.AboutProgressRail
import app.plead.android.features.onboarding.about.AboutQuestion
import app.plead.android.features.onboarding.about.CourtroomOathCopy
import app.plead.android.features.onboarding.about.OathHoldClock
import app.plead.android.features.onboarding.about.aboutHeroHeight
import app.plead.android.features.settings.AccountDeletion
import app.plead.android.models.Role
import app.plead.android.services.UserDefaults
import app.plead.android.services.uuidString
import androidx.compose.ui.geometry.Size
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers the current "About you two" question with its first option. No-op elsewhere. */
internal fun OnboardingModel.answerIfAboutYouTwo() {
    val q = AboutQuestion.forStep(step) ?: return
    if (answers(q).isEmpty()) select(q.options[0].id, q)
}

/** The forward CTA, answering first when the step needs it (the oath's completion is its CTA). */
internal fun OnboardingModel.advanceAnswering() {
    if (step == OnboardingStep.oath) {
        takeOath()
        return
    }
    answerIfAboutYouTwo()
    advance()
}

/** From the first question to the Mock Trial. */
internal fun OnboardingModel.passAboutYouTwo() {
    while (step.isAboutYouTwo) advanceAnswering()
}


class AboutYouTwoStepTests {
    @Test fun rawValuesScreenIdsAndOrder() {
        val six = listOf(OnboardingStep.aboutFrequency, OnboardingStep.aboutTopics, OnboardingStep.aboutOutcome, OnboardingStep.aboutRepeat, OnboardingStep.aboutEasier, OnboardingStep.oath)
        assertEquals((13..18).toList(), six.map { it.rawValue })
        assertEquals(listOf("about_frequency", "about_topics", "about_outcome", "about_repeat", "about_easier", "oath"), six.map { it.screenId })
        assertEquals(six, OnboardingStep.aboutYouTwoSteps)
        assertEquals(six, OnboardingStep.displayOrder.subList(2, 8))
        assertEquals(OnboardingStep.welcome, OnboardingStep.displayOrder[0])
        assertEquals(OnboardingStep.aboutIntro, OnboardingStep.displayOrder[1]) // amendment bg
        assertEquals(OnboardingStep.mockTrial, OnboardingStep.displayOrder[8])
        val intro = OnboardingStep.aboutIntro
        assertEquals(19, intro.rawValue)
        assertEquals("about_intro", intro.screenId)
        assertTrue(intro.isPreAuth && intro.isAboutYouTwo && !intro.isCounted)
        assertNull(intro.aboutIndex)
        assertEquals("2 OF 11", intro.positionLabel())
        assertTrue(OnboardingFlow.canAdvance(intro, ""))
        assertEquals(OnboardingContainer.aboutIntroChromeHeight, OnboardingContainer.chromeHeight(intro))
        assertEquals("5 quick questions", app.plead.android.features.onboarding.about.AboutIntroScreen.questionsFact)
        assertEquals("1 courtroom oath", app.plead.android.features.onboarding.about.AboutIntroScreen.oathFact)
        assertEquals(AboutHeroKind.intro, AboutHeroKind.forStep(intro))
        six.forEachIndexed { i, s ->
            assertEquals(s, OnboardingStep.fromRaw(13 + i))
            assertEquals(s, OnboardingStep.persisted(13 + i))
            assertTrue(s.isPreAuth)
            assertTrue(s.showsChrome)
            assertTrue(s.isAboutYouTwo && !s.isCounted)
            assertEquals(i, s.aboutIndex)
            assertTrue(s after OnboardingStep.welcome && s before OnboardingStep.mockTrial)
        }
        assertNull(OnboardingStep.welcome.aboutIndex)
        assertEquals(OnboardingStep.aboutIntro, OnboardingStep.persisted(19))
        assertEquals(OnboardingStep.ready, OnboardingStep.persisted(20))
    }

    /** The other screens keep their count (eleven on Android, twelve on iOS); the six report the Mock Trial's position. */
    @Test fun countsExcludeTheSix() {
        val steps = OnboardingStep.activeSteps()
        assertEquals(18, steps.size)
        assertEquals(11, OnboardingStep.countedSteps(steps).size)
        assertEquals("1 OF 11", OnboardingStep.welcome.positionLabel())
        assertEquals("2 OF 11", OnboardingStep.mockTrial.positionLabel())
        assertEquals("11 OF 11", OnboardingStep.ready.positionLabel())
        assertEquals("2 OF 11", OnboardingStep.oath.positionLabel())
        assertEquals(1.0, OnboardingStep.ready.progress(), 0.0)
        assertEquals(1, OnboardingContainer.railIndex(OnboardingStep.mockTrial))
        assertEquals(10, OnboardingContainer.railIndex(OnboardingStep.ready))
        assertEquals(10, OnboardingProgressRail.segmentCount)
        assertEquals("Question 1 of 6", AboutProgressRail.accessibilityText(0))
        assertEquals(OnboardingContainer.aboutChromeHeight, OnboardingContainer.chromeHeight(OnboardingStep.aboutTopics))
        assertEquals(OnboardingContainer.chromeHeight, OnboardingContainer.chromeHeight(OnboardingStep.mockTrial))
    }

    @Test fun nextAndPreviousAcrossTheSix() {
        assertEquals(OnboardingStep.aboutIntro, OnboardingStep.welcome.next())
        assertEquals(OnboardingStep.welcome, OnboardingStep.aboutIntro.previous())
        assertEquals(OnboardingStep.aboutFrequency, OnboardingStep.aboutIntro.next())
        assertEquals(OnboardingStep.aboutIntro, OnboardingStep.aboutFrequency.previous())
        assertEquals(OnboardingStep.aboutTopics, OnboardingStep.aboutFrequency.next())
        assertEquals(OnboardingStep.oath, OnboardingStep.aboutEasier.next())
        assertEquals(OnboardingStep.aboutEasier, OnboardingStep.oath.previous())
        assertEquals(OnboardingStep.mockTrial, OnboardingStep.oath.next())
        assertEquals(OnboardingStep.oath, OnboardingStep.mockTrial.previous())
    }

    /** Saved on one of the six → resumes there; saved on the Mock Trial or later → never sent back to them. */
    @Test fun resume() {
        for (raw in 13..19) {
            assertEquals(OnboardingStep.fromRaw(raw), OnboardingFlow.resumeStep(user = null, device = raw, signedIn = false, hasProfile = false))
        }
        assertEquals(OnboardingStep.mockTrial, OnboardingFlow.resumeStep(user = null, device = 11, signedIn = false, hasProfile = false))
        assertEquals(OnboardingStep.summonsIntro, OnboardingFlow.resumeStep(user = null, device = 12, signedIn = false, hasProfile = false))
        assertEquals(OnboardingStep.notifications, OnboardingFlow.resumeStep(user = 7, device = 11, signedIn = true, hasProfile = true))
        assertEquals(OnboardingStep.mockTrial, OnboardingFlow.resumeStep(user = 11, device = null, signedIn = true, hasProfile = true))
    }

    @Test fun canAdvanceRule() {
        for (s in OnboardingStep.aboutYouTwoSteps.dropLast(1)) {
            assertFalse(OnboardingFlow.canAdvance(s, "", answers = emptyList(), oathTaken = false))
            assertTrue(OnboardingFlow.canAdvance(s, "", answers = listOf("x"), oathTaken = false))
            assertFalse(OnboardingFlow.canAdvance(s, "Arif"))
        }
        assertFalse(OnboardingFlow.canAdvance(OnboardingStep.oath, "", answers = emptyList(), oathTaken = false))
        assertTrue(OnboardingFlow.canAdvance(OnboardingStep.oath, "", answers = emptyList(), oathTaken = true))
        assertTrue(OnboardingFlow.canAdvance(OnboardingStep.mockTrial, ""))
    }
}

class AboutYouTwoContentTests {
    /** Copy and ids exactly as amendment bd. */
    @Test fun optionIdsAndCopy() {
        assertEquals(
            listOf(
                listOf("most_days", "few_times_week", "occasionally", "rarely"),
                listOf("chores", "food", "being_late", "phone_habits", "spending", "random"),
                listOf("both_insist", "gives_in", "laugh_off", "unresolved"),
                listOf("all_the_time", "sometimes", "rarely", "something_new"),
                listOf("feeling_heard", "fair_compromise", "clear_decision", "laugh_about_it"),
            ),
            AboutQuestion.entries.map { it.optionIds },
        )
        assertEquals(
            listOf(
                "How often do little things turn into arguments?", "What do you bicker about most?",
                "When you disagree, what usually happens?", "Does the same argument keep coming back?",
                "What would make those moments easier?",
            ),
            AboutQuestion.entries.map { it.title },
        )
        assertEquals(listOf("Most days", "A few times a week", "Occasionally", "Rarely"), AboutQuestion.frequency.options.map { it.title })
        assertEquals(listOf("Chores", "Food", "Being late", "Phone habits", "Spending", "Random little things"), AboutQuestion.topics.options.map { it.title })
        assertEquals(listOf("We both insist we're right", "Someone gives in", "We laugh it off", "It stays unresolved"), AboutQuestion.outcome.options.map { it.title })
        assertEquals(listOf("All the time", "Sometimes", "Rarely", "We always find something new"), AboutQuestion.repeats.options.map { it.title })
        assertEquals(listOf("Feeling heard", "A fair compromise", "A clear decision", "Being able to laugh about it"), AboutQuestion.easier.options.map { it.title })
        assertEquals(listOf(false, true, false, false, true), AboutQuestion.entries.map { it.isMultiSelect })
        assertEquals(listOf("Pick one", "Choose all that fit", "Pick one", "Pick one", "Choose all that fit"), AboutQuestion.entries.map { it.hint })
        assertEquals("ABOUT YOU TWO", AboutQuestion.eyebrow)
        assertEquals("THE COURTROOM OATH", CourtroomOathCopy.eyebrow)
        assertEquals("Do you swear to hear them out?", CourtroomOathCopy.title)
        assertEquals("I'll hear both sides, give the verdict a fair chance, and keep our little disagreements playful.", CourtroomOathCopy.oath)
        assertEquals("HOLD TO TAKE THE OATH", CourtroomOathCopy.button)
        assertEquals("Hold for 2 seconds", CourtroomOathCopy.caption)
        assertEquals(2.0, CourtroomOathCopy.holdDuration, 0.0)
        for (q in AboutQuestion.entries.filter { it.layout != AboutQuestion.Layout.radioRows }) {
            assertTrue(q.options.all { it.icon != null })
        }
        assertEquals(
            listOf("aboutFrequency", "aboutTopics", "aboutOutcome", "aboutRepeat", "aboutEasier"),
            AboutQuestion.entries.map { it.storageKey.rawValue },
        )
        assertEquals("onboarding.device.oathTaken", OnboardingModel.Key.oathTaken.key(null))
        assertEquals("onboarding.device.aboutTopics", AboutQuestion.topics.storageKey.key(null))
        for (q in AboutQuestion.entries) assertEquals(q, AboutQuestion.forStep(q.step))
    }

    @Test fun codec() {
        assertNull(AboutAnswerCodec.encode(emptyList(), AboutQuestion.topics))
        assertEquals("chores,spending", AboutAnswerCodec.encode(listOf("spending", "chores"), AboutQuestion.topics))
        assertEquals("rarely", AboutAnswerCodec.encode(listOf("rarely"), AboutQuestion.frequency))
        assertEquals(listOf("chores", "spending"), AboutAnswerCodec.decode("spending,chores,bogus", AboutQuestion.topics))
        assertEquals(listOf("most_days"), AboutAnswerCodec.decode("most_days,rarely", AboutQuestion.frequency))
        assertTrue(AboutAnswerCodec.decode(null, AboutQuestion.frequency).isEmpty())
        assertEquals(listOf("food", "spending"), AboutAnswerCodec.toggled("food", listOf("spending"), AboutQuestion.topics))
        assertEquals(listOf("spending"), AboutAnswerCodec.toggled("food", listOf("food", "spending"), AboutQuestion.topics))
        assertEquals(listOf("rarely"), AboutAnswerCodec.toggled("rarely", listOf("most_days"), AboutQuestion.frequency))
        assertEquals(listOf("most_days"), AboutAnswerCodec.toggled("nope", listOf("most_days"), AboutQuestion.frequency))
    }

    @Test fun oathHoldClock() {
        val t0 = 1_000_000L
        var c = OathHoldClock()
        assertEquals(0.0, c.progress(t0), 0.0)
        c = c.pressed(t0)
        assertTrue(abs(c.progress(t0 + 1000) - 0.5) < 1e-9)
        assertEquals(1.0, c.progress(t0 + 3000), 0.0)
        c = c.released(t0 + 1000)
        assertTrue(abs(c.releaseFrom - 0.5) < 1e-9)
        assertEquals(0.5, c.progress(t0 + 1000), 1e-9)
        assertEquals(0.0, c.progress(t0 + 1000 + (OathHoldClock.drain * 1000).toLong()), 1e-9)
        assertTrue(c.isActive(t0 + 1050))
        assertFalse(c.isActive(t0 + 2000))
        assertEquals(0.0, OathHoldClock.gavelRaise(0.0), 0.0)
        assertEquals(1.0, OathHoldClock.gavelRaise(1.0), 0.0)
    }

    @Test fun heroLayoutFitsPhones() {
        for (w in listOf(360f, 390f, 402f, 412f, 432f)) {
            val h = AboutHeroLayout.height(w)
            for (height in listOf(h, AboutMotion.heroMin.value)) {
                val l = AboutHeroLayout(Size(w, height))
                assertTrue("judge below the top at $w × $height", l.judgeFrame.top > 0f)
                assertTrue(l.avatarFrame(Role.plaintiff).right < l.judgeFrame.left)
                assertTrue(l.avatarFrame(Role.defendant).left > l.judgeFrame.right)
                assertTrue(l.deskFrame(Role.plaintiff).right < l.deskFrame(Role.defendant).left)
                assertTrue(l.deskFrame(Role.defendant).right <= w)
                assertTrue(l.benchTop > l.judgeFrame.center.y && l.benchTop < l.deskTop)
            }
        }
        assertEquals(AboutHeroKind.arguing, AboutHeroKind.forStep(OnboardingStep.aboutFrequency))
        assertEquals(AboutHeroKind.oath, AboutHeroKind.forStep(OnboardingStep.oath))
        assertTrue(AboutOptionIcon.entries.all { it.rows.isNotEmpty() })
    }

    /** Android fitting: the hero gives back height first (down to `heroMin`), and is never taller than iOS's. */
    @Test fun heroGivesHeightBackFirst() {
        val ideal = AboutHeroLayout.height(412f)
        assertEquals(218f, ideal, 0f)
        // Room to spare: the iOS height.
        assertEquals(ideal, aboutHeroHeight(availableDp = 790f, widthDp = 412f, contentDp = 420f, ctaDp = 80f), 0f)
        // Tight: the hero shrinks by exactly what is missing.
        assertEquals(150f, aboutHeroHeight(availableDp = 650f, widthDp = 412f, contentDp = 420f, ctaDp = 80f), 0f)
        // Very tight: the floor (the content scrolls instead).
        assertEquals(AboutMotion.heroMin.value, aboutHeroHeight(availableDp = 480f, widthDp = 360f, contentDp = 520f, ctaDp = 90f), 0f)
    }
}

class AboutYouTwoModelTests {
    @Test fun notSkippableAndAnswersPersist() {
        val d = UserDefaults.inMemory()
        val m = OnboardingModel(d)
        m.advance()
        assertEquals(OnboardingStep.aboutIntro, m.step)
        m.advance()
        assertEquals(OnboardingStep.aboutFrequency, m.step)
        // Unanswered: CONTINUE does nothing.
        m.advance()
        assertEquals(OnboardingStep.aboutFrequency, m.step)
        m.select("few_times_week", AboutQuestion.frequency)
        m.select("rarely", AboutQuestion.frequency)
        assertEquals(listOf("rarely"), m.answers(AboutQuestion.frequency))
        assertEquals("rarely", d.string("onboarding.device.aboutFrequency"))
        m.advance()
        assertEquals(OnboardingStep.aboutTopics, m.step)
        m.select("spending", AboutQuestion.topics)
        m.select("chores", AboutQuestion.topics)
        assertEquals("chores,spending", d.string("onboarding.device.aboutTopics"))
        m.select("spending", AboutQuestion.topics)
        m.select("chores", AboutQuestion.topics)
        assertNull(d.objectForKey("onboarding.device.aboutTopics"))
        assertFalse(m.canAdvanceAboutYouTwo(OnboardingStep.aboutTopics))
        m.select("food", AboutQuestion.topics)
        // Back keeps answers.
        m.back()
        assertEquals(OnboardingStep.aboutFrequency, m.step)
        assertEquals(listOf("rarely"), m.answers(AboutQuestion.frequency))
        m.advance()
        m.advance()
        assertEquals(OnboardingStep.aboutOutcome, m.step)
        m.select("laugh_off", AboutQuestion.outcome); m.advance()
        m.select("something_new", AboutQuestion.repeats); m.advance()
        m.select("laugh_about_it", AboutQuestion.easier); m.advance()
        assertEquals(OnboardingStep.oath, m.step)
        // The oath only advances by being taken.
        m.advance()
        assertEquals(OnboardingStep.oath, m.step)
        assertFalse(m.oathTaken)

        // Relaunch (process death): same step, same answers.
        val r = OnboardingModel(d)
        assertEquals(OnboardingStep.oath, r.step)
        assertEquals(listOf("food"), r.answers(AboutQuestion.topics))
        assertEquals(listOf("laugh_about_it"), r.answers(AboutQuestion.easier))
        assertEquals(listOf("rarely"), r.answers(AboutQuestion.frequency))
        r.takeOath()
        assertTrue(r.oathTaken)
        assertTrue(d.bool("onboarding.device.oathTaken"))
        assertEquals(OnboardingStep.mockTrial, r.step)
        // A second completion (double fire during the transition) is ignored.
        r.takeOath()
        assertEquals(OnboardingStep.mockTrial, r.step)
    }

    @Test fun analyticsCarryOnlyScreenIds() {
        val m = OnboardingModel(UserDefaults.inMemory())
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        m.analyticsSink = { e, p -> events += e to p }
        m.advance()
        m.advance()
        m.screenAppeared(OnboardingStep.aboutFrequency)
        m.select("most_days", AboutQuestion.frequency)
        m.advance()
        val answerIds = AboutQuestion.entries.flatMap { it.optionIds }.toSet()
        assertTrue(events.all { e -> e.second.values.none { it in answerIds } })
        assertTrue(events.any { it.first == OnboardingEvent.continueTapped && it.second["screen_id"] == "about_frequency" })
        assertTrue(events.any { it.first == OnboardingEvent.screenViewed && it.second["screen_id"] == "about_frequency" })
    }

    @Test fun answersCarryToTheUserAndResetWipesThem() {
        val d = UserDefaults.inMemory()
        val m = OnboardingModel(d)
        m.advance()
        m.select("most_days", AboutQuestion.frequency)
        m.passAboutYouTwo()
        assertEquals(OnboardingStep.mockTrial, m.step)
        val uid = UUID.randomUUID()
        m.go(OnboardingStep.identity)
        m.displayName = "Arif"
        m.userChanged(uid, null)
        assertEquals("most_days", d.string("onboarding.${uid.uuidString}.aboutFrequency"))
        assertTrue(d.bool("onboarding.${uid.uuidString}.oathTaken"))
        assertEquals(listOf("most_days"), m.answers(AboutQuestion.frequency))
        m.resetForDemo(emptyList())
        for (key in OnboardingModel.Key.aboutYouTwo) {
            assertNull(d.objectForKey(key.key(null)))
            assertNull(d.objectForKey(key.key(uid.uuidString)))
        }
        assertTrue(m.answers(AboutQuestion.frequency).isEmpty())
        assertFalse(m.oathTaken)
        // Account deletion clears the device scope by prefix.
        assertEquals(listOf("onboarding.device.aboutTopics"), AccountDeletion.keysToClear(null, listOf("onboarding.device.aboutTopics")))
    }

    /** A user saved on the Mock Trial (or later) by an older build is never sent back to the six. */
    @Test fun savedOnTheMockTrialIsNotSentBack() {
        val d = UserDefaults.inMemory()
        d.set(OnboardingStep.mockTrial.rawValue, OnboardingModel.Key.step.key(null))
        assertEquals(OnboardingStep.mockTrial, OnboardingModel(d).step)
        d.set(OnboardingStep.howItWorks.rawValue, OnboardingModel.Key.step.key(null))
        assertEquals(OnboardingStep.howItWorks, OnboardingModel(d).step)
    }
}

/** Amendment be: polish after the first run-through (port of iOS `AmendmentBETests`, plus the Android-only checks). */
class AmendmentBETests {
    @Test fun oathCelebrationIsSeededAndBrief() {
        val ps = app.plead.android.features.onboarding.about.OathCelebration.particles
        val c = app.plead.android.features.onboarding.about.OathCelebration
        assertEquals(c.confettiCount, ps.count { !it.heart })
        assertEquals(c.heartCount, ps.count { it.heart })
        assertEquals(ps, c.particles)
        assertTrue(ps.all { it.delay + it.life <= c.duration + 0.11 })
        assertTrue(ps.all { it.angle < 0 }) // launched upwards
        val o = androidx.compose.ui.geometry.Offset(100f, 400f)
        assertNull(c.state(ps[0], -0.1, o))
        assertNull(c.state(ps[0], 2.0, o))
        val t = app.plead.android.features.onboarding.about.CourtroomOathTiming
        assertTrue(t.advanceDelay > t.burstDelay)
        assertTrue(t.advanceDelay <= 1.5)
    }

    @Test fun browHandLeavesTheFaceVisible() {
        val art = app.plead.android.features.onboarding.about.AboutPixelArt
        val rows = art.browHand
        assertTrue(rows.all { it.length == 20 })
        // Overlay row r = judge row r + 1. Judge face rows 4…9 (overlay 3…8), face columns 4…11.
        for (judgeRow in 4..9) {
            for (col in 4..11) {
                val ch = rows[judgeRow - 1][col]
                // Only the closed-eye lids (judge row 5, columns 5–6 and 9–10) are drawn on the face.
                val lid = judgeRow == 5 && col in listOf(5, 6, 9, 10)
                assertEquals("covers the face at row $judgeRow, column $col", if (lid) 'l' else '.', ch)
            }
        }
        assertEquals(app.plead.android.designsystem.Color(hex = 0xF5C9A6), art.browHandPalette['S'])
        assertEquals(app.plead.android.features.onboarding.about.AboutHeroKind.facepalm, app.plead.android.features.onboarding.about.AboutHeroKind.forStep(OnboardingStep.aboutOutcome))
        assertEquals(5, art.sweatDrop.size)
    }

    @Test fun screenTwoHoldsAWalletAndAPhone() {
        assertEquals(2f, app.plead.android.features.onboarding.about.AboutHeroFrameProps.propCell, 0f)
        assertEquals(12, AboutOptionIcon.wallet.rows.size)
        assertEquals(12, AboutOptionIcon.phone.rows.size)
        assertEquals(4, app.plead.android.features.onboarding.about.AboutPixelArt.grip.size)
    }

    @Test fun identityCardIsLight() {
        assertEquals(OnboardingPalette.paper, CourtIdentityPreview.surface)
        assertEquals(OnboardingPalette.burgundy, CourtIdentityPreview.titleColor)
        assertEquals(OnboardingPalette.wine, CourtIdentityPreview.nameColor)
        assertEquals(OnboardingPalette.mahogany, CourtIdentityPreview.roleColor)
    }

    @Test fun easelAndPaywallOrnaments() {
        val p = app.plead.android.features.paywall.PlanOrnament
        assertEquals("EXHIBIT A", ReadyEaselExhibit.label)
        assertEquals(app.plead.android.features.paywall.PlanOrnament.Kind.scales, p.ornament(app.plead.android.services.PurchasesService.Plan.annual))
        assertEquals(app.plead.android.features.paywall.PlanOrnament.Kind.logoHeart, p.ornament(app.plead.android.services.PurchasesService.Plan.monthly))
        assertEquals(app.plead.android.features.paywall.PlanOrnament.Kind.logoHeart, p.ornament(app.plead.android.services.PurchasesService.Plan.weekly))
        assertEquals(OnboardingContainer.aboutChromeHeight, OnboardingContainer.chromeHeight(OnboardingStep.oath))
    }

    @Test fun howPleadWorksCardsAreStraight() {
        assertTrue(HowPleadWorksView.looks.all { it.tilt == 0.0 })
    }

    /** Android (amendments bb, be): the wordmark's ink is symmetric in its canvas, so no optical nudge. */
    @Test fun welcomeLogoHasNoOpticalNudge() {
        assertEquals(0f, app.plead.android.designsystem.PleadLogo.defaultOpticalOffset, 0f)
    }
}

/** Amendment bf (port of iOS `PageTurnHapticTests`). */
class PageTurnHapticTests {
    @Test fun firesOncePerForwardAdvanceOnly() {
        val d = UserDefaults.inMemory()
        val m = OnboardingModel(d)
        var count = 0
        m.pageTurnHaptic = { count += 1 }
        assertEquals(0, count)                          // launch
        m.advance()                                     // Welcome → the intro (amendment bg)
        assertEquals(OnboardingStep.aboutIntro, m.step)
        assertEquals(1, count)
        m.advance()                                     // START QUESTIONS → first question
        assertEquals(2, count)
        m.advance()                                     // refused: unanswered
        assertEquals(2, count)
        m.select("most_days", AboutQuestion.frequency)  // selection has its own haptic, not this one
        assertEquals(2, count)
        m.back()
        assertEquals(OnboardingStep.aboutIntro, m.step)
        assertEquals(2, count)                          // Back is silent
        m.advance(); m.advance()
        assertEquals(OnboardingStep.aboutTopics, m.step)
        assertEquals(4, count)
        m.go(OnboardingStep.oath)                       // jumps (harness) are silent
        assertEquals(4, count)
        m.takeOath()                                    // the oath plays its own strike + success
        assertEquals(OnboardingStep.mockTrial, m.step)
        assertEquals(4, count)
        m.completeMockTrial()                           // I'M READY FOR COURT (the demo has no ending haptic)
        assertEquals(5, count)
        m.skipMockTrial()
        m.go(OnboardingStep.ready)
        m.advance()                                     // last step: nothing to advance to
        assertEquals(5, count)
        val relaunched = OnboardingModel(d)
        var other = 0
        relaunched.pageTurnHaptic = { other += 1 }
        assertEquals(0, other)                          // resume is silent
        m.go(OnboardingStep.notifications)
        m.advance(haptic = false)                       // notifications just enabled: its own haptic only
        assertEquals(5, count)
        assertEquals(0.75f, OnboardingHaptics.pageTurnIntensity, 0f)
    }
}

/** Amendment bg: the intro's flexible hero (pure rule). */
class AboutIntroLayoutTests {
    @Test fun heroAbsorbsSpareHeightThenGivesItBackFirst() {
        val s = app.plead.android.features.onboarding.about.AboutIntroScreen
        // Tall phone: the hero takes what the text leaves (no empty band), up to maxHero.
        assertEquals(400f, s.heroHeight(available = 750f, width = 412f, text = 350f), 0f)
        assertEquals(s.maxHero.value, s.heroHeight(available = 1200f, width = 412f, text = 350f), 0f)
        // Short phone, large text: the hero gives height back first, then the page scrolls.
        assertEquals(AboutMotion.heroMin.value, s.heroHeight(available = 480f, width = 360f, text = 470f), 0f)
    }
}
