package ru.ruscrafting.ecia.integration

/** Naming convention for a closed ItemsAdder model and its animated/open twin. */
object CrateModelPairing {
    fun openingModel(closed: String, registered: Set<String>): String? = candidates(closed)
        .firstOrNull(registered::contains)

    fun isOpenVariant(id: String): Boolean {
        val local = id.substringAfter(':').lowercase()
        return local.endsWith("_opening") || local.endsWith("_opened") || local.endsWith("_open")
            || local.startsWith("open_") || local.startsWith("opened_")
            || "_open_" in local || "_opened_" in local
    }

    /** ItemsAdder case bodies are chest models, not every registered furniture item. */
    fun isShellCandidate(id: String): Boolean {
        if (isOpenVariant(id)) return false
        val local = id.substringAfter(':').lowercase()
        return local == "chest" || local.endsWith("_chest") || local.endsWith("-chest")
    }

    private fun candidates(id: String): List<String> {
        val namespace = id.substringBefore(':', "")
        val local = id.substringAfter(':')
        val prefix = if (namespace.isBlank()) "" else "$namespace:"
        val values = linkedSetOf(
            "${local}_opening",
            "${local}_open",
            "${local}_opened",
        )
        listOf("chest", "crate", "case", "barrel", "box", "coffer").forEach { word ->
            if (local.endsWith(word)) {
                val stem = local.removeSuffix(word)
                values += "${stem}${word}_opening"
                values += "${stem}open_$word"
                values += "${stem}opened_$word"
            }
        }
        if (local.endsWith("_closed")) {
            values += local.removeSuffix("_closed") + "_opening"
            values += local.removeSuffix("_closed") + "_open"
            values += local.removeSuffix("_closed") + "_opened"
        }
        return values.map { prefix + it }
    }
}
