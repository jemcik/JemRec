package com.jemcik.jemrec.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the listener will and will not hide. The whole safety argument for a
 * notification listener that snoozes things is that it recognises exactly the
 * debugging banners and nothing else, so that is what these pin down.
 */
class DebugNotificationCleanerTest {

    private fun banner(
        packageName: String? = "android",
        id: Int = 0,
        channelId: String? = null,
        title: String? = null,
        text: String? = null,
    ) = DebugNotificationCleaner.isDebuggingBanner(packageName, id, channelId, title, text)

    @Test fun theBannersAreRecognisedByNumberInAnyLanguage() {
        assertTrue(banner(id = 62, channelId = "DEVELOPER_IMPORTANT", title = "Бездротове налагодження"))
        assertTrue(banner(id = 26, channelId = "DEVELOPER", title = "USB-Debugging aktiviert"))
    }

    @Test fun theEnglishWordIsTheFallback() {
        assertTrue(banner(title = "Wireless debugging connected"))
        assertTrue(banner(text = "Tap to turn off USB debugging"))
    }

    @Test fun aMatchingNumberOnAnotherChannelIsNotEnough() {
        assertFalse(banner(id = 62, channelId = "ALERTS", title = "Something else"))
    }

    @Test fun nothingFromAnotherAppIsTouched() {
        assertFalse(banner(packageName = "com.example.chat", title = "Debugging tips"))
        assertFalse(banner(packageName = "com.example.chat", id = 62, channelId = "DEVELOPER_IMPORTANT"))
        assertFalse(banner(packageName = null, title = "Wireless debugging connected"))
    }

    @Test fun anOrdinarySystemNotificationIsLeftAlone() {
        assertFalse(banner(id = 17, channelId = "ALERTS", title = "Storage is running low"))
    }
}
