package com.inmc.invkeeper.listener

import com.inmc.invkeeper.InvKeeper
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack

/**
 * 남의 각인 아이템을 **쓰지** 못하게 막는다.
 *
 * 옮기는 것만 막으면 부족하다. 상자에 든 남의 각인 검을 꺼내지 못해도, 죽은 자리에 떨어진
 * 것을 밟고 서서 휘두를 수는 있기 때문이다. 손에 든 것이 남의 것이면 행동 자체를 막는다.
 *
 * [EventPriority.LOW] 로 받는다. 보호권 사용 같은 우리 쪽 다른 처리보다 먼저 막아야
 * "쓸 수 없는 아이템인데 효과는 났다"가 생기지 않는다.
 */
class SoulbindUseListener(private val inv: InvKeeper) : Listener {

    /** 허공 클릭은 처음부터 '취소됨'으로 태어난다(클릭한 블록이 없어 블록 사용이 DENY) — `ignoreCancelled` 로 받으면 허공 클릭이 통째로 빠진다. 다른 플러그인이 막았는지는 아이템 사용 쪽을 본다. */
    @EventHandler(priority = EventPriority.LOW)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return
        if (block(event.item, event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (block(event.player.inventory.itemInMainHand, event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (block(event.itemInHand, event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (block(player.inventory.itemInMainHand, player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        // 활과 화살 둘 다 본다. 남의 각인 화살을 내 활로 쏘는 것도 막아야 한다.
        if (block(event.bow, player) || block(event.consumable, player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val player = event.entity.shooter as? Player ?: return
        if (block(player.inventory.itemInMainHand, player)) event.isCancelled = true
    }

    /** 막아야 하면 알리고 true. */
    private fun block(stack: ItemStack?, player: Player): Boolean {
        val now = System.currentTimeMillis()
        if (!inv.guard.isBlocked(stack, player, now)) return false
        inv.guard.denyUse(stack!!, player, now)
        return true
    }
}
