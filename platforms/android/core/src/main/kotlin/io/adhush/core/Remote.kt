package io.adhush.core

/**
 * The Sharp AQUOS remote-control keys reachable through the control port
 * (`RCKY` with a two-digit code, operation manual for the 2010–2011 LE
 * series). Same codes over the network and the serial cable. A set that does
 * not know a code answers ERR, which the app reports.
 */
enum class RemoteKey(val code: Int, val label: String) {
    POWER(12, "Power"), INPUT(36, "Input"), DISPLAY(13, "Display"), SLEEP(24, "Sleep"),
    DIGIT_0(0, "0"), DIGIT_1(1, "1"), DIGIT_2(2, "2"), DIGIT_3(3, "3"), DIGIT_4(4, "4"),
    DIGIT_5(5, "5"), DIGIT_6(6, "6"), DIGIT_7(7, "7"), DIGIT_8(8, "8"), DIGIT_9(9, "9"),
    DOT(10, "•"), ENT(11, "ENT"),
    CH_UP(34, "CH ▲"), CH_DOWN(35, "CH ▼"), FLASHBACK(30, "Flashback"), FAV(47, "Fav"),
    VOL_UP(33, "VOL +"), VOL_DOWN(32, "VOL −"), MUTE(31, "Mute"),
    UP(41, "▲"), DOWN(42, "▼"), LEFT(43, "◀"), RIGHT(44, "▶"), ENTER(40, "OK"),
    MENU(38, "Menu"), SMART(39, "Smart"), RETURN(45, "Return"), EXIT(46, "Exit"),
    CC(27, "CC"), AUDIO(49, "Audio"), AV_MODE(28, "AV mode"), VIEW_MODE(29, "View"), FREEZE(54, "Freeze"),
    REW(15, "◀◀"), PLAY(16, "Play"), FF(17, "▶▶"), PAUSE(18, "Pause"), STOP(20, "Stop"),
    RED(50, "A"), GREEN(51, "B"), BLUE(52, "C"), YELLOW(53, "D"), NETFLIX(59, "Netflix");

    companion object { fun of(name: String): RemoteKey? = entries.firstOrNull { it.name == name } }
}

/** Press one remote key through the control port. */
fun SharpIpClient.press(key: RemoteKey): Boolean = remoteKey(key.code)
