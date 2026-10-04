package com.inmc.invkeeper.gui

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.config.PluginConfig
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 월드별 규칙 — 무덤 on/off + 드랍율. `config.yml` 을 손으로 고치지 않게 하는 것이 목적이다.
 *
 * 목록에서 월드를 고르면 그 월드 편집으로 간다. 목록에 뜨는 것은 **로드된 월드 + 설정에 적힌 월드**다 —
 * 언로드된 월드는 Bukkit 목록에 없으므로 설정 키로라도 잡는다. `default` 는 의사 키라 목록에서 뺀다.
 *
 * 저장은 무덤 블록 버튼(`GraveManager.setBlock`)과 같은 길이다 — `config.yml` 을 읽어 고쳐 쓰고
 * 스냅샷을 통째로 바꾼다. 파일이 작아 메인 스레드에서 한다.
 */
class WorldRuleMenu(
    inv: InvKeeper,
    private val viewer: Player,
    private var page: Int = 0,
) : Menu(inv, SIZE, Text.renderFlat("<dark_gray>인벤키퍼 — 월드 규칙</dark_gray>")) {

    override fun draw() {
        clear()

        val shown = worldNames()
        page = Paging.clamp(page, shown.size)
        val configured = configuredWorlds(inv).map { it.lowercase() }.toSet()

        for ((index, name) in Paging.slice(shown, page).withIndex()) {
            set(index, tile(name, configured)) { WorldEditMenu(inv, viewer, name).open(viewer) }
        }

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

        set(SLOT_DEFAULT, defaultTile())
        set(Paging.SLOT_BACK, Icon.back()) { ItemListMenu(inv, viewer).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun worldNames(): List<String> {
        val loaded = Bukkit.getWorlds().map { it.name }
        val configured = configuredWorlds(inv)
        return (loaded + configured).distinct().sorted()
    }

    private fun tile(name: String, configured: Set<String>): ItemStack {
        val graveOff = inv.config.grave.isWorldDisabled(name)
        val rule = inv.config.dropRules.forWorld(name)
        val custom = configured.contains(name.lowercase())
        return Icon.of(
            if (graveOff) Material.BARRIER else Material.GRASS_BLOCK,
            "<white>$name</white>",
            listOf(
                "<gray>무덤: " + (if (graveOff) "<red>OFF</red>" else "<green>ON</green>") + "</gray>",
                "<gray>인벤 <white>${Math.round(rule.inventoryPercent)}%</white> · 경험치 <white>${Math.round(rule.expPercent)}%</white>" +
                    (if (custom) "" else " <dark_gray>(기본값)</dark_gray>") + "</gray>",
                "",
                "<yellow>▶ 클릭: 이 월드 편집</yellow>",
            ),
        )
    }

    private fun defaultTile(): ItemStack {
        val rule = inv.config.dropRules.forWorld(null)
        return Icon.of(
            Material.BOOK,
            "<aqua>기본 규칙</aqua>",
            listOf(
                "<gray>인벤 <white>${Math.round(rule.inventoryPercent)}%</white> · 경험치 <white>${Math.round(rule.expPercent)}%</white></gray>",
                "<dark_gray>목록에 없는 월드는 이 값을 씁니다.</dark_gray>",
                "<dark_gray>기본값은 config.yml 에서만 바꿉니다.</dark_gray>",
            ),
        )
    }

    private companion object {
        const val SIZE = 54
        const val SLOT_DEFAULT = 49
    }
}

/** 월드 하나 편집 — 무덤 토글·인벤%·경험치%·초기화. */
class WorldEditMenu(
    inv: InvKeeper,
    private val viewer: Player,
    private val world: String,
) : Menu(inv, SIZE, Text.renderFlat("<dark_gray>인벤키퍼 — $world</dark_gray>")) {

    override fun draw() {
        clear()

        val graveOff = inv.config.grave.isWorldDisabled(world)
        val rule = inv.config.dropRules.forWorld(world)

        set(SLOT_GRAVE, Icon.of(
            if (graveOff) Material.REDSTONE_BLOCK else Material.EMERALD_BLOCK,
            "<yellow>무덤: " + (if (graveOff) "<red>OFF</red>" else "<green>ON</green>") + "</yellow>",
            listOf(
                "<gray>OFF 면 여기서 죽어도 무덤 없이 바닥에 드랍됩니다.</gray>",
                "",
                "<yellow>▶ 클릭: 전환</yellow>",
            ),
        )) {
            saveWorldRule(inv) { yaml ->
                val disabled = yaml.getStringList("grave.disabled-worlds").map { it.lowercase() }.toMutableList()
                if (graveOff) disabled.remove(world.lowercase()) else disabled.add(world.lowercase())
                yaml.set("grave.disabled-worlds", disabled.distinct())
            }
            refresh()
        }

        set(SLOT_INV, Editors.intIcon(
            Material.CHEST,
            "<yellow>인벤 드랍율</yellow>",
            Math.round(rule.inventoryPercent).toInt(),
            unit = "%",
            extra = listOf("<dark_gray>$world</dark_gray>"),
            stepLabel = "5",
        )) { event ->
            val next = (Math.round(rule.inventoryPercent).toInt() + Editors.step(event, 5)).coerceIn(0, 100)
            saveWorldRule(inv) { yaml -> yaml.set("rules.world.$world.inventory-drop-percent", next) }
            refresh()
        }

        set(SLOT_EXP, Editors.intIcon(
            Material.EXPERIENCE_BOTTLE,
            "<yellow>경험치 드랍율</yellow>",
            Math.round(rule.expPercent).toInt(),
            unit = "%",
            extra = listOf("<dark_gray>$world</dark_gray>"),
            stepLabel = "5",
        )) { event ->
            val next = (Math.round(rule.expPercent).toInt() + Editors.step(event, 5)).coerceIn(0, 100)
            saveWorldRule(inv) { yaml -> yaml.set("rules.world.$world.exp-drop-percent", next) }
            refresh()
        }

        set(SLOT_RESET, Icon.of(
            Material.BUCKET,
            "<red>이 월드 초기화</red>",
            listOf(
                "<gray>드랍율은 기본값으로, 무덤은 ON 으로 되돌립니다.</gray>",
                "",
                "<red>▶ 클릭: 초기화</red>",
            ),
        )) {
            saveWorldRule(inv) { yaml ->
                yaml.set("rules.world.$world", null)
                val disabled = yaml.getStringList("grave.disabled-worlds").map { it.lowercase() } - world.lowercase()
                yaml.set("grave.disabled-worlds", disabled)
            }
            WorldRuleMenu(inv, viewer).open(viewer)
        }

        set(SLOT_BACK, Icon.back()) { WorldRuleMenu(inv, viewer).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private companion object {
        const val SIZE = 27
        const val SLOT_GRAVE = 11
        const val SLOT_INV = 13
        const val SLOT_EXP = 15
        const val SLOT_RESET = 22
        const val SLOT_BACK = 18
        const val SLOT_CLOSE = 26
    }
}

/** 설정에 적힌 월드 키(`default` 제외). */
internal fun configuredWorlds(inv: InvKeeper): List<String> {
    val yaml = YamlConfiguration.loadConfiguration(inv.io.file("config.yml"))
    return yaml.getConfigurationSection("rules.world")?.getKeys(false)
        .orEmpty().filter { !it.equals("default", ignoreCase = true) }.sorted()
}

/** `config.yml` 을 읽어 고쳐 쓰고 스냅샷을 바꾼다. 무덤 블록 버튼과 같은 길이다. */
private fun saveWorldRule(inv: InvKeeper, mutate: (YamlConfiguration) -> Unit) {
    val file = inv.io.file("config.yml")
    val yaml = YamlConfiguration.loadConfiguration(file)
    mutate(yaml)
    yaml.save(file)
    inv.config = PluginConfig.from(yaml)
}
