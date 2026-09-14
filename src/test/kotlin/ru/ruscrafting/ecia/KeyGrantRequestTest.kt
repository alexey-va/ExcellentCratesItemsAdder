package ru.ruscrafting.ecia

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class KeyGrantRequestTest : FunSpec({
    test("accepts one exact-backend key grant request") {
        val requestId = UUID.fromString("d83c2c25-c728-49ab-8a80-caf247f4b0d2")
        val request = KeyGrantRequest.parse("Steve_23", "simple-key", 5, "survival", "default", requestId)

        request?.requestId shouldBe requestId
        request?.server?.value shouldBe "survival"
        request?.season shouldBe "default"
    }

    test("accepts configured Floodgate names without widening command tokens") {
        val request = KeyGrantRequest.parse(".Bedrock_1", "daily.key", 1, "spawn", null)

        request?.player?.value shouldBe ".Bedrock_1"
        request?.season shouldBe null
    }

    test("rejects unsafe or unbounded grant inputs") {
        KeyGrantRequest.parse("Steve;op", "simple", 1, "spawn", "launch") shouldBe null
        KeyGrantRequest.parse("Steve", "simple giveall", 1, "spawn", "launch") shouldBe null
        KeyGrantRequest.parse("Steve", "simple", 65, "spawn", "launch") shouldBe null
        KeyGrantRequest.parse("Steve", "simple", 1, "survival;spawn", "launch") shouldBe null
        KeyGrantRequest.parse("Steve", "simple", 1, "spawn", "default;op") shouldBe null
    }

    test("serializes the exact physical item for a backend without ExcellentCrates") {
        MockBukkitTestRuntime.open().use {
            val requestId = UUID.fromString("d83c2c25-c728-49ab-8a80-caf247f4b0d2")
            val request = KeyGrantRequest.parse("Steve_23", "simple-key", 5, "survival", "default", requestId)!!
            val delivery = SerializedKeyDelivery.create(request, ItemStack(Material.TRIPWIRE_HOOK, 5))!!

            delivery.xCommand(200).startsWith(
                "x -servers:survival -player:Steve_23 -timeout:200 arc-crate receive-key " +
                    "$requestId Steve_23 simple-key 5 ",
            ) shouldBe true
            delivery.payload.startsWith("b64_") shouldBe true
            delivery.payload.contains('=') shouldBe false
            delivery.item().type shouldBe Material.TRIPWIRE_HOOK
            delivery.item().amount shouldBe 5
        }
    }

    test("rejects an unsafe serialized delivery token") {
        SerializedKeyDelivery.parse(listOf(
            "d83c2c25-c728-49ab-8a80-caf247f4b0d2",
            "Steve_23",
            "simple-key",
            "5",
            "payload;op",
        )) shouldBe null
    }
})
