package com.inmc.invkeeper.listener

import com.inmc.invkeeper.InvKeeper
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 각인 아이템이 주인 아닌 손으로 넘어가는 것을 막는다.
 *
 * 막는 방식은 전부 **`setCancelled(true)` 하나**다. 슬롯을 직접 조작하면 위/아래 인벤토리의
 * 슬롯 번호 체계가 달라 엉뚱한 칸을 건드린다 — 1세대가 남긴 주석이고 그대로 지킨다.
 */
class SoulbindMoveListener(private val inv: InvKeeper) : Listener {

    /**
     * 바닥에서 줍기.
     *
     * 주인이 없는 자동각인 대상이면 **여기서 줍는 사람에게 찍힌다.** 스캐너가 나중에 어차피
     * 훑지만, 줍는 순간 로어가 바뀌어야 사람이 납득한다.
     */
    @EventHandler(ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val stack = event.item.itemStack
        if (stack.type.isAir) return

        val now = System.currentTimeMillis()
        if (inv.guard.isBlocked(stack, player, now)) {
            event.isCancelled = true
            inv.guard.denyPickup(stack, player, now)
            return
        }

        // 만료로 각인이 풀렸거나 자동각인이 찍혔으면 땅에 있는 엔티티에도 반영해야 한다.
        var changed = inv.soulbinds.expireIfDue(stack, now)
        if (inv.autoBind.bindIfEligible(stack, player, now)) changed = true
        if (changed) event.item.itemStack = stack
    }

    /** 클릭으로 옮기기. 집는 쪽·놓는 쪽 아이템 둘 다 본다. */
    @EventHandler(ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val now = System.currentTimeMillis()

        for (stack in listOf(event.currentItem, event.cursor)) {
            if (stack == null || stack.type.isAir) continue
            if (!inv.guard.isBlocked(stack, player, now)) continue
            event.isCancelled = true
            inv.guard.denyPickup(stack, player, now)
            return
        }

        // 자동각인은 다음 틱에 본다. 이 시점의 슬롯은 아직 옮겨지기 전이라, 실제로 어디에
        // 들어갔는지는 이벤트가 끝나야 확정된다.
        //
        // **관련된 아이템이 실제 대상일 때만** 예약한다. 인벤토리 클릭은 GUI 조작마다 나는
        // 아주 잦은 이벤트라, 클릭할 때마다 41칸을 훑으면 그게 비용이 된다. 대부분의 클릭은
        // 등록조차 안 된 아이템이고 아래 검사는 재질 조회 한 번에 끝난다.
        val touched = event.currentItem ?: event.cursor
        if (inv.autoBind.eligibleFor(touched, now) == null) return
        player.scheduler.run(inv.plugin, { inv.autoBind.sweep(player) }, null)
    }

    /** 드래그로 옮기기. 커서에 든 것이 남의 각인이면 통째로 막는다. */
    @EventHandler(ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val now = System.currentTimeMillis()
        val dragged = event.oldCursor
        if (dragged.type.isAir) return
        if (!inv.guard.isBlocked(dragged, player, now)) return

        event.isCancelled = true
        inv.guard.denyPickup(dragged, player, now)
    }

    /**
     * 호퍼·드로퍼 같은 **자동 이동**.
     *
     * 주인이 있는 각인 아이템은 기계로 옮길 수 없다. 이게 없으면 호퍼 한 줄로 남의 각인
     * 아이템을 세탁할 수 있다.
     */
    @EventHandler(ignoreCancelled = true)
    fun onAutoMove(event: InventoryMoveItemEvent) {
        if (isOwned(event.item)) event.isCancelled = true
    }

    /** 호퍼가 바닥의 아이템을 빨아들이는 경우. 위와 같은 이유의 이중 방어다. */
    @EventHandler(ignoreCancelled = true)
    fun onHopperPickup(event: InventoryPickupItemEvent) {
        if (event.inventory.type != InventoryType.HOPPER) return
        if (isOwned(event.item.itemStack)) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        inv.guard.forget(event.player.uniqueId)
        inv.players.forget(event.player.uniqueId)
    }

    /** 주인이 정해진 각인이 붙어 있는지. 기계는 우회 권한이 없으므로 이것만 보면 된다. */
    private fun isOwned(stack: org.bukkit.inventory.ItemStack?): Boolean {
        if (stack == null || stack.type.isAir) return false
        if (!inv.soulbinds.isSoulbound(stack)) return false
        val state = inv.soulbinds.read(stack)
        return state.owner != null && state.isAlive(System.currentTimeMillis())
    }

}
