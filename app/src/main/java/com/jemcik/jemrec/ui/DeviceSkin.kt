package com.jemcik.jemrec.ui

import android.os.Build

/**
 * The phone's Android skin, only as far as setup needs it.
 *
 * Setup has to tell the user where Wireless debugging lives, and that path is
 * the one thing about it that changes by make: under System on a Pixel, System
 * & updates on a HONOR, Additional settings on a Xiaomi, and loose at the top
 * of Settings on a Samsung. Naming the right trail beats naming one and hoping.
 *
 * DETECTION IS DELIBERATELY SHALLOW. It reads Build.MANUFACTURER and
 * Build.BRAND - both public, both stable - and nothing else: no hidden system
 * properties, no reflection into `android.os.SystemProperties`, which the
 * greylist blocks anyway. The cost of guessing wrong is one line of navigation
 * text being slightly off, and every place this is shown carries the universal
 * fallback underneath it - search Settings for "Wireless debugging" - so the
 * precision is a nicety, never a dependency. That is why this stays a display
 * concern and gates nothing: an unrecognised phone still finishes setup.
 */
enum class DeviceSkin(
    /**
     * The Settings menu to open on the way to Developer options, e.g. "System &
     * updates". Empty where Developer options sits at the top level of Settings,
     * as it does on Samsung once unlocked.
     */
    private val toDeveloperOptions: String,
) {
    SAMSUNG(""),
    XIAOMI("Additional settings"),
    OPLUS("Additional settings"), // OPPO, OnePlus, realme - one Settings family
    HONOR("System & updates"),
    HUAWEI("System & updates"),
    // Pixel and everyone who keeps Developer options under System: vivo, Sony,
    // Lenovo, ASUS, ZTE/Nubia, Transsion (Tecno/Infinix/itel), and stock.
    STOCK("System"),
    // Unknown falls in with the most common location rather than refusing to
    // guess; the search fallback shown beside it covers the rest.
    UNKNOWN("System");

    /**
     * The path to say out loud, e.g. "System & updates → Developer options →
     * Wireless debugging", or just "Developer options → Wireless debugging"
     * where Developer options is already top-level.
     */
    /**
     * The path to Developer options itself, e.g. "System & updates → Developer
     * options" - for the steps that go there once and then flip two toggles in
     * place (USB debugging, then Wireless debugging) rather than naming the
     * full route twice.
     */
    val developerOptionsPath: String
        get() = listOf(toDeveloperOptions, "Developer options")
            .filter { it.isNotEmpty() }
            .joinToString(" → ")

    val wirelessDebuggingPath: String
        get() = listOf(toDeveloperOptions, "Developer options", "Wireless debugging")
            .filter { it.isNotEmpty() }
            .joinToString(" → ")

    companion object {
        fun current(): DeviceSkin = of(Build.MANUFACTURER, Build.BRAND)

        /** The decision, given the two strings, so it can be tested off-device. */
        internal fun of(manufacturer: String?, brand: String?): DeviceSkin {
            val id = "$manufacturer $brand".lowercase()
            return when {
                id.contains("samsung") -> SAMSUNG
                id.contains("xiaomi") || id.contains("redmi") || id.contains("poco") -> XIAOMI
                id.contains("oppo") || id.contains("oneplus") || id.contains("realme") -> OPLUS
                id.contains("honor") -> HONOR
                id.contains("huawei") -> HUAWEI
                id.contains("google") || id.contains("pixel") || id.contains("sony") ||
                    id.contains("motorola") || id.contains("nothing") || id.contains("vivo") ||
                    id.contains("iqoo") || id.contains("lenovo") || id.contains("asus") ||
                    id.contains("zte") || id.contains("nubia") || id.contains("tecno") ||
                    id.contains("infinix") || id.contains("itel") -> STOCK
                else -> UNKNOWN
            }
        }
    }
}
