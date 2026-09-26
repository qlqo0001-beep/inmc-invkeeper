package com.inmc.invkeeper.item

import kr.inmc.core.item.StoredItem
import org.bukkit.configuration.ConfigurationSection

/**
 * 등록된 아이템이 하는 일.
 *
 * 1세대의 `ProtectionItemConfig.Kind` 를 그대로 옮기고 [PLAIN] 하나를 더했다. 자동각인만
 * 걸고 싶은 아이템 — 예를 들어 보스 드랍 무기 — 은 보호권도 각인 도구도 아니기 때문이다.
 */
enum class ItemKind(val label: String) {

    /** 아무 역할 없음. 자동각인만 걸고 싶을 때 쓴다. */
    PLAIN("일반"),

    /** 소지만 하고 있으면 사망 시 자동 소모되어 완전 보호. */
    CONSUMABLE_PROTECTION("소모형 보호권"),

    /** 우클릭하면 일정 시간 보호. */
    TIMED_PROTECTION("시간형 보호권"),

    /** 다른 아이템에 시간형 각인을 찍는 도구. */
    SOULBIND_TOOL_TIME("시간형 각인기"),

    /** 다른 아이템에 스택형 각인을 찍는 도구. */
    SOULBIND_TOOL_STACK("스택형 각인기"),

    /** 각인을 지우는 도구. */
    SOULBIND_UNBIND_TOOL("각인 해제기"),

    /** 남의 무덤을 시전해서 여는 도구. */
    GRAVE_LOOT_TOOL("도굴 도구");

    val isProtection: Boolean
        get() = this == CONSUMABLE_PROTECTION || this == TIMED_PROTECTION

    val isSoulbindTool: Boolean
        get() = this == SOULBIND_TOOL_TIME || this == SOULBIND_TOOL_STACK || this == SOULBIND_UNBIND_TOOL

