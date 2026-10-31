package ru.ruscrafting.ecia

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.core.ExecutorTaskScheduler
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.redis.network.RedisRequestTimeoutHandle
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.arc.testing.containers.RedisTestService
import ru.ruscrafting.ecia.api.CrateAcquisitionReward
import ru.ruscrafting.ecia.api.CrateAcquisitionSnapshot
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CrateReadNetworkIntegrationTest : FreeSpec({
    "Survival reads the current complete source pool over real Redis without native ExcellentCrates" {
        RedisTestService.start("redis:7.4-alpine").use { service ->
            ExecutorTaskScheduler().use { scheduler ->
                val connection = RedisConnection(service.endpoint.host, service.endpoint.port)
                val sourceRedis = RedisManager(connection, ServerIdentity { "spawn" })
                val remoteRedis = RedisManager(connection, ServerIdentity { "survival" })
                val timeouts = RedisRequestTimeoutScheduler { delay, action ->
                    val task = scheduler.runLaterAsync((delay + 49L) / 50L, Runnable(action))
                    RedisRequestTimeoutHandle(task::cancel)
                }
                val snapshot = CrateAcquisitionSnapshot("case_daily", "Daily", true, listOf(
                    CrateAcquisitionReward("ae", "AE box", 25.0, 0.25, "case_daily", "ae"),
                    CrateAcquisitionReward("native", "Native reward", 75.0, 0.75, "", ""),
                ))
                CrateReadNetwork(sourceRedis, { true }, { CompletableFuture.completedFuture(listOf(snapshot)) },
                    { null }, mockk(), System::currentTimeMillis, scheduler, timeouts).use {
                    CrateReadNetwork(remoteRedis, { false }, { error("Remote must not capture native rewards") },
                        { error("Remote must not construct a native key") }, mockk(), System::currentTimeMillis,
                        scheduler, timeouts).use { remote ->
                        sourceRedis.init()
                        remoteRedis.init()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while ((!sourceRedis.isSubscriptionActive() || !remoteRedis.isSubscriptionActive()) && System.nanoTime() < deadline) {
                            Thread.sleep(10)
                        }
                        sourceRedis.isSubscriptionActive() shouldBe true
                        remoteRedis.isSubscriptionActive() shouldBe true
                        remote.acquisitionSnapshotsAsync().get(10, TimeUnit.SECONDS) shouldBe listOf(snapshot)
                    }
                }
            }
        }
    }
})
