package com.inmc.invkeeper.item

import kr.inmc.core.integration.ItemRoles
import org.bukkit.Material

/**
 * 인벤키퍼가 커스텀아이템에 내놓는 역할(core [ItemRoles]). 커스텀아이템이 있으면 인벤키퍼의 아이템은 여기서 온다 —
 * 아이템 설정 화면의 "연동 역할"에서 붙이고, 인벤키퍼 화면에서 등록해도 같은 곳에 적힌다.
 *
 * 역할은 넷으로 나눈다(보호권 · 각인 도구 · 도굴 도구 · 각인만). 하나로 두면 보호권에 "시전 시간" 칸이 보이고, 새 아이템에
 * 권할 종류(보호권)도 정할 수 없다.
 */
object InvKeeperRoles {

    // 권한 노드가 아니다 — `const` 로 두면 권한 검사(PermissionParityTest)가 권한으로 읽는다.
    val PROTECTION = "invkeeper.protection"
    val SOULBIND = "invkeeper.soulbind"
    val GRAVE = "invkeeper.grave"
    val BIND = "invkeeper.bind"

    const val OWNER = "인벤키퍼"

    val ALL = listOf(PROTECTION, SOULBIND, GRAVE, BIND)

    fun roleOf(kind: ItemKind): String = when (kind) {
        ItemKind.CONSUMABLE_PROTECTION, ItemKind.TIMED_PROTECTION -> PROTECTION
        ItemKind.SOULBIND_TOOL_TIME, ItemKind.SOULBIND_TOOL_STACK, ItemKind.SOULBIND_UNBIND_TOOL -> SOULBIND
        ItemKind.GRAVE_LOOT_TOOL -> GRAVE
        ItemKind.PLAIN -> BIND
    }

    private fun kinds(role: String): List<ItemKind> = ItemKind.entries.filter { roleOf(it) == role }

    private fun modes() = BindMode.entries.map { it.name to if (it == BindMode.TIME) "시간" else "사망 횟수" }

    /** 각인 한 번의 세기 칸 셋 — [prefix] 로 이름을 가른다(찍는 각인 · 자체 각인 · 자동각인). */
    private fun strength(prefix: String, label: String, visible: (Map<String, String>) -> Boolean): List<ItemRoles.Field> = listOf(
        ItemRoles.Choice("$prefix-mode", "$label 방식", ::modes, BindMode.TIME.name, visible),
        ItemRoles.Number("$prefix-minutes", "$label 시간(분 · 0 영구)", 0.0, 525600.0, 60.0, "0",
            visible = { visible(it) && it["$prefix-mode"] != BindMode.STACK.name }),
        ItemRoles.Number("$prefix-stacks", "$label 사망 횟수(-1 무한)", -1.0, 1000.0, 1.0, "1",
            visible = { visible(it) && it["$prefix-mode"] == BindMode.STACK.name }),
    )

    /** 모든 역할에 붙는 것 — 받는 사람에게 각인(자체) · 처음 주운 사람에게 자동각인. */
    private val binding: List<ItemRoles.Field> =
        listOf(ItemRoles.Toggle("self-bind", "받는 사람에게 각인")) + strength("self", "자체 각인") { it["self-bind"].toBoolean() } +
            listOf(ItemRoles.Toggle("auto-bind", "처음 주운 사람에게 자동각인")) + strength("auto", "자동각인") { it["auto-bind"].toBoolean() }

