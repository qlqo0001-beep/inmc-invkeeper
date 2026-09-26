package com.inmc.invkeeper.grave

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.gui.Menu
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 살아 있는 무덤 전체 — 1세대 `GraveListGui`. `/인벤키퍼 무덤 목록` 으로 연다.
 *
 * 한 칸이 무덤 하나이고 **클릭하면 그 자리로 간다.** 5세대가 처음에 채팅 목록으로만 옮겨서
 * 관리자가 좌표를 손으로 쳐서 찾아가야 했다.
 */
class GraveListMenu(
    inv: InvKeeper,
    private val viewer: Player,
    private var page: Int = 0,
) : Menu(inv, SIZE, Text.render("<dark_gray>살아 있는 무덤")) {

    override fun draw() {
        clear()
        val graves = inv.graves.store.all().sortedByDescending { it.createdAt }
        page = Paging.clamp(page, graves.size)
        val now = System.currentTimeMillis()

        for ((index, grave) in Paging.slice(graves, page).withIndex()) {
            set(index, tile(grave, now)) { teleport(viewer, grave.worldName, grave.x, grave.y, grave.z) }
        }
        if (graves.isEmpty()) {
            set(SLOT_EMPTY, Icon.of(Material.BARRIER, "<gray>살아 있는 무덤이 없습니다</gray>"))
        }

        if (page > 0) {
            set(Paging.SLOT_PREV, Icon.prevPage()) {
                page--
                refresh()
            }
        }
        if (page < Paging.pageCount(graves.size) - 1) {
            set(Paging.SLOT_NEXT, Icon.nextPage()) {
                page++
                refresh()
            }
        }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun tile(grave: Grave, now: Long): ItemStack = Icon.of(
        Material.GREEN_STAINED_GLASS_PANE,
        "<yellow>${grave.ownerName} 의 무덤</yellow>",
        listOf(
            "<gray>위치: <white>${grave.worldName} ${grave.x}, ${grave.y}, ${grave.z}</white></gray>",
            "<gray>상태: <white>${grave.state.name}</white></gray>",
            "<gray>남은 시간: <white>" +
                (if (grave.hasExpiry) Durations.formatShort(grave.remainingMillis(now) / 1000L) else "무제한") +
                "</white></gray>",
            "<gray>아이템: <white>${grave.contents.countSlots()}칸</white> · 경험치 <white>${grave.contents.exp}</white></gray>",
            "",
            "<green>▶ 클릭: 텔레포트</green>",
        ),
    )

    private companion object {
        const val SIZE = 54
        const val SLOT_EMPTY = 22
    }
}

/**
 * 무덤 자리로 보낸다. 월드가 내려가 있으면 알린다 — 조용히 아무 일도 안 하면 관리자는 버그로 안다.
 * 1세대처럼 블록 가운데로 보낸다.
 */
internal fun teleport(player: Player, worldName: String, x: Int, y: Int, z: Int) {
    val world = Bukkit.getWorld(worldName) ?: run {
        player.sendMessage(Text.render("<red>'$worldName' 월드가 없습니다.</red>"))
        return
    }
    player.closeInventory()
    player.teleportAsync(Location(world, x + 0.5, y.toDouble(), z + 0.5))
}
