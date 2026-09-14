package ru.ruscrafting.ecia.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CrateModelPairingTest : FunSpec({
    test("finds the Akira opening twin") {
        CrateModelPairing.openingModel(
            "akiraset:akira_chest",
            setOf("akiraset:akira_chest", "akiraset:akira_chest_opening"),
        ) shouldBe "akiraset:akira_chest_opening"
    }

    test("does not offer opening variants as stationary shells") {
        CrateModelPairing.isOpenVariant("akiraset:akira_chest_opening") shouldBe true
        CrateModelPairing.isOpenVariant("darkworld:royal_chest") shouldBe false
    }

    test("keeps namespace when matching closed suffix conventions") {
        CrateModelPairing.openingModel(
            "event:winter_crate_closed",
            setOf("event:winter_crate_open"),
        ) shouldBe "event:winter_crate_open"
    }
})
