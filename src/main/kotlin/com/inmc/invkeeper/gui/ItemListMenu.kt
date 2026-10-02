package com.inmc.invkeeper.gui

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.item.ItemDefinition
import kr.inmc.core.store.DefinitionKey
import com.inmc.invkeeper.item.ItemKind
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 등록된 아이템 목록. 관리 화면의 첫 장이다.
 *
 * 자동각인을 여기서 켜고 끈다 — `items.yml` 을 손으로 고치지 않아도 되게 하는 것이 이 화면의
 * 목적이다. 새 아이템은 **손에 들고 아래 버튼을 누르면** 그대로 등록된다.
 */
class ItemListMenu(
    inv: InvKeeper,
    private val viewer: Player,
    private var page: Int = 0,
    /** null 이면 전체. 보호권만·각인기만 보고 싶을 때 좁힌다. */
    private var filter: ItemKind? = null,
) : Menu(inv, SIZE, Text.renderFlat("<dark_gray>인벤키퍼 — 등록 아이템</dark_gray>")) {

    override fun draw() {
        clear()

        val shown = filter?.let { kind -> inv.items.ofKind(kind) } ?: inv.items.all()
        page = Paging.clamp(page, shown.size)

        for ((index, definition) in Paging.slice(shown, page).withIndex()) {
            set(index, tile(definition)) { event ->
                if (event.isRightClick) confirmDelete(definition) else ItemEditMenu(inv, viewer, definition).open(viewer)
            }
        }

        set(Paging.SLOT_BACK, registerButton()) { promptRegister() }

        if (page > 0) {
            set(Paging.SLOT_PREV, Icon.prevPage()) {
                page--
                refresh()
            }
        }
        if (page < Paging.pageCount(shown.size) - 1) {
            set(Paging.SLOT_NEXT, Icon.nextPage()) {
                page++
                refresh()
            }
        }

        set(SLOT_FILTER, filterButton(shown.size)) { event ->
            // null(전체) 을 목록 맨 앞에 끼워 돌린다.
            val options = listOf<ItemKind?>(null) + ItemKind.entries
            filter = Editors.cycle(event, options, filter)
            page = 0
            refresh()
        }
        set(SLOT_INFO, infoButton(shown.size))
        set(SLOT_GRAVE_BLOCK, graveBlockButton()) { event ->
            if (event.isRightClick) {
                inv.graves.setBlock(null)
                refresh()
                return@set
            }
            val hand = viewer.inventory.itemInMainHand
            val ref = if (hand.type.isAir) null else kr.inmc.core.item.BlockRef.fromItem(hand, inv.customItems)
            if (ref == null) {
                viewer.sendMessage(Text.render("<red>놓을 수 있는 블록을 손에 든 뒤 누르세요.</red>"))
                return@set
            }
            inv.graves.setBlock(ref)
            refresh()
        }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    /** 무덤 블록 — 지금 것의 모양으로. 커스텀 블록(커스텀아이템)도 된다. */
    private fun graveBlockButton(): ItemStack {
        val ref = inv.graves.graveBlockRef()
        val icon = when (ref) {
            is kr.inmc.core.item.BlockRef.Vanilla -> ItemStack(ref.material)
            is kr.inmc.core.item.BlockRef.Custom -> inv.customItems.create(kr.inmc.core.item.ItemRef.Namespaced(ref.namespace, ref.id)) ?: ItemStack(ref.fallback)
        }
        return Icon.annotate(icon, "<yellow>무덤 블록</yellow>", listOf(
            "<gray>지금: <white>${ref.serialize()}</white></gray>",
            "<dark_gray>죽으면 이 블록이 무덤이 됩니다. 커스텀 블록(커스텀아이템)도 됩니다.</dark_gray>",
            "<dark_gray>이미 있는 무덤은 놓인 블록 그대로 — 사라질 때 그 블록을 치웁니다.</dark_gray>",
            "",
            "<yellow>▶ 좌클릭: 손에 든 블록으로</yellow>",
            "<red>▶ 우클릭: 기본값(통)으로</red>",
        ))
    }

    private fun filterButton(shown: Int): ItemStack = Icon.of(
        Material.HOPPER,
        "<yellow>종류 필터: <white>${filter?.label ?: "전체"}</white></yellow>",
        Editors.optionList(listOf<ItemKind?>(null) + ItemKind.entries, filter) { it?.label ?: "전체" } +
            listOf("", "<gray>보이는 항목 <white>${shown}</white>개</gray>") + Editors.cycleHint,
    )

    /** 한 칸. 아이콘은 등록된 아이템 그대로라 관리자가 눈으로 알아본다. */
    private fun tile(definition: ItemDefinition): ItemStack {
        val icon = inv.itemResolver.icon(definition.item)
        val auto = definition.autoBind
        val lore = buildList {
            add("<gray>종류: <white>${definition.kind.label}</white></gray>")
            add("<gray>저장: <white>${modeLabel(definition)}</white></gray>")
            add("")
            add("<gray>자동각인: ${Icon.toggle(auto.enabled)}</gray>")
            if (auto.enabled) add("<gray>  ${strengthLabel(definition)}</gray>")
            addAll(icon.notes().map { "<dark_gray>$it</dark_gray>" })
            add("")
            add("<yellow>▶ 좌클릭: 설정</yellow>")
            add("<red>▶ 우클릭: 등록 해제</red>")
        }
        return Icon.annotate(icon.stack.clone(), "<white>${definition.key}</white>", lore)
    }

    private fun modeLabel(definition: ItemDefinition): String =
        if (definition.item.mode == kr.inmc.core.item.StorageMode.SNAPSHOT) "스냅샷(고정)" else "동적(참조)"

    private fun strengthLabel(definition: ItemDefinition): String {
        val strength = definition.autoBind.strength
        return when {
            strength.isPermanent && strength.mode == com.inmc.invkeeper.item.BindMode.TIME -> "영구"
            strength.mode == com.inmc.invkeeper.item.BindMode.TIME -> "${strength.durationMinutes}분"
            strength.stacks < 0 -> "사망 무한회"
            else -> "사망 ${strength.stacks}회"
        }
    }

    private fun registerButton(): ItemStack {
        val hand = viewer.inventory.itemInMainHand
        if (hand.type.isAir) {
            return Icon.of(
                Material.BARRIER,
                "<red>등록하려면 아이템을 손에 드세요</red>",
                listOf("<gray>등록할 아이템을 주 손에 들고</gray>", "<gray>이 버튼을 누르세요.</gray>"),
            )
        }
        return Icon.of(
            Material.WRITABLE_BOOK,
            "<green>손에 든 아이템 등록</green>",
            listOf(
                "<gray>대상: <white>${kr.inmc.core.item.StoredItem.plainName(hand) ?: hand.type.name}</white></gray>",
                "",
                "<yellow>▶ 클릭하면 이름을 물어봅니다</yellow>",
            ),
        )
    }

    private fun infoButton(total: Int): ItemStack = Icon.of(
        Material.BOOK,
        "<aqua>자동각인이란</aqua>",
        listOf(
            "<gray>등록된 아이템을 <white>처음 손에 넣은 사람</white>에게</gray>",
            "<gray>각인이 자동으로 찍힙니다. 그 뒤로는 다른 사람이</gray>",
            "<gray>줍지도, 쓰지도, 옮기지도 못합니다.</gray>",
            "",
            "<gray>시간(분) 또는 사망 횟수 중 하나로 설정하며,</gray>",
            "<gray>아이템마다 값을 따로 줄 수 있습니다.</gray>",
            "",
            "<dark_gray>등록 ${total}개 · ${page + 1}/${Paging.pageCount(total)} 쪽</dark_gray>",
        ),
    )

    /** 이름을 물어보고 등록한다. 이름은 설정 파일의 항목 이름이 되므로 규칙을 검사한다. */
    private fun promptRegister() {
        val hand = viewer.inventory.itemInMainHand.clone()
        if (hand.type.isAir) return

        Editors.promptText(
            inv.prompts,
            viewer,
            "등록 이름",
            listOf(
                "<gray>소문자 영문·숫자·한글·_- 만, 최대 32자.</gray>",
                "<gray>예: <white>보스무기</white>, <white>season_cape</white></gray>",
            ),
            reopen = { ItemListMenu(inv, viewer, page, filter).open(viewer) },
        ) { raw ->
            val key = raw.trim().lowercase()
            if (!DefinitionKey.isValid(key)) {
                viewer.sendMessage(Text.render("<red>이름 규칙에 맞지 않습니다: $raw</red>"))
                return@promptText
            }
            val definition = inv.items.register(key, ItemKind.PLAIN, hand)
            viewer.sendMessage(Text.render("<green>'$key' 로 등록했습니다. 이어서 설정을 여세요.</green>"))
            ItemEditMenu(inv, viewer, definition).open(viewer)
        }
    }

    private fun confirmDelete(definition: ItemDefinition) {
        kr.inmc.core.gui.ConfirmMenu(
            owner = inv,
            question = "<red>'${definition.key}' 등록을 해제할까요?</red>",
            detail = listOf(
                "<gray>이미 찍힌 각인은 그대로 남습니다.</gray>",
                "<gray>앞으로 이 아이템이 자동각인 대상에서 빠집니다.</gray>",
            ),
            onConfirm = {
                inv.items.unregister(definition.key)
                ItemListMenu(inv, viewer, page, filter).open(viewer)
            },
            onCancel = { ItemListMenu(inv, viewer, page, filter).open(viewer) },
        ).open(viewer)
    }

    private companion object {
        const val SIZE = 54
        const val SLOT_FILTER = 48
        const val SLOT_INFO = 49
        const val SLOT_GRAVE_BLOCK = 50
    }
}
