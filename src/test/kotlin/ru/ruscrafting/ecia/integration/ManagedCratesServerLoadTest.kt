package ru.ruscrafting.ecia.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler

class ManagedCratesServerLoadTest : FunSpec({
    test("retries enabled managed pools after a successful early startup load") {
        var reloads = 0
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        var presetsLoaded = false
        scheduler.runLater(3L) { presetsLoaded = true }

        // configurationFailed=false represents the successful early load that used to suppress this retry.
        reloadManagedCratesAfterServerLoad(settingsEnabled = true, configurationFailed = false, tasks) {
            presetsLoaded shouldBe true
            reloads++
        }

        reloads shouldBe 0
        scheduler.tick(3)
        reloads shouldBe 0
        scheduler.tick()
        reloads shouldBe 1
        scheduler.tick(20)
        reloads shouldBe 1
    }

    test("retries failed configuration even when managed rewards are disabled") {
        var reloads = 0
        val scheduler = TestTaskScheduler()

        reloadManagedCratesAfterServerLoad(settingsEnabled = false, configurationFailed = true, LifecycleTaskScope(scheduler)) {
            reloads++
        }

        scheduler.tick(4)
        reloads shouldBe 1
    }

    test("does not reload a successful disabled configuration") {
        var reloads = 0
        val scheduler = TestTaskScheduler()

        reloadManagedCratesAfterServerLoad(settingsEnabled = false, configurationFailed = false, LifecycleTaskScope(scheduler)) {
            reloads++
        }

        scheduler.pendingCount() shouldBe 0
        scheduler.tick(4)
        reloads shouldBe 0
    }

    test("closing the runtime cancels the deferred pool reload") {
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        var reloads = 0
        reloadManagedCratesAfterServerLoad(settingsEnabled = true, configurationFailed = false, tasks) {
            reloads++
        }

        tasks.close()
        scheduler.tick(4)

        reloads shouldBe 0
        scheduler.pendingCount() shouldBe 0
    }
})
