package com.example.tutorial

import com.example.gesture.DashboardPage

/*
 * Voice-guided tutorial ("tutorial on"). Narrative steps advance by themselves shortly after the
 * speech ends (or on "next" / thumbs-up); interactive steps wait until the wearer performs the
 * gesture being taught. Steps can open the dashboard on the hazard being explained.
 */

enum class TutorialTrigger(val hint: String) {
  NONE("Say next · or thumbs up"),
  OPEN_DASHBOARD("Hold an open palm · or say open dashboard"),
  SWIPE("Sweep an open hand · or say a hazard name"),
  CENTER_LOCK("Point and hold · or say center"),
  ACKNOWLEDGE("Thumbs up · or say acknowledge"),
}

data class TutorialStep(
  val title: String,
  val speech: String,
  val page: DashboardPage? = null,
  val closeDashboard: Boolean = false,
  val waitFor: TutorialTrigger = TutorialTrigger.NONE,
)

data class TutorialState(
  val index: Int,
  val total: Int,
  val title: String,
  val hint: String,
  val waitingFor: TutorialTrigger,
)

object TutorialScript {
  val steps: List<TutorialStep> =
    listOf(
      TutorialStep(
        "Welcome",
        "Welcome to the Mars spatial HUD. It watches the five hazards NASA lists for human spaceflight, with your mind first. " +
          "Everything is hands free. Say next, back, repeat, or tutorial off at any time. A thumbs up also means next.",
      ),
      TutorialStep(
        "The quiet view",
        "This is the quiet view. The compass sits at the top. Your heart rate, oxygen time, radiation, crew and cognitive load " +
          "sit on the left edge. Alerts appear top right, and the glow on the side edges grows with your mental load. " +
          "White means information, green means nominal, yellow means caution, and red means warning.",
        closeDashboard = true,
      ),
      TutorialStep(
        "Open the dashboard",
        "Let's open the dashboard. Hold an open palm up in front of the camera until the ring fills. You can also say open dashboard.",
        waitFor = TutorialTrigger.OPEN_DASHBOARD,
      ),
      TutorialStep(
        "The window",
        "Each hazard has one window hanging in space. Turn your head to look at the others. The tabs along the bottom show where you are. " +
          "The column on the right always shows Mars time and your cognitive load, and the strip on the left lists the hand gestures.",
        page = DashboardPage.ISOLATION,
      ),
      TutorialStep(
        "1 · Space radiation",
        "Hazard one, space radiation. Radiation can affect thinking and motor control. The dose rate is modeled on NASA's Curiosity rover " +
          "measurements on the surface of Mars. When online, NASA's DONKI service reports solar flares and estimates storm risk. " +
          "The arrow points back to base, and hand steadiness watches your motor control.",
        page = DashboardPage.RADIATION,
      ),
      TutorialStep(
        "2 · Isolation & confinement",
        "Hazard two, isolation and confinement. A hidden front camera counts your blinks and how long your eyes stay closed. " +
          "Together with heart rate, stress, carbon dioxide and sleep, this gives a cognitive load score. Seventy is caution, " +
          "ninety is warning, and ninety five is emergency. The chart forecasts the next ten minutes.",
        page = DashboardPage.ISOLATION,
      ),
      TutorialStep(
        "3 · Distance from Earth",
        "Hazard three, distance from Earth. A message to Earth takes minutes, calculated from NASA JPL orbit data, so you must decide locally. " +
          "The turn back timer predicts when oxygen, battery, or the Axiom suit's eight hour rating runs out, minus your walk home and a thirty minute reserve.",
        page = DashboardPage.DISTANCE,
      ),
      TutorialStep(
        "4 · Gravity",
        "Hazard four, gravity. Mars pulls at thirty eight percent of Earth, so bones and muscles get less load. Your steps are converted to " +
          "Earth equivalent load, and your exercise minutes are compared with the two and a half hours a day that space station crews train.",
        page = DashboardPage.GRAVITY,
      ),
      TutorialStep(
        "5 · Hostile environment",
        "Hazard five, the hostile and closed environment. The live map shows your track and your crew at Jezero crater. Outside air is modeled " +
          "from NASA's Perseverance weather station. Inside, you see suit pressure, carbon dioxide, light, noise and cold exposure.",
        page = DashboardPage.ENVIRONMENT,
      ),
      TutorialStep(
        "Move between hazards",
        "Now try moving between hazards. Sweep an open hand left or right. You can also say a hazard's name, like radiation or gravity.",
        waitFor = TutorialTrigger.SWIPE,
      ),
      TutorialStep(
        "Keep it centered",
        "If you would rather keep the window in front of you, point and hold, or say center. Do it again to release it.",
        waitFor = TutorialTrigger.CENTER_LOCK,
      ),
      TutorialStep(
        "Silence an alert",
        "When an alert speaks, give a thumbs up or say acknowledge to silence it for a minute. Try a thumbs up now.",
        waitFor = TutorialTrigger.ACKNOWLEDGE,
      ),
      TutorialStep(
        "Drills & emergencies",
        "To practise, say emergency, followed by a number from one to ten. Say end all emergencies to stop everything. " +
          "In a real emergency, your crew is told automatically.",
        page = DashboardPage.DRILLS,
      ),
      TutorialStep(
        "Your crew",
        "Nearby phones running this HUD link up without internet. Crewmates appear as dots on the compass and the map, with their distance. " +
          "If one of them needs help, everyone hears where they are.",
        page = DashboardPage.ENVIRONMENT,
      ),
      TutorialStep(
        "Closing & voice",
        "To close the dashboard, hold a fist, or say close dashboard. If voice commands miss you, say calibrate, or sensitivity up. " +
          "To leave the app, tap the top right corner five times.",
      ),
      TutorialStep(
        "Made for Mars",
        "Everything here is tuned for Mars: the Mars clock at Jezero, the signal delay to Earth, Mars gravity, Curiosity and Perseverance data, " +
          "and the Axiom suit. Tutorial complete. Say tutorial on to hear it again.",
        closeDashboard = true,
      ),
    )

  fun stateFor(index: Int): TutorialState {
    val step = steps[index]
    return TutorialState(index, steps.size, step.title, step.waitFor.hint, step.waitFor)
  }
}
