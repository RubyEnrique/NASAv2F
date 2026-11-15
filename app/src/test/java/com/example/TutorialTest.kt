package com.example

import com.example.gesture.DashboardMode
import com.example.gesture.DashboardPage
import com.example.gesture.HandLandmarkPoint
import com.example.gesture.HandObservation
import com.example.gesture.HandPose
import com.example.hud.HudEvent
import com.example.hud.HudViewModel
import com.example.tutorial.TutorialScript
import com.example.tutorial.TutorialTrigger
import com.example.voice.VoiceCommand
import com.example.voice.VoiceCommandRecognizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TutorialTest {
  private fun palm(): List<HandObservation> =
    listOf(
      HandObservation(
        listOf(
            0.50f to 0.70f, 0.44f to 0.65f, 0.40f to 0.58f, 0.36f to 0.52f, 0.33f to 0.46f,
            0.44f to 0.52f, 0.43f to 0.42f, 0.42f to 0.35f, 0.42f to 0.28f,
            0.50f to 0.50f, 0.50f to 0.39f, 0.50f to 0.31f, 0.50f to 0.24f,
            0.56f to 0.52f, 0.57f to 0.41f, 0.58f to 0.34f, 0.59f to 0.27f,
            0.62f to 0.56f, 0.64f to 0.47f, 0.66f to 0.41f, 0.67f to 0.35f,
          )
          .map { (x, y) -> HandLandmarkPoint(x, y) },
        HandPose.OPEN_PALM,
        0.9f,
      )
    )

  @Test
  fun voiceParsesTutorialCommands() {
    fun p(s: String) = VoiceCommandRecognizer.parseVoiceCommand(s)
    assertEquals(VoiceCommand.TUTORIAL_ON, p("tutorial on"))
    assertEquals(VoiceCommand.TUTORIAL_ON, p("tutorial"))
    assertEquals(VoiceCommand.TUTORIAL_OFF, p("tutorial off"))
    assertEquals(VoiceCommand.TUTORIAL_NEXT, p("next"))
    assertEquals(VoiceCommand.TUTORIAL_BACK, p("back"))
    assertEquals(VoiceCommand.TUTORIAL_REPEAT, p("repeat"))
    assertEquals(VoiceCommand.OPEN_PERMANENT, p("open dashboard"))
  }

  @Test
  fun scriptCoversAllFiveHazardsInOrder_andTeachesEachGesture() {
    val pages = TutorialScript.steps.mapNotNull { it.page }
    for (h in DashboardPage.ring) assertTrue("missing $h", h in pages)
    val waits = TutorialScript.steps.map { it.waitFor }.filter { it != TutorialTrigger.NONE }
    assertEquals(
      listOf(TutorialTrigger.OPEN_DASHBOARD, TutorialTrigger.SWIPE, TutorialTrigger.CENTER_LOCK, TutorialTrigger.ACKNOWLEDGE),
      waits,
    )
  }

  @Test
  fun tutorialSpeaks_waitsForTheTaughtGesture_autoAdvances_andStops() = runTest {
    val vm = HudViewModel(enablePeriodicDecay = false)
    val spoken = mutableListOf<String>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { if (it is HudEvent.Speak) spoken.add(it.text) } }

    vm.onVoiceCommand(VoiceCommand.TUTORIAL_ON)
    assertEquals(0, vm.uiState.value.tutorial?.index)
    assertTrue(spoken.last().startsWith("Welcome to the Mars spatial HUD"))

    // Narrative step: advances 2.5 s after the speech finishes.
    vm.onSpeechFinished(10_000L)
    vm.maybeAutoAdvanceTutorial(11_000L)
    assertEquals(0, vm.uiState.value.tutorial?.index)
    vm.maybeAutoAdvanceTutorial(12_600L)
    assertEquals(1, vm.uiState.value.tutorial?.index)
    assertEquals(DashboardMode.CLOSED, vm.uiState.value.dashboardMode)

    // Thumbs-up / "next" also advances narrative steps.
    vm.onVoiceCommand(VoiceCommand.TUTORIAL_NEXT)
    val openStep = vm.uiState.value.tutorial!!
    assertEquals(TutorialTrigger.OPEN_DASHBOARD, openStep.waitingFor)

    // Interactive step never auto-advances; it waits for the real gesture.
    vm.onSpeechFinished(20_000L)
    vm.maybeAutoAdvanceTutorial(40_000L)
    assertEquals(openStep.index, vm.uiState.value.tutorial?.index)
    for (t in 0L..640L step 33L) vm.onHandsDetected(palm(), t)
    assertEquals(DashboardMode.PERMANENT, vm.uiState.value.dashboardMode)
    assertEquals(openStep.index + 1, vm.uiState.value.tutorial?.index)

    // Hazard steps open the matching window.
    vm.onVoiceCommand(VoiceCommand.TUTORIAL_NEXT)
    assertEquals(DashboardPage.RADIATION, vm.uiState.value.currentPage)
    vm.onVoiceCommand(VoiceCommand.TUTORIAL_BACK)
    assertEquals(DashboardPage.ISOLATION, vm.uiState.value.currentPage)
    val before = spoken.size
    vm.onVoiceCommand(VoiceCommand.TUTORIAL_REPEAT)
    assertEquals(before + 1, spoken.size)

    vm.onVoiceCommand(VoiceCommand.TUTORIAL_OFF)
    assertNull(vm.uiState.value.tutorial)
    assertEquals("Tutorial off.", spoken.last())
    job.cancel()
  }
}
