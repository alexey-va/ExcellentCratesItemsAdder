package ru.ruscrafting.ecia.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecia.KeyGrantRequest
import ru.ruscrafting.ecia.SerializedKeyDelivery
import su.nightexpress.excellentcrates.key.CrateKey
import java.util.UUID

class NativeSeasonKeysTest : FunSpec({
    test("addon-issued key uses configured name and preserves native metadata through network delivery") {
        MockBukkitTestRuntime.open().use {
            val itemsAdderId = NamespacedKey("itemsadder", "vinland_animated_weapon_key")
            val excellentCratesId = NamespacedKey("excellentcrates", "key_id")
            val seasonId = NamespacedKey.fromString("ecia:season")!!
            val native = ItemStack(Material.PAPER)
            native.editMeta { meta ->
                meta.setCustomModelData(12236)
                meta.persistentDataContainer.set(itemsAdderId, PersistentDataType.STRING, "vinland_animated_weapon-key")
                meta.persistentDataContainer.set(excellentCratesId, PersistentDataType.STRING, "daily")
                meta.persistentDataContainer.set(seasonId, PersistentDataType.STRING, "old-season")
            }
            val originalBytes = native.serializeAsBytes()
            val configuredName = "<#fff4a3>Ключ от кейса «<#70f0a5>Ежедневный тайник<#fff4a3>»"
            val key = mockk<CrateKey>()
            every { key.itemStack } returns native
            every { key.name } returns configuredName

            val issued = NativeSeasonKeys.createKeyStack(key, 3, "daily")

            issued.amount shouldBe 3
            issued.itemMeta.displayName() shouldBe MiniMessage.miniMessage().deserialize(configuredName)
                .decoration(TextDecoration.ITALIC, false)
            issued.itemMeta.persistentDataContainer.get(itemsAdderId, PersistentDataType.STRING) shouldBe
                "vinland_animated_weapon-key"
            issued.itemMeta.persistentDataContainer.get(excellentCratesId, PersistentDataType.STRING) shouldBe "daily"
            issued.itemMeta.persistentDataContainer.get(seasonId, PersistentDataType.STRING) shouldBe "daily"
            issued.itemMeta.customModelData shouldBe 12236
            native.serializeAsBytes().contentEquals(originalBytes) shouldBe true

            val request = KeyGrantRequest.parse(
                "Steve_23", "daily", 3, "survival", "daily",
                UUID.fromString("d83c2c25-c728-49ab-8a80-caf247f4b0d2"),
            )!!
            val restored = SerializedKeyDelivery.create(request, issued)!!.item()
            restored.serializeAsBytes().contentEquals(issued.serializeAsBytes()) shouldBe true
        }
    }

    test("plain addon-issued keys use the configured name and clear only the managed season marker") {
        MockBukkitTestRuntime.open().use {
            val itemsAdderId = NamespacedKey("itemsadder", "vinland_animated_weapon_key")
            val seasonId = NamespacedKey.fromString("ecia:season")!!
            val native = ItemStack(Material.PAPER)
            native.editMeta { meta ->
                meta.setCustomModelData(12236)
                meta.persistentDataContainer.set(itemsAdderId, PersistentDataType.STRING, "vinland_animated_weapon-key")
                meta.persistentDataContainer.set(seasonId, PersistentDataType.STRING, "old-season")
            }
            val key = mockk<CrateKey>()
            every { key.itemStack } returns native
            val configuredName = "<#70f0a5>Case key"
            every { key.name } returns configuredName

            val issued = NativeSeasonKeys.createKeyStack(key, 1, null)

            issued.itemMeta.displayName() shouldBe MiniMessage.miniMessage().deserialize(configuredName)
                .decoration(TextDecoration.ITALIC, false)
            issued.itemMeta.persistentDataContainer.has(itemsAdderId, PersistentDataType.STRING) shouldBe true
            issued.itemMeta.persistentDataContainer.has(seasonId, PersistentDataType.STRING) shouldBe false
            issued.itemMeta.customModelData shouldBe 12236
        }
    }
})
