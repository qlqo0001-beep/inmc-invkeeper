package com.inmc.invkeeper.listener

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.util.Ph
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

/**
 * 시간형 보호권을 우클릭으로 쓴다.
 *
 * [EventPriority.NORMAL] 이라 [SoulbindUseListener] 의 `LOW` 보다 뒤에 온다 — 남의 각인이
 * 걸린 보호권이면 그쪽이 먼저 이벤트를 취소하고(아이템 사용 DENY), 여기는 그걸 보고 건너뛴다.
 *
 * 허공 클릭은 처음부터 '취소됨'으로 태어난다(클릭한 블록이 없어 블록 사용이 DENY) — `ignoreCancelled` 로 받으면 허공 클릭이 통째로 빠진다. 다른 플러그인이 막았는지는 아이템 사용 쪽을 본다.
 */
class ProtectionUseListener(private val inv: InvKeeper) : Listener {

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return

        val player = event.player
        val stack = event.item ?: return
        val definition = inv.items.identify(stack) ?: return
        if (definition.kind != com.inmc.invkeeper.item.ItemKind.TIMED_PROTECTION) return

        // 우리 아이템이라고 판정한 이상, 블록 상호작용(상자 열기 등)까지 일어나면 안 된다.
        event.isCancelled = true

        val now = System.currentTimeMillis()
        if (inv.protection.isTimedActive(player, now)) {
            // 이미 켜져 있으면 소모하지 않는다. 1세대와 같다 - 겹쳐 쓰면 시간이 날아간다.
            inv.messages.send(
                player,
                "timed-already-active",
                Ph.of().remaining(inv.protection.timedRemainingText(player, now)),
            )
            return
        }

        if (inv.protection.consumeTimedInHand(player) == null) return
        inv.protection.activateTimed(player, definition.protectionMinutes * 60L, now)
        inv.messages.send(player, "timed-activated", Ph.of().duration(definition.protectionMinutes))
    }
}
