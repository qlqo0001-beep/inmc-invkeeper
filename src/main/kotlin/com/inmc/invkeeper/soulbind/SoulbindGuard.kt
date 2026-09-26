package com.inmc.invkeeper.soulbind

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.util.Ph
import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.Text
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "이 사람이 이 아이템을 만져도 되는가"를 판정하고, 안 되면 알린다.
 *
 * 1세대는 이 판정과 메시지 쿨다운을 리스너 네 벌에 각각 복사해 뒀다 — `canBypass` 가 네 번,
 * 쿨다운 맵이 두 번. 한 곳에 모으면 권한 노드가 늘거나 줄 때 고칠 자리도 한 곳이다.
 */
class SoulbindGuard(private val inv: InvKeeper) {

    private val lastPickupMessage = ConcurrentHashMap<UUID, Long>()
    private val lastUseMessage = ConcurrentHashMap<UUID, Long>()

    /** 관리자와 우회 권한자는 남의 각인 아이템도 만질 수 있다. */
    fun canBypass(player: Player): Boolean =
        player.hasPermission(ADMIN) || player.hasPermission(BYPASS)

    /**
     * [player] 가 [stack] 을 만지면 막아야 하는지.
     *
     * 막아야 할 때만 true 다. 각인이 없거나, 만료됐거나, 본인 것이거나, 우회 권한이 있으면 false.
     * **만료된 각인은 여기서 지운다** — 만료됐는데도 막히는 일이 없어야 하고, 스캐너를 기다리면
     * 최대 몇 초가 걸린다.
     */
    fun isBlocked(stack: ItemStack?, player: Player, now: Long = System.currentTimeMillis()): Boolean {
        if (stack == null || stack.type.isAir) return false
        if (!inv.soulbinds.isSoulbound(stack)) return false

        if (inv.soulbinds.expireIfDue(stack, now)) return false

        val owner = inv.soulbinds.read(stack).owner ?: return false
        if (owner == player.uniqueId) return false
        return !canBypass(player)
    }

    /** 줍기·이동을 막았다고 알린다. 같은 사람에게 너무 자주 보내지 않는다. */
    fun denyPickup(stack: ItemStack, player: Player, now: Long = System.currentTimeMillis()) {
        deny(stack, player, now, lastPickupMessage, inv.config.pickupMessageCooldownSeconds, "soulbound-cant-pickup")
    }

    /** 사용을 막았다고 알린다. */
    fun denyUse(stack: ItemStack, player: Player, now: Long = System.currentTimeMillis()) {
        deny(stack, player, now, lastUseMessage, inv.config.useMessageCooldownSeconds, "soulbound-cant-use")
    }

    fun forget(playerId: UUID) {
        lastPickupMessage.remove(playerId)
        lastUseMessage.remove(playerId)
    }

    private fun deny(
        stack: ItemStack,
        player: Player,
        now: Long,
        cooldowns: ConcurrentHashMap<UUID, Long>,
        cooldownSeconds: Long,
        key: String,
    ) {
        if (cooldownSeconds > 0) {
            val last = cooldowns[player.uniqueId] ?: 0L
            if (now - last < cooldownSeconds * 1000L) return
            cooldowns[player.uniqueId] = now
        }
        val owner = inv.soulbinds.read(stack).owner
        inv.messages.send(
            player,
            key,
            Ph.of().item(displayNameOf(stack)).owner(inv.soulbinds.nameOf(owner)),
        )
    }

    /** 이름이 붙어 있으면 그 이름, 아니면 재질 이름. 메시지에만 쓴다. */
    private fun displayNameOf(stack: ItemStack): String =
        StoredItem.plainName(stack) ?: stack.type.name.lowercase().replace('_', ' ')

    private companion object {
        const val ADMIN = "invkeeper.admin"
        const val BYPASS = "invkeeper.soulbind.bypass"
    }
}
