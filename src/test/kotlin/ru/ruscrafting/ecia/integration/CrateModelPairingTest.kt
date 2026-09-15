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
        CrateModelPairing.isOpenVariant("darkworldpack:darkworld_open_chest") shouldBe true
        CrateModelPairing.isOpenVariant("darkworld:royal_chest") shouldBe false
    }

    test("shell catalog keeps actual chests and rejects unrelated ItemsAdder furniture") {
        CrateModelPairing.isShellCandidate("akiraset:akira_chest") shouldBe true
        CrateModelPairing.isShellCandidate("elitecreatures:vinland_animated_weapon-chest") shouldBe true
        CrateModelPairing.isShellCandidate("elitecreatures:jf2_money-chest") shouldBe true
        CrateModelPairing.isShellCandidate("bear_set:flag") shouldBe false
        CrateModelPairing.isShellCandidate("elitecreatures:medieval_market_decoration_v2_crate_1") shouldBe false
        CrateModelPairing.isShellCandidate("itemshopplus:storage_box") shouldBe false
        CrateModelPairing.isShellCandidate("elitecreatures:egyptanubis_animated_weapon_set_chestplate") shouldBe false
        CrateModelPairing.isShellCandidate("akiraset:akira_chest_opening") shouldBe false
    }

    test("keeps namespace when matching closed suffix conventions") {
        CrateModelPairing.openingModel(
            "event:winter_crate_closed",
            setOf("event:winter_crate_open"),
        ) shouldBe "event:winter_crate_open"
    }
})
