package com.inmc.invkeeper.gui

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.item.AutoBind
import com.inmc.invkeeper.item.BindMode
import com.inmc.invkeeper.item.BindStrength
import com.inmc.invkeeper.item.ItemDefinition
import com.inmc.invkeeper.item.ItemKind
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.item.StorageMode
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack

/**
 * 아이템 한 개의 설정 화면.
 *
 * 종류에 따라 **보이는 칸이 달라진다.** 도굴 도구에 "보호 시간"을 보여주면 관리자가 그 값을
 * 맞춰놓고 왜 안 되는지 묻게 된다. `items.yml` 주석 40줄로 설명하던 것을 화면이 대신한다.
 *
 * 값을 바꿀 때마다 정의를 새로 만들어 레지스트리에 갈아끼운다. [ItemDefinition] 이 불변이라
 * 반쯤 바뀐 상태가 존재하지 않는다.
 */
class ItemEditMenu(
    inv: InvKeeper,
    private val viewer: Player,
    private var definition: ItemDefinition,
) : Menu(inv, SIZE, Text.renderFlat("<dark_gray>설정 — ${definition.key}</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(SLOT_ICON, previewIcon())
        set(SLOT_KIND, kindIcon()) { event -> cycleKind(event) }
        set(SLOT_STORAGE, storageIcon()) { toggleStorage() }

        drawAutoBind()
        drawSelfBind()
        drawKindSpecific()

        set(SLOT_GIVE, giveIcon()) { event -> give(if (event.isShiftClick) 64 else 1) }
        set(SLOT_BACK, Icon.back()) { ItemListMenu(inv, viewer).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    /**
     * 이 아이템을 자기에게 준다.
     *
     * 보호권·각인기의 주된 관리 동작이 "만들어서 나눠주기"다. 설정을 고친 자리에서 바로
     * 받아볼 수 있어야 값이 맞는지 확인할 수 있다. 남에게 주는 것은 `/인벤키퍼 지급` 이다.
     */
    private fun give(amount: Int) {
        val stack = inv.items.create(definition, amount, viewer)
        if (stack == null) {
            viewer.sendMessage(
                Text.render("<red>아이템을 만들 수 없습니다 — 연동 플러그인이 꺼져 있는지 확인하세요.</red>"),
            )
            return
        }
        inv.graves.give(viewer, stack)
        viewer.sendMessage(Text.render("<green>${definition.key} ${amount}개를 받았습니다.</green>"))
    }

    private fun giveIcon(): ItemStack = Icon.of(
        Material.CHEST,
        "<green>나에게 지급</green>",
        listOf(
            "<gray>설정한 값 그대로 만들어 받아봅니다.</gray>",
            if (definition.selfBind.enabled) {
                "<gray>자체 각인이 켜져 있어 <white>받는 즉시 묶입니다</white>.</gray>"
            } else {
                "<dark_gray>자체 각인이 꺼져 있어 그냥 아이템으로 나갑니다.</dark_gray>"
            },
            "",
            "<yellow>▶ 좌클릭: 1개  /  Shift: 64개</yellow>",
            "<dark_gray>남에게 주려면 /인벤키퍼 지급</dark_gray>",
        ),
    )

    // --- 미리보기 · 기본 -------------------------------------------------------------

    private fun previewIcon(): ItemStack {
        val icon = inv.itemResolver.icon(definition.item)
        return Icon.annotate(
            icon.stack.clone(),
            "<white>${definition.key}</white>",
            listOf("<gray>이 아이템이 등록돼 있습니다.</gray>") +
                icon.notes().map { "<dark_gray>$it</dark_gray>" },
        )
    }

    private fun kindIcon(): ItemStack = Icon.of(
        Material.NAME_TAG,
        "<yellow>종류</yellow>",
        Editors.optionList(ItemKind.entries, definition.kind) { it.label } +
            listOf("", "<yellow>▶ 클릭하여 변경</yellow>"),
    )

    private fun cycleKind(event: InventoryClickEvent) {
        val next = Editors.cycle(event, ItemKind.entries, definition.kind)
        replace(copy(kind = next))
    }

    private fun storageIcon(): ItemStack {
        val snapshot = definition.item.mode == StorageMode.SNAPSHOT
        return Icon.of(
            if (snapshot) Material.ITEM_FRAME else Material.COMPASS,
            "<yellow>저장 방식: <white>${if (snapshot) "스냅샷(고정)" else "동적(참조)"}</white></yellow>",
            listOf(
                "<gray>동적: 원본 정의에서 매번 다시 만듭니다.</gray>",
                "<gray>      MMOItems 정의를 고치면 바로 반영됩니다.</gray>",
                "<gray>스냅샷: 등록 당시 모습으로 고정합니다.</gray>",
                "",
                "<dark_gray>자동각인은 둘 다 똑같이 동작합니다.</dark_gray>",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        )
    }

    private fun toggleStorage() {
        val next = definition.item.mode.toggle()
        replace(
            ItemDefinition(
                key = definition.key,
                kind = definition.kind,
                item = definition.item.withMode(next),
                protectionMinutes = definition.protectionMinutes,
                applies = definition.applies,
                castSeconds = definition.castSeconds,
                selfBind = definition.selfBind,
                autoBind = definition.autoBind,
                legacy = definition.legacy,
            ),
        )
    }

    // --- 자동각인 ------------------------------------------------------------------

    private fun drawAutoBind() {
        val auto = definition.autoBind

        set(
            SLOT_AUTO_TOGGLE,
            Icon.of(
                Icon.toggleMaterial(auto.enabled),
                "<aqua>자동각인: ${Icon.toggle(auto.enabled)}</aqua>",
                listOf(
                    "<gray>이 아이템을 <white>처음 손에 넣은 사람</white>에게</gray>",
                    "<gray>각인이 자동으로 찍힙니다.</gray>",
                    "<gray>그 뒤로 다른 사람은 줍지도 쓰지도 못합니다.</gray>",
                    "",
                    "<dark_gray>각인이 풀리면 다음에 줍는 사람이 새 주인이 됩니다.</dark_gray>",
                    "<yellow>▶ 클릭하여 전환</yellow>",
                ),
            ),
        ) { replace(copy(autoBind = auto.copy(enabled = !auto.enabled))) }

        if (!auto.enabled) return

        set(SLOT_AUTO_MODE, modeIcon("자동각인", auto.strength)) { event ->
            val next = Editors.cycle(event, BindMode.entries, auto.strength.mode)
            replace(copy(autoBind = auto.copy(strength = auto.strength.copy(mode = next))))
        }

        set(SLOT_AUTO_VALUE, valueIcon(auto.strength)) { event ->
            editStrength(auto.strength, "자동각인") { updated ->
                replace(copy(autoBind = auto.copy(strength = updated)))
            }(event)
        }
    }

    // --- 자체 각인 -----------------------------------------------------------------

    private fun drawSelfBind() {
        val self = definition.selfBind

        set(
            SLOT_SELF_TOGGLE,
            Icon.of(
                Icon.toggleMaterial(self.enabled),
                "<light_purple>자체 각인: ${Icon.toggle(self.enabled)}</light_purple>",
                listOf(
                    "<gray>지급받는 <white>그 사람</white>에게 바로 묶입니다.</gray>",
                    "<gray>보호권·각인기를 팔거나 넘기지 못하게 할 때 씁니다.</gray>",
                    "",
                    "<dark_gray>자동각인과 다릅니다 — 이쪽은 지급 시점,</dark_gray>",
                    "<dark_gray>자동각인은 누가 처음 줍는가입니다.</dark_gray>",
                    "<yellow>▶ 클릭하여 전환</yellow>",
                ),
            ),
        ) { replace(copy(selfBind = self.copy(enabled = !self.enabled))) }

        if (!self.enabled) return

        set(SLOT_SELF_MODE, modeIcon("자체 각인", self.strength)) { event ->
            val next = Editors.cycle(event, BindMode.entries, self.strength.mode)
            replace(copy(selfBind = self.copy(strength = self.strength.copy(mode = next))))
        }

        set(SLOT_SELF_VALUE, valueIcon(self.strength)) { event ->
            editStrength(self.strength, "자체 각인") { updated ->
                replace(copy(selfBind = self.copy(strength = updated)))
            }(event)
        }
    }

    // --- 종류별 --------------------------------------------------------------------

    private fun drawKindSpecific() {
        when (definition.kind) {
            ItemKind.TIMED_PROTECTION -> set(
                SLOT_EXTRA_A,
                Editors.intIcon(
                    Material.CLOCK,
                    "<green>보호 시간</green>",
                    definition.protectionMinutes,
                    unit = "분",
                    extra = listOf("<gray>우클릭으로 쓰면 이만큼 사망 보호를 받습니다.</gray>"),
                ),
            ) { event -> nudgeInt(event, definition.protectionMinutes, 1, 1440, "보호 시간") { replace(copy(protectionMinutes = it)) } }

            ItemKind.GRAVE_LOOT_TOOL -> set(
                SLOT_EXTRA_A,
                Editors.intIcon(
                    Material.CLOCK,
                    "<green>도굴 시전 시간</green>",
                    definition.castSeconds,
                    unit = "초",
                    extra = listOf("<gray>이 시간이 지나야 남의 무덤이 열립니다.</gray>", "<gray>그 사이 주인이 확인하면 취소됩니다.</gray>"),
                ),
            ) { event -> nudgeInt(event, definition.castSeconds, 1, 86400, "시전 시간") { replace(copy(castSeconds = it)) } }

            ItemKind.SOULBIND_TOOL_TIME, ItemKind.SOULBIND_TOOL_STACK -> {
                set(SLOT_EXTRA_A, appliesIcon()) { event ->
                    editStrength(definition.applies, "찍는 각인") { replace(copy(applies = it)) }(event)
                }
            }

            else -> Unit
        }
    }

    private fun appliesIcon(): ItemStack {
        val applies = definition.applies
        val expected = if (definition.kind == ItemKind.SOULBIND_TOOL_TIME) BindMode.TIME else BindMode.STACK
        val warning = if (applies.mode != expected) {
            listOf("", "<red>⚠ 종류와 모드가 어긋납니다. 모드를 ${expected.name} 으로 두세요.</red>")
        } else {
            emptyList()
        }
        return Icon.of(
            Material.AMETHYST_SHARD,
            "<yellow>대상에 찍는 각인</yellow>",
            listOf("<gray>현재: <white>${describe(applies)}</white></gray>") + warning + Editors.nudgeHint("1"),
        )
    }

    // --- 공통 조각 -----------------------------------------------------------------

    private fun modeIcon(label: String, strength: BindStrength): ItemStack = Icon.of(
        if (strength.mode == BindMode.TIME) Material.CLOCK else Material.SKELETON_SKULL,
        "<yellow>$label 방식</yellow>",
        Editors.optionList(BindMode.entries, strength.mode) {
            if (it == BindMode.TIME) "시간 (분 단위로 만료)" else "횟수 (사망 시 소모)"
        } + listOf("", "<yellow>▶ 클릭하여 변경</yellow>"),
    )

    private fun valueIcon(strength: BindStrength): ItemStack = when (strength.mode) {
        BindMode.TIME -> Editors.intIcon(
            Material.CLOCK,
            "<yellow>지속 시간</yellow>",
            strength.durationMinutes,
            unit = "분",
            extra = listOf("<gray>0 이하면 <white>영구</white> 각인입니다.</gray>"),
        )

        BindMode.STACK -> Editors.intIcon(
            Material.SKELETON_SKULL,
            "<yellow>버티는 횟수</yellow>",
            strength.stacks,
            unit = "회",
            extra = listOf("<gray>-1 이면 <white>무한</white>입니다.</gray>"),
        )
    }

    private fun describe(strength: BindStrength): String = when {
        strength.mode == BindMode.TIME && strength.isPermanent -> "영구"
        strength.mode == BindMode.TIME -> "${strength.durationMinutes}분"
        strength.stacks < 0 -> "사망 무한회"
        else -> "사망 ${strength.stacks}회"
    }

    /** 시간이면 분을, 횟수면 스택을 고친다. 어느 쪽인지는 [BindStrength.mode] 가 정한다. */
    private fun editStrength(
        strength: BindStrength,
        label: String,
        apply: (BindStrength) -> Unit,
    ): (InventoryClickEvent) -> Unit = { event ->
        if (strength.mode == BindMode.TIME) {
            nudgeInt(event, strength.durationMinutes, 0, 525_600, "$label 지속 시간(분)") {
                apply(strength.copy(durationMinutes = it))
            }
        } else {
            nudgeInt(event, strength.stacks, -1, 999, "$label 횟수") {
                apply(strength.copy(stacks = it))
            }
        }
    }

    /** 클릭으로 밀거나 숫자키로 직접 입력한다. core 의 편집 규칙을 그대로 따른다. */
    private fun nudgeInt(
        event: InventoryClickEvent,
        current: Int,
        min: Int,
        max: Int,
        label: String,
        apply: (Int) -> Unit,
    ) {
        if (Editors.isPrompt(event)) {
            Editors.promptInt(
                inv.prompts,
                viewer,
                label,
                min,
                max,
                reopen = { ItemEditMenu(inv, viewer, inv.items.get(definition.key) ?: definition).open(viewer) },
                onValue = apply,
            )
            return
        }
        apply((current + Editors.step(event, 1)).coerceIn(min, max))
    }

    /** 바뀐 정의로 갈아끼우고 화면을 다시 그린다. */
    private fun replace(updated: ItemDefinition) {
        definition = updated
        inv.items.replace(updated)
        refresh()
    }

    private fun copy(
        kind: ItemKind = definition.kind,
        protectionMinutes: Int = definition.protectionMinutes,
        applies: BindStrength = definition.applies,
        castSeconds: Int = definition.castSeconds,
        selfBind: AutoBind = definition.selfBind,
        autoBind: AutoBind = definition.autoBind,
    ) = ItemDefinition(
        key = definition.key,
        kind = kind,
        item = definition.item,
        protectionMinutes = protectionMinutes,
        applies = applies,
        castSeconds = castSeconds,
        selfBind = selfBind,
        autoBind = autoBind,
        legacy = definition.legacy,
    )

    private companion object {
        const val SIZE = 54

        const val SLOT_ICON = 4
        const val SLOT_KIND = 19
        const val SLOT_STORAGE = 20

        const val SLOT_AUTO_TOGGLE = 22
        const val SLOT_AUTO_MODE = 23
        const val SLOT_AUTO_VALUE = 24

        const val SLOT_SELF_TOGGLE = 31
        const val SLOT_SELF_MODE = 32
        const val SLOT_SELF_VALUE = 33

        const val SLOT_EXTRA_A = 29

        const val SLOT_GIVE = 49
        const val SLOT_BACK = 45
        const val SLOT_CLOSE = 53
    }
}
