package com.inmc.invkeeper.protection

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.item.ItemDefinition
import com.inmc.invkeeper.item.ItemKind
import com.inmc.invkeeper.soulbind.AutoBindService
import kr.inmc.core.util.Durations
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** 사망 순간 보호가 어떻게 걸렸는지. */
enum class ProtectionResult {
    /** 보호 없음. 드랍 규칙대로 잃는다. */
    NONE,

    /** 시간형 보호가 켜져 있었다. 아이템을 소모하지 않는다. */
    TIMED,

    /** 소모형 보호권 하나를 써서 막았다. */
    CONSUMABLE,
}

/**
 * 보호권 두 종류를 관리한다.
 *
 * 시간형 보호는 **플레이어의 PDC** 에 만료 시각으로 들어간다. 메모리에 두지 않는 이유는 서버가
 * 꺼졌다 켜져도 남아야 하기 때문이고, 파일에 두지 않는 이유는 플레이어 데이터가 이미 그 일을
 * 하고 있기 때문이다.
 *
 * PDC 네임스페이스는 [com.inmc.invkeeper.soulbind.SoulbindKeys] 와 같은 이유로 `invkeeper` 로
 * 고정한다 — 1세대가 켜둔 보호가 그대로 이어져야 한다.
 */
class ProtectionService(private val inv: InvKeeper) {

    // --- 시간형 보호 ---------------------------------------------------------------

    fun isTimedActive(player: Player, now: Long = System.currentTimeMillis()): Boolean =
        timedRemainingMillis(player, now) > 0L

    fun timedRemainingMillis(player: Player, now: Long = System.currentTimeMillis()): Long {
        val expiry = player.persistentDataContainer.get(EXPIRY, PersistentDataType.LONG) ?: return 0L
        return (expiry - now).coerceAtLeast(0L)
    }

    fun timedRemainingText(player: Player, now: Long = System.currentTimeMillis()): String =
        Durations.formatShort(timedRemainingMillis(player, now) / 1000L)

    fun activateTimed(player: Player, durationSeconds: Long, now: Long = System.currentTimeMillis()) {
        val expiry = now + durationSeconds.coerceAtLeast(0L) * 1000L
        player.persistentDataContainer.set(EXPIRY, PersistentDataType.LONG, expiry)
    }

    fun clearTimed(player: Player) {
        player.persistentDataContainer.remove(EXPIRY)
    }

    // --- 사망 경로 -----------------------------------------------------------------

    /**
     * 죽는 순간 보호가 걸리는지 보고, 소모형이면 **여기서 한 장 소모한다**.
     *
     * 시간형이 먼저다 — 시간형 보호가 켜져 있는데 소모형까지 태우면 안 된다.
     */
    fun checkAndConsume(player: Player, now: Long = System.currentTimeMillis()): ProtectionResult {
        if (isTimedActive(player, now)) return ProtectionResult.TIMED
        return if (consumeOneConsumable(player)) ProtectionResult.CONSUMABLE else ProtectionResult.NONE
    }

    /** 인벤토리에서 소모형 보호권 한 장을 찾아 하나 줄인다. 없으면 false. */
    fun consumeOneConsumable(player: Player): Boolean {
        val inventory = player.inventory
        for (slot in 0..AutoBindService.LAST_SLOT) {
            val stack = inventory.getItem(slot) ?: continue
            val definition = inv.items.identify(stack) ?: continue
            if (definition.kind != ItemKind.CONSUMABLE_PROTECTION) continue
            takeOne(inventory::setItem, slot, stack)
            return true
        }
        return false
    }

    /** 손에 든 시간형 보호권을 한 장 쓴다. 손의 것이 시간형이 아니면 아무것도 하지 않는다. */
    fun consumeTimedInHand(player: Player): ItemDefinition? {
        val inventory = player.inventory
        val stack = inventory.itemInMainHand
        val definition = inv.items.identify(stack) ?: return null
        if (definition.kind != ItemKind.TIMED_PROTECTION) return null

        val remaining = stack.amount - 1
        if (remaining <= 0) inventory.setItemInMainHand(null)
        else inventory.setItemInMainHand(stack.clone().also { it.amount = remaining })
        return definition
    }

    private inline fun takeOne(setItem: (Int, ItemStack?) -> Unit, slot: Int, stack: ItemStack) {
        val remaining = stack.amount - 1
        if (remaining <= 0) setItem(slot, null)
        else setItem(slot, stack.clone().also { it.amount = remaining })
    }

    private companion object {

        @Suppress("DEPRECATION")
        val EXPIRY = NamespacedKey("invkeeper", "timed_protection_expiry")
    }
}
