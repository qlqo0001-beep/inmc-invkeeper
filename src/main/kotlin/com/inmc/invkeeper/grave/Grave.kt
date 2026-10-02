package com.inmc.invkeeper.grave

import com.inmc.invkeeper.soulbind.AutoBindService
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID

/** 무덤의 생애주기. */
enum class GraveState {
    /** 주인만 열 수 있다. */
    ACTIVE,

    /** 도굴 시전이 돌고 있다. */
    BEING_LOOTED,

    /** 도굴이 끝나 누구나 열 수 있다. */
    LOOTED;

    companion object {
        fun parse(raw: String?): GraveState =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ACTIVE
    }
}

/** 무덤을 누가 비웠는지. 기록 화면에만 쓴다. */
enum class RecoveryType {
    NONE, OWNER, LOOTER;

    companion object {
        fun parse(raw: String?): RecoveryType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

/**
 * 무덤에 담긴 것.
 *
 * 1세대는 장비 4칸·왼손·인벤토리 36칸을 **세 개의 배열**로 따로 들고 있었고, 그래서 슬롯을
 * 다룰 때마다 어느 배열의 몇 번인지 환산하는 코드가 붙었다. 여기서는 **플레이어 인벤토리와
 * 같은 41칸 한 벌**로 둔다 — 읽을 때도 돌려줄 때도 환산이 없다. 화면 배치만 [GraveMenu] 가
 * 따로 매핑한다.
 */
class GraveContents(
    val slots: Array<ItemStack?> = arrayOfNulls(SIZE),
    var exp: Int = 0,
) {

    fun isEmpty(): Boolean = exp <= 0 && slots.all { it == null || it.type.isAir }

    /** 남아 있는 칸 수. 스택 안의 개수가 아니라 칸을 센다. */
    fun countSlots(): Int = slots.count { it != null && !it.type.isAir }

    fun copy(): GraveContents =
        GraveContents(Array(SIZE) { slots[it]?.clone() }, exp)

    fun take(slot: Int): ItemStack? {
        val stack = slots.getOrNull(slot) ?: return null
        slots[slot] = null
        return stack
    }

    fun save(section: ConfigurationSection) {
        section.set("exp", exp)
        val items = section.createSection("slots")
        for (slot in 0 until SIZE) {
            val stack = slots[slot] ?: continue
            if (stack.type.isAir) continue
            items.set(slot.toString(), Base64.getEncoder().encodeToString(stack.serializeAsBytes()))
        }
    }

    companion object {

        /** 보관칸 0~35 · 방어구 36~39 · 왼손 40. 플레이어 인벤토리와 같다. */
        const val SIZE = AutoBindService.LAST_SLOT + 1

        fun load(section: ConfigurationSection?): GraveContents {
            if (section == null) return GraveContents()
            val slots = arrayOfNulls<ItemStack>(SIZE)
            section.getConfigurationSection("slots")?.let { items ->
                for (key in items.getKeys(false)) {
                    val slot = key.toIntOrNull() ?: continue
                    if (slot !in 0 until SIZE) continue
                    val raw = items.getString(key) ?: continue
                    // 읽지 못하는 칸은 버리고 나머지를 살린다. 아이템 하나 때문에 무덤 전체를
                    // 잃는 것보다 낫다 - 서버 버전이 바뀌면 실제로 일어난다.
                    slots[slot] = runCatching {
                        ItemStack.deserializeBytes(Base64.getDecoder().decode(raw))
                    }.getOrNull()
                }
            }
            return GraveContents(slots, section.getInt("exp", 0))
        }
    }
}

/**
 * 무덤 하나.
 *
 * 위치·주인·만료는 생성 시점에 정해지고 그 뒤로는 상태 전이만 일어난다.
 */
class Grave(
    val id: UUID,
    val ownerId: UUID,
    val ownerName: String,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val createdAt: Long,
    /** 만료 시각. [NEVER] 면 만료하지 않는다. */
    val expireAt: Long,
    /** 무덤을 놓기 전에 그 자리에 있던 블록. 무덤이 사라질 때 되돌린다. */
    val replacedMaterial: Material,
    val contents: GraveContents,
    /** 사망 시점 원본. 기록 화면이 "원래 무엇이 있었는지" 보여주는 데 쓴다. */
    val original: GraveContents,
) {

    var state: GraveState = GraveState.ACTIVE
    var looterId: UUID? = null
    var looterName: String? = null
    var recoveredAt: Long = 0L
    var recoveredBy: RecoveryType = RecoveryType.NONE

    val hasExpiry: Boolean get() = expireAt != NEVER

    fun isExpired(now: Long): Boolean = hasExpiry && now >= expireAt

    /** 남은 밀리초. 무제한이면 -1. */
    fun remainingMillis(now: Long): Long =
        if (!hasExpiry) NEVER else (expireAt - now).coerceAtLeast(0L)

    fun markRecovered(type: RecoveryType, now: Long) {
        recoveredAt = now
        recoveredBy = type
    }

    /** 이 무덤을 지금 열 수 있는 사람인지. 관리자 우회는 부르는 쪽이 본다. */
    fun canOpen(playerId: UUID): Boolean =
        ownerId == playerId || state == GraveState.LOOTED

    fun location(): Location? {
        val world = org.bukkit.Bukkit.getWorld(worldName) ?: return null
        return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
    }

    /** 블록 좌표 하나를 가리키는 문자열 키. 상호작용에서 무덤을 찾는 데 쓴다. */
    fun blockKey(): String = blockKey(worldName, x, y, z)

    /** 놓은 무덤 블록(`BlockRef` 문자열) — 설정을 바꿔도 사라질 때 이 무덤이 놓은 것을 치운다. 옛 기록은 빈 칸(그때 설정). */
    var block: String = ""

    /** 놓은 직후 그 칸의 재질(커스텀 블록이면 받침 블록) — 사라질 때 이것일 때만 되돌린다(그 사이 누가 바꿨으면 손대지 않게). */
    var placed: Material? = null

    fun save(section: ConfigurationSection) {
        section.set("owner", ownerId.toString())
        section.set("owner-name", ownerName)
        section.set("world", worldName)
        section.set("x", x)
        section.set("y", y)
        section.set("z", z)
        section.set("created-at", createdAt)
        section.set("expire-at", expireAt)
        section.set("replaced", replacedMaterial.key().toString())
        if (block.isNotBlank()) section.set("block", block)
        placed?.let { section.set("placed", it.key().toString()) }
        section.set("state", state.name)
        looterId?.let { section.set("looter", it.toString()) }
        looterName?.let { section.set("looter-name", it) }
        if (recoveredAt > 0L) {
            section.set("recovered-at", recoveredAt)
            section.set("recovered-by", recoveredBy.name)
        }
        contents.save(section.createSection("contents"))
        original.save(section.createSection("original"))
    }

    companion object {

        /** 만료하지 않음. 파일에도 이 값이 그대로 들어간다. */
        const val NEVER = -1L

        fun blockKey(world: String, x: Int, y: Int, z: Int): String = "$world:$x:$y:$z"

        fun load(id: String, section: ConfigurationSection): Grave? {
            val graveId = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
            val ownerId = section.getString("owner")
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val world = section.getString("world") ?: return null

            val grave = Grave(
                id = graveId,
                ownerId = ownerId,
                ownerName = section.getString("owner-name").orEmpty().ifBlank { "?" },
                worldName = world,
                x = section.getInt("x"),
                y = section.getInt("y"),
                z = section.getInt("z"),
                createdAt = section.getLong("created-at"),
                expireAt = section.getLong("expire-at", NEVER),
                replacedMaterial = section.getString("replaced")
                    ?.let { Material.matchMaterial(it) } ?: Material.AIR,
                contents = GraveContents.load(section.getConfigurationSection("contents")),
                original = GraveContents.load(section.getConfigurationSection("original")),
            )
            grave.state = GraveState.parse(section.getString("state"))
            grave.block = section.getString("block").orEmpty()
            grave.placed = section.getString("placed")?.let { Material.matchMaterial(it) }
            grave.looterId = section.getString("looter")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            grave.looterName = section.getString("looter-name")
            grave.recoveredAt = section.getLong("recovered-at", 0L)
            grave.recoveredBy = RecoveryType.parse(section.getString("recovered-by"))
            return grave
        }
    }
}
