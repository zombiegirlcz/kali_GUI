package com.linux_core.xlauncher.input.key

import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks the US-layout key tables against accidental edits. */
class X11KeyTableTest {

    @Test fun letters() {
        assertEquals(38, X11KeyTable.letter(0))   // a
        assertEquals(52, X11KeyTable.letter(25))  // z
        assertEquals(26, X11KeyTable.letter('e' - 'a'))
        assertEquals(26, X11KeyTable.LETTER_KEYCODES.size)
    }

    @Test fun digits() {
        assertEquals(19, X11KeyTable.digit(0))
        assertEquals(10, X11KeyTable.digit(1))
        assertEquals(18, X11KeyTable.digit(9))
    }

    @Test fun functionKeys() {
        assertEquals(67, X11KeyTable.functionKey(0))  // F1
        assertEquals(76, X11KeyTable.functionKey(9))  // F10
        assertEquals(95, X11KeyTable.functionKey(10)) // F11
        assertEquals(96, X11KeyTable.functionKey(11)) // F12
    }

    @Test fun escape() {
        assertEquals(9, X11KeyTable.ESCAPE)
    }
}
