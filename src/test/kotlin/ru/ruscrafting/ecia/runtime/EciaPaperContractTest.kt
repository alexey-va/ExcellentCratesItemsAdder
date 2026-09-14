package ru.ruscrafting.ecia.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime

/** Keeps the Paper boundary on the published arc-core fixture. */
class EciaPaperContractTest : FunSpec({
    test("opens and closes the canonical MockBukkit runtime per test") {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.isOpen shouldBe true
        }
    }

    test("registers only the standalone arc-crate administrator command") {
        val descriptor = EciaPaperContractTest::class.java.classLoader
            .getResourceAsStream("plugin.yml")!!.bufferedReader().readText()
        descriptor.contains("commands:") shouldBe true
        descriptor.contains("  arc-crate:") shouldBe true
        descriptor.contains("/ecia") shouldBe false
        descriptor.contains("  case:") shouldBe false
    }
})
