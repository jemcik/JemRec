package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The Default USB configuration's answers (UsbDefault.decide): read, set to
 * none, or nothing written - with the default as UsbManager's bits (MTP is
 * 4, PTP 16, RNDIS 32, MIDI 8, ADB 1; GadgetFunction.aidl).
 */
public class UsbDefaultTest {

    private static final long MTP = 1L << 2;
    private static final long PTP = 1L << 4;

    @Test
    public void aReadSaysTheDefault() {
        assertEquals("USB default 4", UsbDefault.decide(UsbDefault.READ, MTP, false, false));
        assertEquals("USB default 0", UsbDefault.decide(UsbDefault.READ, 0L, false, false));
        // A call or a fetch is no reason not to answer a read.
        assertEquals("USB default 16", UsbDefault.decide(UsbDefault.READ, PTP, true, false));
    }

    @Test
    public void aDefaultThatCannotBeAskedIsSaidSo() {
        assertEquals(Wire.USB_UNREADABLE, UsbDefault.decide(UsbDefault.READ, null, false, false));
        assertEquals(Wire.USB_UNREADABLE, UsbDefault.decide(UsbDefault.SET_NONE, null, false, false));
    }

    @Test
    public void aDataModeIsSetToNone() {
        assertEquals(Wire.USB_SETTING, UsbDefault.decide(UsbDefault.SET_NONE, MTP, false, false));
        assertEquals(Wire.USB_SETTING, UsbDefault.decide(UsbDefault.SET_NONE, MTP | PTP, false, false));
    }

    @Test
    public void nothingIsWrittenDuringACallOrAFetch() {
        assertEquals(Wire.USB_BUSY, UsbDefault.decide(UsbDefault.SET_NONE, MTP, true, false));
    }

    @Test
    public void nothingIsWrittenWhileTelecomHoldsACall() {
        // Calls.busyLocked leaves a ring out (a quit may go then); a write
        // could end the recorder just before the call is answered - so any
        // call Telecom holds (TelecomManager.isInCall) holds the write.
        assertEquals(Wire.USB_BUSY, UsbDefault.decide(UsbDefault.SET_NONE, MTP, false, true));
        // A read is answered all the same.
        assertEquals("USB default 4", UsbDefault.decide(UsbDefault.READ, MTP, false, true));
    }

    @Test
    public void noneAlreadyIsNotWrittenAgain() {
        // Setting none again would switch the current functions to charging
        // and cut a computer's file transfer for nothing.
        assertEquals("USB default 0", UsbDefault.decide(UsbDefault.SET_NONE, 0L, false, false));
        assertEquals("USB default 1", UsbDefault.decide(UsbDefault.SET_NONE, UsbDefault.FUNCTION_ADB, false, false));
        assertEquals("USB default 0", UsbDefault.decide(UsbDefault.SET_NONE, 0L, true, false));
    }

    @Test
    public void onlyAnExplicitOneWrites() {
        // A byte that never came reads as -1: a read, never a write.
        assertEquals("USB default 4", UsbDefault.decide(-1, MTP, false, false));
        assertEquals("USB default 4", UsbDefault.decide(2, MTP, false, false));
    }

    @Test
    public void aReadIsLoggedOnlyWhenItSaysSomethingNew() {
        UsbDefault.forgetLogged();
        assertTrue("the first", UsbDefault.logOnce(UsbDefault.READ, "USB default 4"));
        assertFalse("the same again", UsbDefault.logOnce(UsbDefault.READ, "USB default 4"));
        assertTrue("changed", UsbDefault.logOnce(UsbDefault.READ, "USB default 0"));
        assertFalse("and noted", UsbDefault.logOnce(UsbDefault.READ, "USB default 0"));
        // A set always: the emulator check reads its line.
        assertTrue(UsbDefault.logOnce(UsbDefault.SET_NONE, "USB setting"));
        assertTrue(UsbDefault.logOnce(UsbDefault.SET_NONE, "USB setting"));
        assertTrue("a read after a set", UsbDefault.logOnce(UsbDefault.READ, "USB default 0"));
        UsbDefault.forgetLogged();
    }

    @Test
    public void debuggingAloneIsNoDataMode() {
        assertTrue(UsbDefault.none(0L));
        assertTrue(UsbDefault.none(UsbDefault.FUNCTION_ADB));
        assertFalse(UsbDefault.none(MTP));
        assertFalse(UsbDefault.none(MTP | UsbDefault.FUNCTION_ADB));
    }
}
