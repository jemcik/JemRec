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
     * The path to Developer options, e.g. "System & updates → Developer
     * options" - for the steps that go there once and then flip both debugging
     * switches in place rather than naming the route twice.
     */
    val developerOptionsPath: String
        get() = listOf(toDeveloperOptions, "Developer options")
            .filter { it.isNotEmpty() }
            .joinToString(" → ")

    /**
     * The same thing as the end of a sentence, because "usually under
     * Developer options" is not a sentence anybody can act on. Where Developer
     * options is already top-level - Samsung, once it is unlocked - there is no
     * menu to name, so it says where to look instead.
     */
    val developerOptionsHint: String
        get() = if (toDeveloperOptions.isEmpty()) "in the main Settings list"
        else "under $toDeveloperOptions"

    companion object {
        /**
         * The markers of a ROM that is not the maker's own.
         *
         * THE MANUFACTURER IS NOT THE SKIN, and a custom ROM is where the two
         * come apart. A OnePlus running LineageOS still reports OnePlus in
         * Build.MANUFACTURER, so it was told to look under "Additional
         * settings" - OxygenOS's menu, which that phone does not have. Its
         * Developer options are under System, like any AOSP build. Reported
         * from a real phone, not imagined.
         *
         * Build.DISPLAY, PRODUCT, ID and FINGERPRINT are public fields, so no
         * hidden system property is read for this. LineageOS names itself in
         * DISPLAY and PRODUCT ("lineage_instantnoodle-userdebug"), and the
         * others here do the same. A ROM that hides itself completely simply
         * falls through and gets its maker's path, which is where it started.
         */
        private val CUSTOM_ROMS = listOf(
            "lineage", "aosp", "calyx", "graphene", "omni", "crdroid", "havoc",
            "evolution", "pixelexperience", "resurrection", "arrow", "derpfest",
            "paranoid", "aokp", "microg", "iode", "divest", "e-os", "e_os",
        )

        fun current(): DeviceSkin = of(
            Build.MANUFACTURER,
            Build.BRAND,
            listOf(Build.DISPLAY, Build.PRODUCT, Build.ID, Build.FINGERPRINT)
                .joinToString(" "),
        )

        /** The decision, given the strings, so it can be tested off-device. */
        internal fun of(
            manufacturer: String?,
            brand: String?,
            rom: String? = null,
        ): DeviceSkin {
            val id = "$manufacturer $brand".lowercase()
            val build = rom.orEmpty().lowercase()
            // Asked FIRST, before the maker: on a custom ROM the maker's menu
            // is the one thing that is certainly not there.
            if (CUSTOM_ROMS.any { build.contains(it) }) return STOCK
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
