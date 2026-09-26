package com.inmc.invkeeper.grave

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.gui.Menu
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 한 사람의 무덤 기록.
 *
 * 무덤이 어떻게 사라졌든(전량 회수·도굴·만료) 사망 시점 원본이 남는다. 관리자는 여기서
 * "이 사람이 무엇을 잃었는지"를 보고, 만료로 사라진 무덤의 **남은 아이템을 돌려줄 수 있다.**
 * 잃은 것을 되돌려달라는 문의가 왔을 때 근거 없이 짐작하지 않아도 된다.
 */
class GraveHistoryMenu(
    inv: InvKeeper,
    private val viewer: Player,
    private val ownerId: UUID,
    private val ownerName: String,
    private var page: Int = 0,
) : Menu(inv, SIZE, Text.render("<dark_gray>$ownerName 의 무덤 기록")) {

    /** 한 칸 — 살아 있는 무덤이거나 사라진 무덤의 기록. 1세대처럼 둘을 한 목록에 둔다. */
    private sealed interface Row {
        class Live(val grave: Grave) : Row
        class Past(val record: GraveRecord) : Row
    }

    override fun draw() {
        clear()

        val now = System.currentTimeMillis()
        val rows: List<Row> = inv.graves.store.all().filter { it.ownerId == ownerId }.map { Row.Live(it) } +
            inv.graves.history.of(ownerId).map { Row.Past(it) }
        page = Paging.clamp(page, rows.size)

        for ((index, row) in Paging.slice(rows, page).withIndex()) {
            when (row) {
                is Row.Live -> set(index, liveTile(row.grave, now)) {
                    teleport(viewer, row.grave.worldName, row.grave.x, row.grave.y, row.grave.z)
                }
                // 1세대: 좌클릭 텔레포트, 우클릭 내용 보기(여기서는 남은 아이템 회수).
                is Row.Past -> set(index, tile(row.record)) { event ->
                    if (event.isRightClick) {
                        openLeftover(row.record)
                    } else {
                        teleport(viewer, row.record.worldName, row.record.x, row.record.y + 1, row.record.z)
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            set(
                SLOT_EMPTY,
                Icon.of(
                    Material.BARRIER,
                    "<gray>기록이 없습니다</gray>",
                    listOf("<dark_gray>$ownerName 의 무덤이 사라진 적이 없습니다.</dark_gray>"),
                ),
            )
        }

        if (page > 0) {
            set(Paging.SLOT_PREV, Icon.prevPage()) {
                page--
                refresh()
            }
        }
        if (page < Paging.pageCount(rows.size) - 1) {
            set(Paging.SLOT_NEXT, Icon.nextPage()) {
                page++
                refresh()
            }
        }

        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    /**
     * 기록 한 칸.
     *
     * 아이콘은 **회수 상태로 고른다** — 미회수(남은 것이 있음)만 눈에 띄어야 관리자가 돌려줄
     * 대상을 바로 찾는다.
     */
    private fun tile(record: GraveRecord): ItemStack {
        val leftover = record.leftover.countSlots()
        val material = when {
            leftover > 0 || record.leftover.exp > 0 -> Material.CHEST
            record.recoveredBy == RecoveryType.LOOTER -> Material.SKELETON_SKULL
            else -> Material.BARREL
        }
        return Icon.of(
            material,
            "<white>${format(record.endedAt)}</white>",
            listOf(
                "<gray>위치: <white>${record.worldName} ${record.x}, ${record.y}, ${record.z}</white></gray>",
                "<gray>사망 시점 아이템: <white>${record.original.countSlots()}칸</white>" +
                    " · 경험치 <white>${record.original.exp}</white></gray>",
                "<gray>유지 시간: <white>${Durations.formatShort((record.endedAt - record.createdAt) / 1000L)}</white></gray>",
                "",
                "<gray>상태: ${statusLabel(record)}</gray>",
                if (leftover > 0 || record.leftover.exp > 0) {
                    "<yellow>▶ 우클릭: 남은 <white>${leftover}칸</white> 회수</yellow>"
                } else {
                    "<dark_gray>남은 아이템이 없습니다.</dark_gray>"
                },
                "<green>▶ 좌클릭: 텔레포트</green>",
            ),
        )
    }

    /** 아직 살아 있는 무덤. */
    private fun liveTile(grave: Grave, now: Long): ItemStack = Icon.of(
        Material.GREEN_STAINED_GLASS_PANE,
        "<green>살아 있는 무덤</green>",
        listOf(
            "<gray>위치: <white>${grave.worldName} ${grave.x}, ${grave.y}, ${grave.z}</white></gray>",
            "<gray>남은 시간: <white>" +
                (if (grave.hasExpiry) Durations.formatShort(grave.remainingMillis(now) / 1000L) else "무제한") +
                "</white></gray>",
            "<gray>아이템: <white>${grave.contents.countSlots()}칸</white> · 경험치 <white>${grave.contents.exp}</white></gray>",
            "",
            "<green>▶ 클릭: 텔레포트</green>",
        ),
    )

    private fun statusLabel(record: GraveRecord): String = when (record.recoveredBy) {
        RecoveryType.OWNER -> "<green>주인이 전량 회수</green>"
        RecoveryType.LOOTER -> "<red>${record.looterName ?: "?"} 이(가) 도굴</red>"
        RecoveryType.NONE -> "<yellow>미회수 (만료 또는 정리)</yellow>"
    }

    /** 남은 아이템을 여는 화면. 비우면 기록의 남은 칸만 지우고 기록 자체는 남는다. */
    private fun openLeftover(record: GraveRecord) {
        if (record.leftover.isEmpty()) {
            inv.messages.send(viewer, "grave-history-no-items")
            return
        }
        GraveMenu(
            inv = inv,
            ownerName = record.ownerName,
            contents = record.leftover,
            onEmptied = { player ->
                inv.graves.history.clearLeftover(ownerId, record.id)
                inv.messages.send(player, "grave-history-emptied")
            },
            onChanged = { inv.graves.history.markChanged(ownerId) },
        ).open(viewer)
    }

    private fun format(at: Long): String {
        val zone = runCatching { ZoneId.of(inv.config.timezone) }.getOrDefault(ZoneId.systemDefault())
        return STAMP.withZone(zone).format(Instant.ofEpochMilli(at))
    }

    private companion object {
        const val SIZE = 54
        const val SLOT_EMPTY = 22
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}
