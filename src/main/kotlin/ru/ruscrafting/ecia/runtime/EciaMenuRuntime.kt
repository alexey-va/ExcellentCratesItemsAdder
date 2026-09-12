package ru.ruscrafting.ecia.runtime

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.Tasks
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuRuntime

/** Narrow holder for the validated configurable menu runtime used by future ECIA screens. */
class EciaMenuRuntime private constructor(
    private val delegate: PaperMenuRuntime,
) : AutoCloseable {
    fun current(): PaperMenuConfiguration = delegate.current()

    fun replace(configuration: PaperMenuConfiguration) = delegate.replace(configuration)

    fun runtime(): PaperMenuRuntime = delegate

    override fun close() = delegate.close()

    companion object {
        @JvmStatic
        fun create(plugin: JavaPlugin, configuration: PaperMenuConfiguration): EciaMenuRuntime =
            EciaMenuRuntime(PaperMenuRuntime(plugin, Tasks.scheduler, configuration))
    }
}
