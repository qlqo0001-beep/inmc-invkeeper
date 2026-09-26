package com.inmc.invkeeper.grave

import com.inmc.invkeeper.InvKeeper
import kr.inmc.core.store.YamlFolder
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/**
 * 사라진 무덤 한 건의 기록.
 *
 * 사망 시점의 **원본 내용물**을 들고 있어서, 관리자가 "이 사람이 무엇을 잃었는지"를 나중에도
 * 볼 수 있고 필요하면 돌려줄 수 있다. 무덤이 어떻게 사라졌든(전량 회수·도굴·만료) 남는다.
 */
class GraveRecord(
    val id: UUID,
    val ownerId: UUID,
    val ownerName: String,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val createdAt: Long,
    val endedAt: Long,
    val recoveredBy: RecoveryType,
    val looterName: String?,
    /** 사망 시점 원본. */
    val original: GraveContents,
    /** 사라질 때 남아 있던 것. 만료로 사라졌으면 여기에 아이템이 들어 있다. */
    val leftover: GraveContents,
) {

    fun save(section: org.bukkit.configuration.ConfigurationSection) {
        section.set("owner", ownerId.toString())
        section.set("owner-name", ownerName)
        section.set("world", worldName)
        section.set("x", x)
        section.set("y", y)
        section.set("z", z)
        section.set("created-at", createdAt)
        section.set("ended-at", endedAt)
        section.set("recovered-by", recoveredBy.name)
        looterName?.let { section.set("looter-name", it) }
        original.save(section.createSection("original"))
        leftover.save(section.createSection("leftover"))
    }

    companion object {

        fun of(grave: Grave, endedAt: Long) = GraveRecord(
            id = grave.id,
            ownerId = grave.ownerId,
            ownerName = grave.ownerName,
            worldName = grave.worldName,
            x = grave.x,
            y = grave.y,
            z = grave.z,
            createdAt = grave.createdAt,
            endedAt = endedAt,
            recoveredBy = grave.recoveredBy,
            looterName = grave.looterName,
            original = grave.original.copy(),
            leftover = grave.contents.copy(),
        )

        fun load(id: String, section: org.bukkit.configuration.ConfigurationSection): GraveRecord? {
            val recordId = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
            val ownerId = section.getString("owner")
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            return GraveRecord(
                id = recordId,
                ownerId = ownerId,
                ownerName = section.getString("owner-name").orEmpty().ifBlank { "?" },
                worldName = section.getString("world").orEmpty(),
                x = section.getInt("x"),
                y = section.getInt("y"),
                z = section.getInt("z"),
                createdAt = section.getLong("created-at"),
                endedAt = section.getLong("ended-at"),
                recoveredBy = RecoveryType.parse(section.getString("recovered-by")),
                looterName = section.getString("looter-name"),
                original = GraveContents.load(section.getConfigurationSection("original")),
                leftover = GraveContents.load(section.getConfigurationSection("leftover")),
            )
        }
    }
}

/**
 * 무덤 기록. **플레이어별 파일**이다 (`history/<uuid>.yml`).
 *
 * 한 파일에 몰면 기록 하나가 늘 때마다 전원의 인벤토리 스냅샷을 통째로 다시 쓴다. 기록은
 * 계속 쌓이는 종류라 그 비용이 시간이 갈수록 커진다. 사람별로 나누면 바뀐 사람 것만 쓴다.
 */
class GraveHistory(private val inv: InvKeeper) {

    private val files = YamlFolder(inv.io, inv.logger, "history", what = "무덤 기록")

    private val byPlayer = HashMap<UUID, MutableList<GraveRecord>>()

    fun of(ownerId: UUID): List<GraveRecord> =
        byPlayer[ownerId].orEmpty().sortedByDescending { it.endedAt }

    /** 기록을 더하고 보관 정책(기간·개수)을 적용한다. */
    fun record(grave: Grave, now: Long) {
        val records = byPlayer.getOrPut(grave.ownerId) { ArrayList() }
        records.add(GraveRecord.of(grave, now))
        prune(grave.ownerId, records, now)
        files.markDirty(grave.ownerId.toString())
    }

    /** 이 사람의 기록이 바뀌었다고 표시한다. 다음 플러시에 파일이 쓰인다. */
    fun markChanged(ownerId: UUID) {
        files.markDirty(ownerId.toString())
    }

    /** 기록 하나에서 남은 아이템을 비운다. 관리자가 돌려준 뒤 부른다. */
    fun clearLeftover(ownerId: UUID, recordId: UUID) {
        val record = byPlayer[ownerId]?.firstOrNull { it.id == recordId } ?: return
        for (slot in record.leftover.slots.indices) record.leftover.slots[slot] = null
        record.leftover.exp = 0
        files.markDirty(ownerId.toString())
    }

    /**
     * 보관 정책 적용. 기간이 지난 것을 먼저 버리고, 그래도 많으면 오래된 순으로 버린다.
     *
     * 둘 다 0 이하면 제한 없음이라는 뜻이다 — `config.yml` 이 그렇게 약속한다.
     */
    private fun prune(ownerId: UUID, records: MutableList<GraveRecord>, now: Long) {
        val days = inv.config.grave.historyRetentionDays
        if (days > 0) {
            val cutoff = now - days * 86_400_000L
            records.removeAll { it.endedAt < cutoff }
        }
        val max = inv.config.grave.historyMaxPerPlayer
        if (max > 0 && records.size > max) {
            records.sortBy { it.endedAt }
            while (records.size > max) records.removeAt(0)
        }
        if (records.isEmpty()) byPlayer.remove(ownerId)
    }

    /** 보관 기간이 지난 기록을 전부 정리한다. 틱커가 가끔 부른다. */
    fun purge(now: Long) {
        if (inv.config.grave.historyRetentionDays <= 0) return
        for (ownerId in byPlayer.keys.toList()) {
            val records = byPlayer[ownerId] ?: continue
            val before = records.size
            prune(ownerId, records, now)
            if (records.size != before) files.markDirty(ownerId.toString())
        }
    }

    fun load(then: (Int) -> Unit = {}) {
        inv.io.async({
            files.readAll(skip = { name -> runCatching { UUID.fromString(name) }.isFailure }) { id, config ->
                val records = ArrayList<GraveRecord>()
                config.getConfigurationSection("records")?.let { section ->
                    for (key in section.getKeys(false)) {
                        val record = section.getConfigurationSection(key)
                            ?.let { GraveRecord.load(key, it) } ?: continue
                        records.add(record)
                    }
                }
                if (records.isEmpty()) null else records
            }
        }) { loaded ->
            byPlayer.clear()
            files.clearDirty()
            for ((id, records) in loaded) {
                val ownerId = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
                byPlayer[ownerId] = records
            }
            then(byPlayer.values.sumOf { it.size })
        }
    }

    fun flush() = files.flushDirty(::render)

    fun flushBlocking() = files.flushDirtyBlocking(::render)

    /** 메인 스레드에서 돈다. */
    private fun render(id: String): YamlConfiguration? {
        val ownerId = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        val records = byPlayer[ownerId] ?: return null
        val config = YamlConfiguration()
        val section = config.createSection("records")
        for (record in records) record.save(section.createSection(record.id.toString()))
        return config
    }
}
