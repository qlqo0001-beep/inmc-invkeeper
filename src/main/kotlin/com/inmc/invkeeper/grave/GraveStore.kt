package com.inmc.invkeeper.grave

import com.inmc.invkeeper.InvKeeper
import kr.inmc.core.store.YamlFileStore
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 살아 있는 무덤 전부. `graves.yml` 한 파일이다.
 *
 * 한 파일로 둔 것은 무덤 수가 적기 때문이다 — 1인당 기본 5개에 기본 만료가 1시간이라, 아무리
 * 붐벼도 수백 개다. 기록(`history/`)은 반대로 계속 쌓이므로 플레이어별 파일로 나눈다.
 *
 * 좌표 색인을 따로 들고 있다. 무덤을 우클릭할 때마다 전체를 훑으면 안 되기 때문이다.
 */
class GraveStore(private val inv: InvKeeper) : YamlFileStore(
    io = inv.io,
    path = listOf("graves.yml"),
    header = "살아 있는 무덤. 직접 고치지 마세요 - 서버가 켜져 있는 동안 덮어씁니다.",
    what = "무덤",
) {

    private val byId = ConcurrentHashMap<UUID, Grave>()

    /** `world:x:y:z` → 무덤. 블록 상호작용이 이것만 본다. */
    private val byBlock = ConcurrentHashMap<String, Grave>()

    val size: Int get() = byId.size

    fun get(id: UUID?): Grave? = id?.let { byId[it] }

    fun at(world: String, x: Int, y: Int, z: Int): Grave? = byBlock[Grave.blockKey(world, x, y, z)]

    fun of(ownerId: UUID): List<Grave> =
        byId.values.filter { it.ownerId == ownerId }.sortedBy { it.createdAt }

    fun all(): List<Grave> = byId.values.sortedBy { it.createdAt }

    fun add(grave: Grave) {
        byId[grave.id] = grave
        byBlock[grave.blockKey()] = grave
        markDirty()
    }

    fun remove(grave: Grave) {
        byId.remove(grave.id)
        byBlock.remove(grave.blockKey())
        markDirty()
    }

    /** 만료된 무덤들. 지우는 것은 부르는 쪽이 한다 - 블록 되돌리기와 기록 남기기가 딸려 있다. */
    fun expired(now: Long): List<Grave> = byId.values.filter { it.isExpired(now) }

    /** 메인 스레드에서 돈다. */
    override fun read(config: YamlConfiguration) {
        byId.clear()
        byBlock.clear()
        val graves = config.getConfigurationSection("graves") ?: return
        for (key in graves.getKeys(false)) {
            val section = graves.getConfigurationSection(key) ?: continue
            val grave = Grave.load(key, section)
            if (grave == null) {
                inv.logger.warning("무덤 '$key' 을(를) 읽지 못해 건너뜁니다")
                continue
            }
            byId[grave.id] = grave
            byBlock[grave.blockKey()] = grave
        }
    }

    /** 메인 스레드에서 돈다. */
    override fun write(config: YamlConfiguration) {
        val graves = config.createSection("graves")
        for (grave in byId.values) {
            grave.save(graves.createSection(grave.id.toString()))
        }
    }
}
