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

    test("does not register the removed ecia command") {
        val descriptor = EciaPaperContractTest::class.java.classLoader
            .getResourceAsStream("plugin.yml")!!.bufferedReader().readText()
        descriptor.contains("commands:") shouldBe false
        descriptor.contains("/ecia") shouldBe false
    }
})
