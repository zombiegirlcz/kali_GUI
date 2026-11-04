package com.linux_core.xlauncher.input.key

/**
 * Pure Android-independent mapping tables from logical keys to X11 keycodes
 * (US layout), the counterpart to the Windows App's scan-code mapping data in
 * `com.microsoft.windowsapp.input` (`AsciiToScanCodeMappings`,
 * `ScanCodeToKeyCodeMappingsKt`, …). Kept free of `android.view.KeyEvent` so it
 * is unit-testable on a plain JVM; the thin Android dispatch that calls it
 * lives in `LauncherActivity.xKeycode` (the `KeyEventExtKt` role).
 */
object X11KeyTable {

    /** X keycode for Escape (the Android IME has no Esc key). */
    const val ESCAPE = 9

    /**
     * X keycodes for a US layout, indexed by `letter - 'a'`. The X physical
     * order follows QWERTY, not the alphabet, so a table is required.
     */
    val LETTER_KEYCODES = intArrayOf(
        38, // a
        56, // b
        54, // c
        40, // d
        26, // e
        41, // f
        42, // g
        43, // h
        31, // i
        44, // j
        45, // k
        46, // l
        58, // m
        57, // n
        32, // o
        33, // p
        24, // q
        27, // r
        39, // s
        28, // t
        30, // u
        55, // v
        25, // w
        53, // x
        29, // y
        52, // z
    )

    /** X keycode for the letter at [index] (0 = 'a' … 25 = 'z'). */
    fun letter(index: Int): Int = LETTER_KEYCODES[index]

    /** X keycode for a decimal [digit] 0..9 (0 is physically after 9 on the row). */
    fun digit(digit: Int): Int = if (digit == 0) 19 else 9 + digit

    /**
     * X keycode for function key F(1+[index]) with [index] 0..11. F1..F10 are
     * contiguous at 67; F11/F12 jump to 95/96.
     */
    fun functionKey(index: Int): Int = if (index < 10) 67 + index else 95 + (index - 10)
}
