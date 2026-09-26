package com.inmc.invkeeper.listener

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.soulbind.BindTool
import com.inmc.invkeeper.util.Ph
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack

/**
 * 각인 도구를 다른 아이템 위에 놓아 각인을 찍거나 지운다.
 *
 * **자기 인벤토리 안에서만** 동작한다. 상점이나 다른 플러그인의 GUI 안에서 도구가 발동하면
 * 관리자가 진열해 둔 아이템에 각인이 찍히거나, 더 나쁘게는 GUI 가 그 변경을 모르고 원본을
 * 덮어써 각인이 증발한다.
 *
 * 무엇을 할지는 [BindTool] 이 정한다. 여기는 그 결정을 아이템에 반영하고 도구를 한 개 쓰는
 * 일만 한다.
 */
class SoulbindToolListener(private val inv: InvKeeper) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!isOwnInventory(event)) return

        val action = event.action
        if (action != InventoryAction.SWAP_WITH_CURSOR && action != InventoryAction.HOTBAR_SWAP) return

        val tool = toolStack(event, player) ?: return
        val target = event.currentItem ?: return
        if (target.type.isAir) return

        val now = System.currentTimeMillis()

        // 만료된 각인은 먼저 치운다. 도구 자신도, 대상도 마찬가지다 - 만료된 각인 때문에
        // 타입 충돌로 거절당하면 사람이 이유를 알 수 없다.
        inv.soulbinds.expireIfDue(tool, now)
        inv.soulbinds.expireIfDue(target, now)

        val definition = inv.items.identify(tool) ?: return
        if (!definition.kind.isSoulbindTool) return

        // 남의 각인이 걸린 아이템은 건드리지 못한다.
        val targetOwner = inv.soulbinds.read(target).owner
        if (targetOwner != null && targetOwner != player.uniqueId && !inv.guard.canBypass(player)) {
            event.isCancelled = true
            inv.guard.denyPickup(target, player, now)
            return
        }

        val decision = BindTool.decide(
            tool = definition.kind,
            applies = definition.applies,
            current = inv.soulbinds.read(target),
            maxStack = inv.config.maxSoulbindStack,
            now = now,
        )
        if (decision == BindTool.Result.Ignore) return

        // 여기부터는 우리가 처리한다. 바닐라의 자리 교환이 일어나면 안 된다.
        event.isCancelled = true

        when (decision) {
            is BindTool.Result.Reject -> {
                inv.messages.send(
                    player,
                    decision.key,
                    Ph.of().type(decision.type).max(decision.max),
                )
                return
            }

            BindTool.Result.Unbind -> {
                inv.soulbinds.remove(target)
                event.currentItem = target
                consumeOne(event, player)
                inv.messages.send(player, "soulbound-unbound")
            }

            is BindTool.Result.Bind -> {
                // 주인이 없던 아이템은 이 사람 것이 되고, 이미 주인이 있으면 그대로 둔다.
                val owner = targetOwner ?: player.uniqueId
                inv.soulbinds.apply(target, owner, decision.strength, now)
                event.currentItem = target
                consumeOne(event, player)

                val state = inv.soulbinds.read(target)
                val ph = Ph.of()
                    .owner(inv.soulbinds.nameOf(owner))
                    .remaining(
                        if (state.type == com.inmc.invkeeper.soulbind.SoulbindType.STACK) {
                            if (state.stacks < 0) "무한" else "${state.stacks}회"
                        } else {
                            state.remainingText(now)
                        },
                    )
                inv.messages.send(player, if (decision.extended) "soulbound-extended" else "soulbound-applied", ph)
            }

            BindTool.Result.Ignore -> Unit
        }
    }

    /**
     * 자기 인벤토리 안에서 일어난 클릭인지.
     *
     * 위쪽 화면이 제작대(자기 인벤토리를 열었을 때)나 플레이어 인벤토리일 때만 참이다.
     * 상자·상점·우리 무덤 화면 안에서는 도구가 발동하지 않는다.
     */
    private fun isOwnInventory(event: InventoryClickEvent): Boolean {
        if (event.clickedInventory?.type != InventoryType.PLAYER) return false
        val top = event.view.topInventory.type
        return top == InventoryType.PLAYER || top == InventoryType.CRAFTING
    }

    /** 도구가 든 자리. 커서에 들고 있거나, 숫자키로 눌러 바꾼 핫바 칸이다. */
    private fun toolStack(event: InventoryClickEvent, player: Player): ItemStack? {
        val stack = when (event.action) {
            InventoryAction.SWAP_WITH_CURSOR -> event.cursor
            InventoryAction.HOTBAR_SWAP -> {
                val slot = event.hotbarButton
                if (slot !in 0..8) return null
                player.inventory.getItem(slot)
            }

            else -> null
        }
        return stack?.takeIf { !it.type.isAir }
    }

    /** 도구를 한 개 쓴다. */
    private fun consumeOne(event: InventoryClickEvent, player: Player) {
        when (event.action) {
            InventoryAction.SWAP_WITH_CURSOR -> {
                val cursor = event.cursor
                if (cursor.type.isAir) return
                val remaining = cursor.amount - 1
                // `event.cursor` 는 읽기 전용이다. 커서는 플레이어가 들고 있는 것이므로
                // 플레이어 쪽으로 쓴다.
                player.setItemOnCursor(
                    if (remaining <= 0) null else cursor.clone().also { it.amount = remaining },
                )
            }

            InventoryAction.HOTBAR_SWAP -> {
                val slot = event.hotbarButton
                if (slot !in 0..8) return
                val stack = player.inventory.getItem(slot) ?: return
                val remaining = stack.amount - 1
                player.inventory.setItem(
                    slot,
                    if (remaining <= 0) null else stack.clone().also { it.amount = remaining },
                )
            }

            else -> Unit
        }
    }
}