    fun roles(): List<ItemRoles.Role> = listOf(
        ItemRoles.Role(
            PROTECTION, OWNER, "인벤 보호권", Material.TOTEM_OF_UNDYING,
            listOf("죽을 때 가방을 지켜 줍니다.", "소모형: 들고만 있으면 한 장 소모 · 시간형: 우클릭하면 정한 시간 동안"),
            listOf(
                ItemRoles.Choice("kind", "보호권 종류", { kinds(PROTECTION).map { it.name to it.label } }, ItemKind.CONSUMABLE_PROTECTION.name),
                ItemRoles.Number("protection-minutes", "보호 시간(분)", 1.0, 10080.0, 5.0, "30",
                    visible = { it["kind"] == ItemKind.TIMED_PROTECTION.name }),
            ) + binding,
        ),
        ItemRoles.Role(
            SOULBIND, OWNER, "각인 도구", Material.NAME_TAG,
            listOf("다른 아이템에 끌어다 놓아 각인을 찍거나 지웁니다."),
            listOf(ItemRoles.Choice("kind", "도구 종류", { kinds(SOULBIND).map { it.name to it.label } }, ItemKind.SOULBIND_TOOL_TIME.name)) +
                strength("applies", "찍는 각인") { it["kind"] != ItemKind.SOULBIND_UNBIND_TOOL.name } + binding,
        ),
        ItemRoles.Role(
            GRAVE, OWNER, "도굴 도구", Material.GOLDEN_SHOVEL,
            listOf("남의 무덤을 시전해서 엽니다."),
            listOf(ItemRoles.Number("cast-seconds", "시전 시간(초)", 1.0, 3600.0, 5.0, "300")) + binding,
        ),
        ItemRoles.Role(
            BIND, OWNER, "각인만", Material.IRON_CHAIN,
            listOf("다른 역할 없이 자동각인·자체 각인만 겁니다(보스 드랍 무기 등)."),
            binding,
        ),
    )

    private fun strengthOf(values: Map<String, String>, prefix: String) = BindStrength(
        BindMode.parse(values["$prefix-mode"]),
        values["$prefix-minutes"]?.toDoubleOrNull()?.toInt() ?: 0,
        values["$prefix-stacks"]?.toDoubleOrNull()?.toInt() ?: 1,
    )

    private fun strengthValues(prefix: String, strength: BindStrength) = mapOf(
        "$prefix-mode" to strength.mode.name,
        "$prefix-minutes" to strength.durationMinutes.toString(),
        "$prefix-stacks" to strength.stacks.toString(),
    )

    /** 역할 값 → 인벤키퍼의 정의. 키는 커스텀아이템 id, 아이템은 그 참조(옛 아이템이 있으면 같이 알아본다). */
    fun definition(role: String, holder: ItemRoles.Holder): ItemDefinition? {
        val values = holder.values
        val kind = when (role) {
            PROTECTION, SOULBIND -> ItemKind.parse(values["kind"])?.takeIf { roleOf(it) == role } ?: kinds(role).first()
            GRAVE -> ItemKind.GRAVE_LOOT_TOOL
            BIND -> ItemKind.PLAIN
            else -> return null
        }
        return ItemDefinition(
            key = holder.ref.id,
            kind = kind,
            item = holder.item(),
            protectionMinutes = values["protection-minutes"]?.toDoubleOrNull()?.toInt() ?: 30,
            applies = strengthOf(values, "applies"),
            castSeconds = values["cast-seconds"]?.toDoubleOrNull()?.toInt() ?: 300,
            selfBind = AutoBind(values["self-bind"].toBoolean(), strengthOf(values, "self")),
            autoBind = AutoBind(values["auto-bind"].toBoolean(), strengthOf(values, "auto")),
            legacy = holder.legacy(),
        )
    }

    /** 인벤키퍼의 정의 → 역할 값(옮길 때 · 인벤키퍼 화면에서 고칠 때). */
    fun values(definition: ItemDefinition): Map<String, String> = buildMap {
        put("kind", definition.kind.name)
        put("protection-minutes", definition.protectionMinutes.toString())
        putAll(strengthValues("applies", definition.applies))
        put("cast-seconds", definition.castSeconds.toString())
        put("self-bind", definition.selfBind.enabled.toString())
        putAll(strengthValues("self", definition.selfBind.strength))
        put("auto-bind", definition.autoBind.enabled.toString())
        putAll(strengthValues("auto", definition.autoBind.strength))
    }
}
