package io.adhush.android

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Plain-language explanations, one per idea, shown from the ⓘ buttons and
 * the Help page. Written for someone who has never heard the jargon: what a
 * "bug" is, what a "script" is, what ducking is, and what each of the six
 * methods actually looks or listens for.
 */
object Help {
    class Topic(val title: String, val body: String)

    val BUG = Topic("What is the \"bug\"?",
        "The bug is the small channel logo that sits in a corner of the picture during a show — the MSNBC peacock, the CNN letters, the network's name. " +
        "Broadcasters call it a \"bug\" because it never goes away, like an insect on the glass.\n\n" +
        "During a commercial the bug is taken off the screen. That is the whole trick: the camera looks at the corner where the bug lives; " +
        "bug there means your show is on, bug gone for a couple of seconds means a commercial has started.\n\n" +
        "The camera has to see the WHOLE TV to know where the corner is. If only part of the TV is in the picture, or the picture is blurred, " +
        "the app says \"whole TV not in view\" and does nothing — it never mutes just because it cannot see. " +
        "It also has to have SEEN the bug at least once since it started before it is allowed to say the bug is gone.")

    val SCRIPT = Topic("What is a \"script\"?",
        "A script is the words of one commercial, written down. \"Ask your doctor if Zyprexa is right for you\" is part of a script.\n\n" +
        "The app can hear words two ways: the microphone plus an offline speech recogniser (Speech), or the camera reading the closed captions " +
        "off the bottom of the screen (Captions). Either way it keeps the last three hours of words.\n\n" +
        "Commercials say exactly the same words every time they air; a show never repeats ten words in a row. So every ten minutes the app looks " +
        "for a run of words that came back identically minutes apart and saves it as a script. When you use Is an ad and Show's back, the words " +
        "in between are saved as a script straight away. From then on, hearing or reading a saved script ducks the TV by itself.")

    val DUCK = Topic("Ducking, muting and \"Not an ad\"",
        "Ducking means turning the TV down to a whisper (the \"Duck to\" volume on the TV page, 4 by default) instead of muting it. " +
        "The phone can still hear the TV a little, which is how it notices when the show is back. Muting makes the phone deaf, so ducking is the default.\n\n" +
        "Not an ad: press it whenever the TV went quiet during your show. The volume comes back at once, whatever caused the duck is dropped, " +
        "and for one minute nothing is allowed to duck again. The camera then has to see the bug again before it may say the bug is gone.\n\n" +
        "The app never fights your remote: if you turn the volume up yourself while it is ducked, it stands down.")

    val TEACH = Topic("Teaching it (Is an ad / Show's back)",
        "Press Is an ad the moment a commercial break starts. The TV ducks and stays ducked. Press Show's back the second your show returns. " +
        "Everything in between — the sound (as numbers, never a recording) and the words — is filed as commercial material.\n\n" +
        "After five or six breaks the app recognises those commercials on its own, in any order, and ducks within a few seconds. " +
        "Pressed Is an ad by mistake? Not an ad cancels it without learning anything.")

    val SILENCE = Topic("Method 1 — Quiet gaps",
        "Broadcasters leave a short silence (a fraction of a second) between the show and each commercial, and between commercials. " +
        "The microphone listens for those gaps at the level of the room's background. On its own this method is a hint, not a verdict: it needs another method to agree.")

    val LOUDNESS = Topic("Method 2 — Loudness jumps",
        "Commercials are mixed to sound louder than the show they interrupt (the CALM Act limits the average, not how it feels). " +
        "The microphone tracks how loud the show normally is and notices when the sound jumps up and stays up. Like quiet gaps, this is a hint that needs a second opinion. " +
        "It works best after the ten-minute Room survey has measured this room.")

    val FINGERPRINTS = Topic("Method 3 — Remembered breaks",
        "Every commercial you bracket with Is an ad and Show's back is remembered as an audio fingerprint (a stream of numbers, not a recording). " +
        "When the same commercial airs again the fingerprint matches within a few seconds and the TV ducks on its own. This is the most reliable method once it has been taught, " +
        "and the only one that can act alone with no help from the others.")

    val CAMERA = Topic("Method 4 — Channel bug (camera)",
        "The back camera watches the corner of the TV where the channel's bug lives. Bug gone for 2.5 seconds means a commercial; bug back means the show. " +
        "Set it up once per channel on the Camera setup screen: zoom in until the bug is big but the whole TV still fits, press Watch 45 s, check the magnified box shows the bug, Save.\n\n" +
        "It only works while the whole TV is in the picture and sharp; otherwise it says so and stays out of the decision. Hand-held is fine — the TV is found again in every frame and the box follows the bug.")

