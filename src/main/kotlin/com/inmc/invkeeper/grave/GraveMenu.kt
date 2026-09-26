package com.inmc.invkeeper.grave

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.gui.Menu
import com.inmc.invkeeper.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack

/**
 * 무덤 회수 화면.
 *
 * **블록의 진짜 인벤토리가 아니다.** 무덤 블록은 장식일 뿐이고 내용물은 [GraveContents] 에
 * 있다. 이렇게 해야 후퍼로 빨아가는 것도, 두 사람이 동시에 열어 복사하는 것도 원천적으로
 * 불가능하다 — 화면은 매번 [contents] 에서 다시 그려진다.
 *
 * 배치 (54칸)
 * ```
 *  0~3   방어구 (투구·갑옷·바지·신발)
 *  4     왼손
 *  9~44  보관칸 36개
 *  49    모두 회수
 * ```
 */
class GraveMenu(
    inv: InvKeeper,
    ownerName: String,
    private val contents: GraveContents,
    /**
     * 마지막 한 칸까지 비었을 때 할 일.
     *
     * 살아 있는 무덤이면 무덤을 치우고, 기록에서 연 것이면 기록의 남은 아이템만 지운다.
     * "비었다"를 감지하는 것은 이 화면의 일이지만 **그 뒤에 무엇을 할지는 아니다** —
     * 그래서 호출부에서 받는다.
     */
    private val onEmptied: (Player) -> Unit,
    /** 내용물이 바뀔 때마다. 저장 표시를 남기는 자리다. */
    private val onChanged: () -> Unit = {},
) : Menu(inv, SIZE, Text.render("<dark_gray>$ownerName 의 무덤")) {

    override fun draw() {
        clear()

        for (viewSlot in 0 until SIZE) {
            val source = sourceSlot(viewSlot) ?: continue
            val stack = contents.slots[source] ?: continue
            if (stack.type.isAir) continue
            set(viewSlot, stack) { event -> takeOne(event, viewSlot, source) }
        }

        if (contents.exp > 0) {
            set(EXP_SLOT, expIcon()) { event -> takeExp(event.whoClicked as Player) }
        }

        set(RECOVER_ALL_SLOT, recoverAllIcon()) { event -> recoverAll(event.whoClicked as Player) }
    }

    /** 화면 칸 → [GraveContents] 칸. 배치를 아는 유일한 곳이다. */
    private fun sourceSlot(viewSlot: Int): Int? = when (viewSlot) {
        // 방어구는 인벤토리에서 신발·바지·갑옷·투구(36~39) 순이라 뒤집어 보여준다.
        in 0..3 -> 39 - viewSlot
        4 -> 40
        in 9..44 -> viewSlot - 9
        else -> null
    }

    private fun takeOne(event: InventoryClickEvent, viewSlot: Int, source: Int) {
        val player = event.whoClicked as? Player ?: return
        val stack = contents.take(source) ?: return
        inv.graves.give(player, stack)
        markDirty()
        refresh()
        closeIfEmpty(player)
    }

    private fun takeExp(player: Player) {
        if (contents.exp <= 0) return
        player.giveExp(contents.exp)
        contents.exp = 0
        markDirty()
        refresh()
        closeIfEmpty(player)
    }

    private fun recoverAll(player: Player) {
        inv.graves.giveAll(player, contents)
        markDirty()
        refresh()
        closeIfEmpty(player)
    }

    /** 마지막 한 칸까지 비었으면 호출부가 정한 마무리를 실행한다. */
    private fun closeIfEmpty(player: Player) {
        if (!contents.isEmpty()) return
        onEmptied(player)
        player.closeInventory()
    }

    override fun onClose(event: InventoryCloseEvent) {
        markDirty()
    }

    private fun markDirty() {
        onChanged()
    }

    private fun expIcon(): ItemStack {
        val stack = ItemStack(Material.EXPERIENCE_BOTTLE)
        stack.editMeta { meta ->
            meta.displayName(Text.render("<yellow>경험치 ${contents.exp}exp"))
            meta.lore(listOf(Text.render("<gray>클릭하여 경험치를 회수합니다.")))
        }
        return stack
    }

    private fun recoverAllIcon(): ItemStack {
        val stack = ItemStack(Material.NETHER_STAR)
        stack.editMeta { meta ->
            meta.displayName(Text.render("<green><bold>모두 회수"))
            meta.lore(
                listOf(
                    Text.render("<gray>장비·왼손·보관칸의 모든 아이템과"),
                    Text.render("<gray>경험치를 한 번에 회수합니다."),
                    Text.render("<gray>넣을 자리가 없으면 발밑에 떨어집니다."),
                ),
            )
        }
        return stack
    }

    companion object {
        const val SIZE = 54
        const val EXP_SLOT = 53
        const val RECOVER_ALL_SLOT = 49
    }
}
