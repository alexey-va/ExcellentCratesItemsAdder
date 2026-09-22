package ru.ruscrafting.ecia.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ManagedCratesServerLoadTest : FunSpec({
    test("retries enabled managed pools after a successful early startup load") {
        var reloads = 0

        // configurationFailed=false represents the successful early load that used to suppress this retry.
        reloadManagedCratesAfterServerLoad(settingsEnabled = true, configurationFailed = false) {
            reloads++
        }

        reloads shouldBe 1
    }

    test("retries failed configuration even when managed rewards are disabled") {
        var reloads = 0

        reloadManagedCratesAfterServerLoad(settingsEnabled = false, configurationFailed = true) {
            reloads++
        }

        reloads shouldBe 1
    }

    test("does not reload a successful disabled configuration") {
        var reloads = 0

        reloadManagedCratesAfterServerLoad(settingsEnabled = false, configurationFailed = false) {
            reloads++
        }

        reloads shouldBe 0
    }
})
