package app.mynguli.core

import kotlin.math.pow

/** Persist stable keys; never persist Android colors or language-dependent labels. */
object Appearance {
    val presets = linkedMapOf(
        "ember" to "#E60046", "sage" to "#23724F", "blue" to "#2468BD",
        "violet" to "#7251B5", "teal" to "#007F80", "amber" to "#A96208",
        "rose" to "#B64573", "slate" to "#53647E",
    )
    val skins = setOf("classic", "paper", "tinted", "oled")
    fun normalizeHex(value: String): String? =
        value.trim().removePrefix("#").takeIf { it.matches(Regex("[a-fA-F0-9]{6}")) }?.let { "#${it.uppercase()}" }
    fun rgb(settings: Settings): Int =
        (if (settings.accent == "custom") normalizeHex(settings.customAccent) else presets[settings.accent])
            .orEmpty().ifBlank { presets.getValue("ember") }.removePrefix("#").toInt(16)
    fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((rgb shr shift) and 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    fun foreground(rgb: Int): Int {
        val l = luminance(rgb)
        return if ((l + .05) / .05 >= 1.05 / (l + .05)) 0x000000 else 0xFFFFFF
    }
    /** Adapt the accent tone for readable labels; keep the saved hex unchanged. */
    fun readableAccent(rgb: Int, dark: Boolean): Int {
        val background = if (dark) 0x23211D else 0xF0EBDF
        val toward = if (dark) 0xFFFFFF else 0x000000
        for (step in 0..100) {
            val t = step / 100.0
            fun c(shift: Int) = ((((rgb shr shift) and 255) * (1 - t) + ((toward shr shift) and 255) * t).toInt()).coerceIn(0,255)
            val result = (c(16) shl 16) or (c(8) shl 8) or c(0)
            if (contrast(result, background) >= 5.0) return result
        }
        return toward
    }
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}
