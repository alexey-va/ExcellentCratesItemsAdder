package ru.ruscrafting.ecia.screens

import ru.arc.config.Config
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuTextContract
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap

/**
 * Stable menu schema for the crate opening screens.
 *
 * The Kotlin renderer owns actions and domain values. `menus.yml` owns slots,
 * materials, model data and Russian wording, and is validated as one catalog
 * before it is installed in [ru.ruscrafting.ecia.runtime.EciaRuntime].
 */
object EciaMenuConfiguration {
    const val RESOURCE = "menus.yml"

    private val namesByConfiguration = Collections.synchronizedMap(WeakHashMap<PaperMenuConfiguration, Map<String, String>>())
    private val fallbackByConfiguration = Collections.synchronizedMap(WeakHashMap<PaperMenuConfiguration, String>())
    private val labelsByConfiguration = Collections.synchronizedMap(WeakHashMap<PaperMenuConfiguration, Map<String, String>>())

    val HISTORY = MenuId.of("ecia-history")
    val POOL_PREVIEW = MenuId.of("ecia-pool-preview")
    val POOL_PREVIEWS: Map<Int, MenuId> = (2..4).associateWith { rows ->
        MenuId.of("ecia-pool-preview-$rows")
    }

    val ENTRIES = MenuRegionId.of("entries")
    val REWARDS = MenuRegionId.of("rewards")
    val FOOTER = MenuRegionId.of("footer")

    private fun elements(vararg ids: String) = ids.mapTo(linkedSetOf(), MenuElementId::of)

    /** Semantic contracts consumed by [EciaMenuScreens]. */
    val contracts: Map<MenuId, MenuContract> = linkedMapOf(
        HISTORY to MenuContract(
            requiredElements = elements("info", "previous", "next"),
            requiredRegions = setOf(ENTRIES),
        ),
    ).apply {
        put(POOL_PREVIEW, MenuContract(
            requiredElements = elements("previous", "next"),
            requiredRegions = setOf(REWARDS, FOOTER),
        ))
        POOL_PREVIEWS.values.forEach { menu ->
            put(menu, MenuContract(
                requiredElements = elements("previous", "next"),
                requiredRegions = setOf(REWARDS, FOOTER),
            ))
        }
    }

    fun poolPreview(rows: Int): MenuId = requireNotNull(POOL_PREVIEWS[rows]) {
        "Pool preview rows must be between 2 and 4"
    }

    val textContracts: Map<String, PaperMenuTextContract> = mapOf(
        "background" to PaperMenuTextContract(),
        "history-info" to PaperMenuTextContract(values = setOf("count")),
        "history-entry" to PaperMenuTextContract(
            values = setOf("state", "revision"),
        ),
        "pool-reward" to PaperMenuTextContract(values = setOf("weight")),
        "unavailable" to PaperMenuTextContract(),
        "previous" to PaperMenuTextContract(),
        "next" to PaperMenuTextContract(),
    )

    val requiredLabels: Set<String> = setOf(
        "title-history", "title-pool",
        "status-reserved", "status-choosing", "status-mail", "status-delivering",
        "status-delivered", "status-review", "status-aborted",
    )

    fun load(dataRoot: Path): PaperMenuConfiguration = parse(Config(dataRoot, RESOURCE))

    /** Returns the operator-owned case names attached while loading a catalog. */
    fun caseNames(configuration: PaperMenuConfiguration): Map<String, String> =
        namesByConfiguration[configuration].orEmpty()

    /** Localized fallback used when an operator has not named a new case yet. */
    fun caseNameFallback(configuration: PaperMenuConfiguration): String =
        fallbackByConfiguration[configuration] ?: "Кейс"

    /** Returns validated screen labels attached while loading a catalog. */
    fun labels(configuration: PaperMenuConfiguration): Map<String, String> =
        labelsByConfiguration[configuration].orEmpty()

    /** Loads the bundled catalog without relying on a plugin data directory. */
    fun loadResource(classLoader: ClassLoader = EciaMenuConfiguration::class.java.classLoader): PaperMenuConfiguration {
        val root = Files.createTempDirectory("ecia-menu-resource")
        val target = root.resolve(RESOURCE)
        classLoader.getResourceAsStream(RESOURCE).use { source ->
            requireNotNull(source) { "Bundled $RESOURCE is missing" }
            Files.copy(source, target)
        }
        return load(root)
    }

    private fun parse(config: Config): PaperMenuConfiguration {
        val configuration = PaperMenuConfigurationParser.require(
            config = config,
            layoutRoot = "menus.layouts",
            templateRoot = "menus.templates",
            contracts = contracts,
            requiredTemplates = textContracts.keys,
            textContracts = textContracts,
        )
        require(configuration.templates.values.none { it.customModelData == FORBIDDEN_MODEL_DATA }) {
            "menus.templates must not use reserved custom model data $FORBIDDEN_MODEL_DATA"
        }
        POOL_PREVIEWS.forEach { (rows, menu) ->
            val layout = configuration.catalog.require(menu)
            require(layout.rows == rows) { "menus.layouts.$menu must have $rows rows" }
            require(layout.region(REWARDS).size == (rows - 1) * 9) {
                "menus.layouts.$menu rewards must fill every slot above its footer"
            }
            require(layout.region(FOOTER).size == 7) {
                "menus.layouts.$menu footer must keep seven slots between navigation buttons"
            }
        }
        val caseNames = config.keys("menus.case-names").associateWith { id ->
            config.string("menus.case-names.$id")
        }
        require(caseNames.keys.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) }) {
            "menus.case-names keys must be safe bounded identifiers"
        }
        require(caseNames.values.all { it.isNotBlank() && it.length <= 128 }) {
            "menus.case-names values must be bounded non-empty text"
        }
        namesByConfiguration[configuration] = caseNames
        val fallback = config.string("menus.case-name-fallback", "Кейс")
        require(fallback.isNotBlank() && fallback.length <= 128) {
            "menus.case-name-fallback must be bounded non-empty text"
        }
        fallbackByConfiguration[configuration] = fallback
        val labels = config.keys("menus.labels").associateWith { id ->
            config.string("menus.labels.$id")
        }
        require(labels.keys.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) }) {
            "menus.labels keys must be safe bounded identifiers"
        }
        require(labels.values.all { it.isNotBlank() && it.length <= 128 }) {
            "menus.labels values must be bounded non-empty text"
        }
        require(requiredLabels.all(labels::containsKey)) {
            "menus.labels must define every required screen label"
        }
        labelsByConfiguration[configuration] = labels
        return configuration
    }

    private const val FORBIDDEN_MODEL_DATA = 11001
}
