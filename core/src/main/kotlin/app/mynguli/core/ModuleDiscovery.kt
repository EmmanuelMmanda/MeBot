package app.mynguli.core

import java.text.Normalizer
import java.util.Locale

/** Catalog browsing never changes the persisted module order or enabled choices. */
object ModuleDiscovery {
    const val ALL = "all"
    const val ENABLED = "enabled"
    const val DISABLED = "disabled"
    private val combiningMarks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun includesStatus(enabled: Boolean, status: String): Boolean = when (status) {
        ENABLED -> enabled
        DISABLED -> !enabled
        else -> true
    }

    fun matches(query: String, searchableText: String): Boolean {
        val words = normalized(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val haystack = normalized(searchableText)
        return words.all { it in haystack }
    }

    fun filter(
        modules: List<FeedModule>,
        status: String = ALL,
        group: String = ALL,
        query: String = "",
        localizedLabels: Map<String, String> = emptyMap(),
    ): List<FeedModule> = modules.filter { module ->
        includesStatus(module.enabled, status) &&
            (group == ALL || ModuleCatalog.preset(module.kind)?.group == group || module.kind == "watch" && group == "signals") &&
            matches(query, listOf(module.kind, module.title, module.query, localizedLabels[module.id].orEmpty()).joinToString(" "))
    }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(combiningMarks, "")
        .lowercase(Locale.ROOT)
        .replace(separators, " ")
        .trim()
}
