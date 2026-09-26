package com.inmc.invkeeper.config

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `config.yml` 한 벌의 **불변 스냅샷**.
 *
 * 리로드는 이 객체를 통째로 새로 만들어 바꿔 끼운다. 1세대의 `ConfigManager` 는 값을 필드로
 * 들고 있으면서 `reload()` 가 그것들을 하나씩 덮어써서, 리로드 도중에 읽는 쪽이 반쯤 바뀐
 * 설정을 보는 구간이 있었다. 스냅샷이면 그 구간 자체가 없다.
 */
class PluginConfig(
    /** 로드/월드 로드마다 모든 월드의 keepInventory 를 false 로 되돌린다 (인벤 복제 방지). */
    val forceKeepInventoryFalse: Boolean,
    /** 각인 만료 검사를 몇 초에 걸쳐 나눠 돌지. 1 이상. */
    val soulbindScanBatches: Int,
    val timezone: String,
    /** 아이템 하나에 줄 수 있는 최대 각인 스택. -1 은 무제한. */
    val maxSoulbindStack: Int,
    val pickupMessageCooldownSeconds: Long,
    val useMessageCooldownSeconds: Long,
    val dropRules: DropRules,
    val grave: GraveSettings,
) {

    companion object {

        fun from(config: YamlConfiguration): PluginConfig = PluginConfig(
            forceKeepInventoryFalse = config.getBoolean("force-keep-inventory-false", true),
            // 0 이하는 의미가 없다. 1세대도 같은 보정을 했고 config.yml 이 그렇게 약속한다.
            soulbindScanBatches = config.getInt("soulbind-scan-batches", 5).coerceAtLeast(1),
            timezone = config.getString("timezone").orEmpty().ifBlank { "Asia/Seoul" },
            maxSoulbindStack = config.getInt("max-soulbind-stack", -1),
            pickupMessageCooldownSeconds =
                config.getLong("soulbind-pickup-message-cooldown-seconds", 5L).coerceAtLeast(0L),
            useMessageCooldownSeconds =
                config.getLong("soulbind-use-message-cooldown-seconds", 3L).coerceAtLeast(0L),
            dropRules = readDropRules(config.getConfigurationSection("rules")),
            grave = GraveSettings.from(config.getConfigurationSection("grave")),
        )

        private fun readDropRules(section: ConfigurationSection?): DropRules {
            if (section == null) return DropRules.NONE

            var default = DropRule.KEEP_ALL
            val worlds = LinkedHashMap<String, DropRule>()
            section.getConfigurationSection("world")?.let { worldSection ->
                for (key in worldSection.getKeys(false)) {
                    val rule = worldSection.getConfigurationSection(key)?.let { readRule(it) } ?: continue
                    if (key.equals("default", ignoreCase = true)) default = rule else worlds[key] = rule
                }
            }

            val permissions = ArrayList<PermissionDropRule>()
            for (raw in section.getMapList("permissions")) {
                val permission = raw["permission"]?.toString().orEmpty()
                if (permission.isBlank()) continue
                permissions += PermissionDropRule(
                    permission = permission,
                    priority = number(raw["priority"]).toInt(),
                    rule = DropRule(
                        inventoryPercent = percent(raw["inventory-drop-percent"]),
                        expPercent = percent(raw["exp-drop-percent"]),
                    ),
                )
            }
            return DropRules(default, worlds, permissions)
        }

        private fun readRule(section: ConfigurationSection) = DropRule(
            inventoryPercent = percent(section.get("inventory-drop-percent")),
            expPercent = percent(section.get("exp-drop-percent")),
        )

        /** 설정은 0~100 으로 약속돼 있다. 벗어난 값은 자르고 넘어간다 - 사망 경로에서 터지면 안 된다. */
        private fun percent(raw: Any?): Double = number(raw).coerceIn(0.0, 100.0)

        private fun number(raw: Any?): Double = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}

/** 무덤 블록으로 무엇을 놓을지. */
enum class GraveContainerType { VANILLA, CUSTOM_BLOCK }

/** 권한별 무덤 만료 시간 덮어쓰기. [seconds] 가 0 이하면 만료 없음. */
data class GraveExpireRule(val permission: String, val priority: Int, val seconds: Long)

class GraveSettings(
    val enabled: Boolean,
    val containerType: GraveContainerType,
    val vanillaMaterial: Material,
    val customBlockId: String,
    val customBlockProvider: String,
    /** 1인당 동시 무덤 수. 0 이하면 무제한. */
    val maxPerPlayer: Int,
    val expireEnabled: Boolean,
    /** 0 이하면 만료 없음. */
    val expireDefaultSeconds: Long,
    val expireOverrides: List<GraveExpireRule>,
    /** 0 이하면 기간 제한 없음. */
    val historyRetentionDays: Int,
    /** 0 이하면 개수 제한 없음. */
    val historyMaxPerPlayer: Int,
    private val disabledWorlds: Set<String>,
    val hologram: HologramSettings,
    val protection: GraveProtectionSettings,
) {

    fun isWorldDisabled(worldName: String?): Boolean =
        worldName != null && disabledWorlds.contains(worldName.lowercase())

    /**
     * 이 플레이어의 무덤이 몇 초 뒤에 사라지는지. 0 이하면 만료하지 않는다.
     *
     * 드랍 규칙과 같은 "priority 높은 것 하나만" 방식이다.
     */
    fun expireSecondsFor(has: (String) -> Boolean): Long {
        if (!expireEnabled) return 0L
        var best = expireDefaultSeconds
        var bestPriority = Int.MIN_VALUE
        for (rule in expireOverrides) {
            if (rule.priority <= bestPriority) continue
            if (!has(rule.permission)) continue
            best = rule.seconds
            bestPriority = rule.priority
        }
        return best
    }

    companion object {

        fun from(section: ConfigurationSection?): GraveSettings {
            val container = section?.getConfigurationSection("container")
            val expire = section?.getConfigurationSection("expire")
            val history = section?.getConfigurationSection("history")

            val overrides = ArrayList<GraveExpireRule>()
            for (raw in expire?.getMapList("permission-overrides").orEmpty()) {
                val permission = raw["permission"]?.toString().orEmpty()
                if (permission.isBlank()) continue
                overrides += GraveExpireRule(
                    permission = permission,
                    priority = (raw["priority"] as? Number)?.toInt() ?: 0,
                    seconds = (raw["seconds"] as? Number)?.toLong() ?: 3600L,
                )
            }

            return GraveSettings(
                enabled = section?.getBoolean("enabled", true) ?: true,
                containerType = runCatching {
                    GraveContainerType.valueOf(
                        container?.getString("type").orEmpty().uppercase().ifBlank { "VANILLA" },
                    )
                }.getOrDefault(GraveContainerType.VANILLA),
                vanillaMaterial = Material.matchMaterial(
                    container?.getString("vanilla-material").orEmpty().ifBlank { "BARREL" },
                ) ?: Material.BARREL,
                customBlockId = container?.getString("custom-block-id").orEmpty(),
                customBlockProvider = container?.getString("custom-block-provider").orEmpty(),
                maxPerPlayer = section?.getInt("max-graves-per-player", 5) ?: 5,
                expireEnabled = expire?.getBoolean("enabled", true) ?: true,
                expireDefaultSeconds = expire?.getLong("default-seconds", 3600L) ?: 3600L,
                expireOverrides = overrides,
                historyRetentionDays = history?.getInt("retention-days", 30) ?: 30,
                historyMaxPerPlayer = history?.getInt("max-entries-per-player", 50) ?: 50,
                disabledWorlds = section?.getStringList("disabled-worlds")
                    .orEmpty().map { it.lowercase() }.toSet(),
                hologram = HologramSettings.from(section?.getConfigurationSection("hologram")),
                protection = GraveProtectionSettings.from(section?.getConfigurationSection("protection")),
            )
        }
    }
}

class HologramSettings(
    val enabled: Boolean,
    val offsetY: Double,
    val updateIntervalTicks: Long,
    val lineFormat: String,
    val lineFormatUnlimited: String,
    val lootingLineFormat: String,
    val lootedLineFormat: String,
) {
    companion object {
        fun from(section: ConfigurationSection?) = HologramSettings(
            enabled = section?.getBoolean("enabled", true) ?: true,
            offsetY = section?.getDouble("offset-y", 1.0) ?: 1.0,
            // config.yml 이 20틱 미만을 권하지 않는다고 적어놨지만 1세대는 강제하지 않았다.
            // 0 이하만 막는다 - 그건 스케줄러가 거부한다.
            updateIntervalTicks = (section?.getLong("update-interval-ticks", 20L) ?: 20L).coerceAtLeast(1L),
            lineFormat = section?.getString("line-format").orEmpty()
                .ifBlank { "{player} 의 무덤 {remaining}" },
            lineFormatUnlimited = section?.getString("line-format-unlimited").orEmpty()
                .ifBlank { "{player} 의 무덤" },
            lootingLineFormat = section?.getString("looting-line-format").orEmpty()
                .ifBlank { "도굴중 {remaining}" },
            lootedLineFormat = section?.getString("looted-line-format").orEmpty()
                .ifBlank { "{looter}님이 도굴을 한 {owner}의 무덤" },
        )
    }
}

/**
 * 무덤 블록 보호.
 *
 * 1세대에서는 이 네 값이 **코드와 연결돼 있지 않았다** — 파괴/폭발/피스톤은 설정과 무관하게
 * 항상 막혔고 `prevent-hopper` 는 기능 자체가 없었다. 여기서는 실제로 읽어 쓴다.
 * 후퍼는 무덤 블록이 장식일 뿐 진짜 인벤토리가 아니라서 빨아갈 것이 애초에 없지만,
 * 커스텀 블록 제공자가 진짜 컨테이너를 놓는 경우를 위해 막아둔다.
 */
class GraveProtectionSettings(
    val preventBlockBreak: Boolean,
    val preventExplosion: Boolean,
    val preventPiston: Boolean,
    val preventHopper: Boolean,
) {
    companion object {
        fun from(section: ConfigurationSection?) = GraveProtectionSettings(
            preventBlockBreak = section?.getBoolean("prevent-block-break", true) ?: true,
            preventExplosion = section?.getBoolean("prevent-explosion", true) ?: true,
            preventPiston = section?.getBoolean("prevent-piston", true) ?: true,
            preventHopper = section?.getBoolean("prevent-hopper", true) ?: true,
        )
    }
}