    val SPEECH = Topic("Method 5 — Spoken words (speech)",
        "An offline speech recogniser (a 40 MB model downloaded once; nothing leaves the phone) turns what the microphone hears into words. " +
        "Commercials repeat their words verbatim, so runs of words that come back minutes apart are saved as scripts and recognised from then on. " +
        "Legal and sales phrases (\"ask your doctor\", \"call now\") duck by themselves. Loud room fans make this method mishear; captions are the deaf-proof version.")

    val CAPTIONS = Topic("Method 6 — On-screen captions",
        "With closed captions switched on in the TV's own menu, the camera reads the caption text off the bottom of the screen. " +
        "The words go into the same script matching as speech, but nothing is misheard over the room's fans: what the TV prints is what the app reads. " +
        "It needs the whole TV in view, reasonably close or zoomed, and captions big enough to read — the Camera setup screen shows what the camera sees.\n\n" +
        "No cable can deliver captions to the phone: the TV's serial and network control ports only carry commands (volume, power, input), and infrared is one-way into the set. The camera is the only way in.")

    val CLAUDE = Topic("Method 7 — Ask Claude (cloud AI)",
        "Every ten seconds or so, the last 40 seconds of words (from Speech or Captions) are sent to Claude, Anthropic's AI, with one question: is a commercial playing right now? " +
        "It answers COMMERCIAL or SHOW with a confidence and a few words why. Unlike the script methods it needs no memory of the commercial: it recognises someone selling something from the words alone.\n\n" +
        "It costs money: about 13 cents per hour of TV with Haiku, the cheap model, in \"always\" mode, and a tenth of that in tie-breaker mode (ask only when the other methods are unsure, or while ducked). " +
        "A confident COMMERCIAL answer is saved as a script, so the next airing is recognised offline for free.\n\n" +
        "Privacy: only text leaves the phone, never audio, and only while this switch is on. The microphone also hears the room, so words spoken near the phone can be in that text. Anthropic keeps API data for 30 days. " +
        "You need an API key from console.anthropic.com; it is stored in the app's encrypted settings.")

    val LOCAL = Topic("Method 8 — Local AI (on the phone)",
        "The same question as Method 7, answered by a small language model running on the phone itself (Qwen 2.5, 0.5 billion parameters, about 550 MB, downloaded once). " +
        "Nothing leaves the phone and nothing costs money. Each answer takes a few seconds of CPU, so it asks on a cadence, not on every word, and the phone runs warmer while it is on.\n\n" +
        "It is less sharp than Claude — a small model hedges more and is fooled by garbled speech more easily — but it is free, private and works without Wi-Fi. " +
        "Like Method 7 it needs words from Speech or Captions, and a confident COMMERCIAL answer is saved as a script.")

    val TICKER = Topic("The news ticker instead of the bug",
        "On a news channel the lower part of the picture carries a band — the ticker, or the chyron with the headline — whose top and bottom edges are straight lines that never move, while the words inside scroll. " +
        "During a commercial the band is gone. Camera setup can watch that band instead of the corner bug: choose \"news ticker\" under Channel bug, press Camera setup, Watch 45 s, and the yellow box should sit on the band.\n\n" +
        "Same rules as the bug: the whole TV must be in view, the band must have been seen once before its absence counts, and it must be gone for 2.5 seconds. " +
        "It works on channels that keep a ticker up through the whole show; some shows drop it for interviews, which would read as a commercial — then use the bug instead.")

    val METHODS = Topic("Choosing methods",
        "Any mix of the eight methods can be on, but AT LEAST ONE must be on or the app has nothing to go on and will refuse to start.\n\n" +
        "Quiet gaps and loudness jumps are hints: two of them have to agree before the TV is ducked. Remembered breaks, the channel bug or ticker, spoken words, captions, and the two AI judges are each strong enough to duck on their own.\n\n" +
        "The AI judges (7 and 8) need words to read, so they only work together with Speech or Captions.\n\n" +
        "A good starting set: the three sound methods on, plus the camera once you have set the bug or ticker up, plus captions if the TV shows them, plus one AI judge as a tie-breaker.")

    val TEST = Topic("Test mode",
        "Test TV talks to the set the way the app does when a commercial comes on: it asks the volume, mutes and unmutes, ducks and restores, and prints every byte it sent and got back. " +
        "If the sound dips twice, the connection works. It uses whatever connection is chosen on the TV page — network, serial cable or infrared.")

    val ALL = listOf(METHODS, BUG, TICKER, SCRIPT, DUCK, TEACH, TEST, SILENCE, LOUDNESS, FINGERPRINTS, CAMERA, SPEECH, CAPTIONS, CLAUDE, LOCAL)

    fun show(context: Context, topic: Topic) {
        MaterialAlertDialogBuilder(context).setTitle(topic.title).setMessage(topic.body).setPositiveButton("Got it", null).show()
    }
}
