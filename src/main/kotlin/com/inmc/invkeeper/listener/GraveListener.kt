package com.inmc.invkeeper.listener

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.grave.Grave
import com.inmc.invkeeper.grave.GraveMenu
import com.inmc.invkeeper.grave.GraveState
import com.inmc.invkeeper.grave.RecoveryType
import com.inmc.invkeeper.item.ItemKind
import com.inmc.invkeeper.util.Ph
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerInteractEvent

/**
 * 무덤 블록과의 모든 상호작용.
 *
 * 블록 보호 네 가지는 `config.yml` 의 `grave.protection.*` 를 **실제로 읽는다.** 1세대에서는
 * 그 설정이 코드에 연결돼 있지 않아, 끄고 싶어도 끌 수 없었고 후퍼 항목은 기능 자체가 없었다.
 */
class GraveListener(private val inv: InvKeeper) : Listener {

    /** 무덤 우클릭. 주인이면 열고, 아니면 도굴 도구를 확인한다. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val grave = inv.graves.at(block.location) ?: return

        event.isCancelled = true
        val player = event.player
        val now = System.currentTimeMillis()

        if (grave.ownerId == player.uniqueId) {
            // 주인이 확인하면 진행 중인 도굴은 그 자리에서 무산된다.
            inv.looting.cancelByOwner(grave, player)
            open(player, grave)
            return
        }

        if (grave.state == GraveState.LOOTED || player.hasPermission(BYPASS)) {
            open(player, grave)
            return
        }

        val held = player.inventory.itemInMainHand
        val tool = inv.items.identify(held)
        if (tool == null || tool.kind != ItemKind.GRAVE_LOOT_TOOL) {
            inv.messages.send(player, "grave-loot-item-required")
            inv.messages.send(player, "grave-not-owner", Ph.of().owner(grave.ownerName))
            return
        }

        inv.looting.start(player, grave, tool.castSeconds, now)
    }

    private fun open(player: Player, grave: Grave) {
        GraveMenu(
            inv = inv,
            ownerName = grave.ownerName,
            contents = grave.contents,
            onEmptied = { who ->
                val type = if (who.uniqueId == grave.ownerId) RecoveryType.OWNER else RecoveryType.LOOTER
                val now = System.currentTimeMillis()
                grave.markRecovered(type, now)
                inv.looting.forget(grave.id)
                inv.graves.dispose(grave, now)
                inv.messages.send(who, "grave-fully-recovered")
            },
            onChanged = { inv.graves.store.markDirty() },
        ).open(player)
        inv.messages.send(player, "grave-opened")
    }

    // --- 블록 보호 -----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!inv.config.grave.protection.preventBlockBreak) return
        if (inv.graves.at(event.block.location) == null) return
        event.isCancelled = true
        inv.messages.send(event.player, "grave-not-owner", Ph.of().owner(ownerNameAt(event.block)))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (!inv.config.grave.protection.preventExplosion) return
        event.blockList().removeAll { inv.graves.at(it.location) != null }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (!inv.config.grave.protection.preventExplosion) return
        event.blockList().removeAll { inv.graves.at(it.location) != null }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (!inv.config.grave.protection.preventPiston) return
        if (event.blocks.any { inv.graves.at(it.location) != null }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (!inv.config.grave.protection.preventPiston) return
        if (event.blocks.any { inv.graves.at(it.location) != null }) event.isCancelled = true
    }

    /**
     * 후퍼가 무덤에서 빨아가는 것.
     *
     * 바닐라 무덤 블록은 장식이라 진짜 내용물이 없지만, 커스텀 블록 제공자가 실제 컨테이너를
     * 놓는 경우가 있다. 그때를 위한 것이다.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHopper(event: InventoryMoveItemEvent) {
        if (!inv.config.grave.protection.preventHopper) return
        val source = event.source.location ?: return
        if (inv.graves.at(source) != null) event.isCancelled = true
    }

    private fun ownerNameAt(block: Block): String =
        inv.graves.at(block.location)?.ownerName ?: "?"

    private companion object {
        const val BYPASS = "invkeeper.grave.bypass"
    }
}