    companion object {
        fun parse(raw: String?): ItemKind? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** 각인을 무엇으로 세는가. */
enum class BindMode {
    /** 시각으로 만료. */
    TIME,

    /** 사망 횟수로 소모. */
    STACK;

    companion object {
        fun parse(raw: String?): BindMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: TIME
    }
}

/**
 * 각인 한 번의 세기. [mode] 에 따라 [durationMinutes] 또는 [stacks] 중 하나만 의미가 있다.
 *
 * 1세대는 이 두 값을 각각 다른 필드 이름으로 네 군데(`duration-minutes`·`soulbind-duration`·
 * `soulbind-stacks`·`stacks`)에 흩어놓아서, 어떤 값이 무엇에 쓰이는지 items.yml 주석 40줄로
 * 설명해야 했다. 한 덩어리로 묶으면 그 설명이 필요 없다.
 */
data class BindStrength(
    val mode: BindMode,
    /** [BindMode.TIME] 전용. 0 이하면 영구. */
    val durationMinutes: Int,
    /** [BindMode.STACK] 전용. -1 이면 무한. */
    val stacks: Int,
) {

    val isPermanent: Boolean
        get() = when (mode) {
            BindMode.TIME -> durationMinutes <= 0
            BindMode.STACK -> stacks < 0
        }

    /** 지금 찍으면 언제 만료되는지. 영구면 -1. [BindMode.STACK] 에는 의미가 없다. */
    fun expiryAt(now: Long): Long =
        if (isPermanent) INFINITE else now + durationMinutes * 60_000L

    fun save(section: ConfigurationSection) {
        section.set("mode", mode.name)
        section.set("duration-minutes", durationMinutes)
        section.set("stacks", stacks)
    }

    companion object {

        /** 만료 없음을 뜻하는 값. PDC 에도 이 값이 그대로 들어간다. */
        const val INFINITE = -1L

        val PERMANENT_TIME = BindStrength(BindMode.TIME, 0, 1)

        fun load(section: ConfigurationSection?): BindStrength {
            if (section == null) return PERMANENT_TIME
            return BindStrength(
                mode = BindMode.parse(section.getString("mode")),
                durationMinutes = section.getInt("duration-minutes", 0),
                stacks = section.getInt("stacks", 1),
            )
        }
    }
}

/**
 * 아이템을 처음 손에 넣은 사람에게 자동으로 각인을 찍는 설정.
 *
 * "처음"의 판정은 따로 기록을 두지 않는다 — **각인 소유자가 아직 없으면 처음**이다. 각인
 * 자체가 그 기록이라 별도 저장이 필요 없고, 그래서 이 판정은 자연히 멱등하다. 각인이 만료돼
 * 소유자가 지워지면 다음에 줍는 사람이 새 주인이 되는데, 그게 의도된 동작이다.
 */
data class AutoBind(val enabled: Boolean, val strength: BindStrength) {

    fun save(section: ConfigurationSection) {
        section.set("enabled", enabled)
        strength.save(section)
    }

    companion object {
        val OFF = AutoBind(false, BindStrength.PERMANENT_TIME)

        fun load(section: ConfigurationSection?): AutoBind {
            if (section == null) return OFF
            return AutoBind(
                enabled = section.getBoolean("enabled", false),
                strength = BindStrength.load(section),
            )
        }
    }
}

/**
 * `items.yml` 의 항목 하나.
 *
 * 1세대는 아이템을 `use-type: vanilla | mmoitems` 로 갈라 `vanilla-material`·`vanilla-name`·
 * `vanilla-lore`·`custom-model-data` 또는 `mmoitems-type`·`mmoitems-id` 를 손으로 적게 했다.
 * 그래서 ItemsAdder 아이템이나 손으로 만든 아이템은 아예 등록할 수 없었다.
 *
 * 여기서는 core 의 [StoredItem] 을 그대로 쓴다 — urb 가 쓰는 것과 같은 것이라, 관리자가
 * **손에 든 아이템을 그대로 등록**할 수 있고 MMOItems·ItemsAdder·바닐라·수제 아이템이
 * 전부 같은 길로 들어온다.
 */
class ItemDefinition(
    val key: String,
    val kind: ItemKind,
    val item: StoredItem,
    /** [ItemKind.TIMED_PROTECTION] 이 켜 주는 보호 시간(분). */
    val protectionMinutes: Int,
    /** 각인 도구가 **대상 아이템에** 찍을 각인의 세기. */
    val applies: BindStrength,
    /** [ItemKind.GRAVE_LOOT_TOOL] 의 시전 시간(초). */
    val castSeconds: Int,
    /** 이 아이템 **자체**에 걸리는 각인 (1세대의 `soulbind:` 블록). */
    val selfBind: AutoBind,
    /** 이 아이템을 처음 획득한 사람에게 자동으로 찍히는 각인. */
    val autoBind: AutoBind,
    /**
     * 커스텀아이템으로 옮기기 전의 아이템(옮긴 경우만). 이미 플레이어가 들고 있는 옛 보호권도 계속 알아보게 [item] 과 함께 본다.
     * 파일에는 안 적는다 — 커스텀아이템 역할 값에 들어 있다(core `ItemRoles.LEGACY`).
     */
    val legacy: StoredItem? = null,
) {

    fun label(): String = item.label()

    fun save(section: ConfigurationSection) {
        section.set("kind", kind.name)
        item.save(section)
        if (kind == ItemKind.TIMED_PROTECTION) section.set("protection-minutes", protectionMinutes)
        if (kind == ItemKind.SOULBIND_TOOL_TIME || kind == ItemKind.SOULBIND_TOOL_STACK) {
            applies.save(section.createSection("applies"))
        }
        if (kind == ItemKind.GRAVE_LOOT_TOOL) section.set("cast-seconds", castSeconds)
        selfBind.save(section.createSection("self-bind"))
        autoBind.save(section.createSection("auto-bind"))
    }

    companion object {

        fun load(key: String, section: ConfigurationSection): ItemDefinition? {
            val kind = ItemKind.parse(section.getString("kind")) ?: return null
            val item = StoredItem.load(section) ?: return null
            return ItemDefinition(
                key = key,
                kind = kind,
                item = item,
                protectionMinutes = section.getInt("protection-minutes", 30),
                applies = BindStrength.load(section.getConfigurationSection("applies")),
                castSeconds = section.getInt("cast-seconds", 300),
                selfBind = AutoBind.load(section.getConfigurationSection("self-bind")),
                autoBind = AutoBind.load(section.getConfigurationSection("auto-bind")),
            )
        }

        /** 손에 든 아이템을 그대로 등록할 때의 기본값. */
        fun of(key: String, kind: ItemKind, item: StoredItem) = ItemDefinition(
            key = key,
            kind = kind,
            item = item,
            protectionMinutes = 30,
            applies = BindStrength.PERMANENT_TIME,
            castSeconds = 300,
            selfBind = AutoBind.OFF,
            autoBind = AutoBind.OFF,
        )
    }
}
