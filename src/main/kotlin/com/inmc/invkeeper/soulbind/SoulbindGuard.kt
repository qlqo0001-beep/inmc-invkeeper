package com.inmc.invkeeper.soulbind

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.util.Ph
import io.papermc.paper.datacomponent.DataComponentTypes
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

    /** 우회를 잠시 끈 관리자(`/인벤키퍼 각인 우회 끄기`) — 남의 각인 규칙을 직접 겪어 보는 시험용. 메모리에만, 나가면 풀린다. */
    private val bypassOff: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 관리자와 우회 권한자는 남의 각인 아이템도 만질 수 있다. */
    fun canBypass(player: Player): Boolean =
        player.uniqueId !in bypassOff && (player.hasPermission(ADMIN) || player.hasPermission(BYPASS))

    fun setBypassTest(player: Player, off: Boolean) {
        if (off) bypassOff += player.uniqueId else bypassOff -= player.uniqueId
    }

    /**
     * [player] 가 [stack] 을 만지면 막아야 하는지.
     *
     * 막아야 할 때만 true 다. 각인이 없거나, 만료됐거나, 본인 것이거나, 우회 권한이 있으면 false.
     * **만료된 각인은 여기서 지운다** — 만료됐는데도 막히는 일이 없어야 하고, 스캐너를 기다리면
     * 최대 몇 초가 걸린다.
     *
     * **꾸러미·셜커 상자에 든 남의 각인 아이템도 그릇째 막는다**(2026-10-07 버그). 안 그러면 꾸러미에 넣어 건넨 각인 아이템을
     * 받은 사람이 꺼내는 순간부터 줍지도 옮기지도 못하고, 손에 든 꾸러미를 우클릭해 쏟으면 땅에서 그대로 사라졌다.
     */
    fun isBlocked(stack: ItemStack?, player: Player, now: Long = System.currentTimeMillis()): Boolean =
        culprit(stack, player, now) != null

    /** 막는 까닭이 된 각인 아이템 — [stack] 자신이거나 그 안에 담긴 것. 막을 것이 없으면 null. */
    private fun culprit(stack: ItemStack?, player: Player, now: Long, depth: Int = 0): ItemStack? {
        if (stack == null || stack.type.isAir) return null
        if (blockedItself(stack, player, now)) return stack
        if (depth >= MAX_DEPTH) return null
        for (inner in contents(stack)) culprit(inner, player, now, depth + 1)?.let { return it }
        return null
    }

    private fun blockedItself(stack: ItemStack, player: Player, now: Long): Boolean {
        if (!inv.soulbinds.isSoulbound(stack)) return false

        if (inv.soulbinds.expireIfDue(stack, now)) return false

        val owner = inv.soulbinds.read(stack).owner ?: return false
        if (owner == player.uniqueId) return false
        return !canBypass(player)
    }

    /** 꾸러미·셜커 상자(아이템 상태)에 담긴 것. 그 밖의 아이템은 빈 목록. 사본이다 — 만료를 지워도 그릇에는 안 남는다. */
    private fun contents(stack: ItemStack): List<ItemStack> = runCatching {
        stack.getData(DataComponentTypes.BUNDLE_CONTENTS)?.contents()
            ?: stack.getData(DataComponentTypes.CONTAINER)?.contents()
    }.getOrNull().orEmpty()

    /** 줍기·이동을 막았다고 알린다. 같은 사람에게 너무 자주 보내지 않는다. */
    fun denyPickup(stack: ItemStack, player: Player, now: Long = System.currentTimeMillis()) {
        deny(stack, player, now, lastPickupMessage, inv.config.pickupMessageCooldownSeconds, "soulbound-cant-pickup")
    }

    /** 사용을 막았다고 알린다. */
    fun denyUse(stack: ItemStack, player: Player, now: Long = System.currentTimeMillis()) {
        deny(stack, player, now, lastUseMessage, inv.config.useMessageCooldownSeconds, "soulbound-cant-use")
    }

    fun forget(playerId: UUID) {
        bypassOff.remove(playerId)
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
        // 꾸러미째 막았으면 그 안의 각인 아이템 이름·주인으로 알린다.
        val shown = culprit(stack, player, now) ?: stack
        val owner = inv.soulbinds.read(shown).owner
        inv.messages.send(
            player,
            key,
            Ph.of().item(displayNameOf(shown)).owner(inv.soulbinds.nameOf(owner)),
        )
    }

    /** 이름이 붙어 있으면 그 이름, 아니면 재질 이름. 메시지에만 쓴다. */
    private fun displayNameOf(stack: ItemStack): String =
        StoredItem.plainName(stack) ?: stack.type.name.lowercase().replace('_', ' ')

    private companion object {
        /** 꾸러미 안의 꾸러미를 몇 겹까지 보는가. 바닐라 무게 한도로 깊어질 수 없지만 손으로 만든 아이템을 막는다. */
        const val MAX_DEPTH = 4
        const val ADMIN = "invkeeper.admin"
        const val BYPASS = "invkeeper.soulbind.bypass"
    }
}
