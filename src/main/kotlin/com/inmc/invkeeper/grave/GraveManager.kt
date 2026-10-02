package com.inmc.invkeeper.grave

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.config.GraveContainerType
import com.inmc.invkeeper.util.Ph
import kr.inmc.core.item.BlockRef
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.NamespacedKey
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * 무덤의 생성·소멸과 그 부수효과(블록·홀로그램·기록)를 한 곳에서 다룬다.
 *
 * 무덤이 사라지는 길은 넷이다 — 주인이 전량 회수 / 도굴 완료 후 비워짐 / 시간 만료 /
 * 개수 초과로 밀려남. **넷 다 [dispose] 하나를 지난다.** 1세대는 각 경로가 블록 되돌리기와
 * 기록 남기기를 따로 했고, 그래서 만료 경로만 홀로그램을 안 지우는 버그가 있었다.
 */
class GraveManager(private val inv: InvKeeper) {

    val store = GraveStore(inv)
    val history = GraveHistory(inv)

    /** 무덤 id → 홀로그램. 서버가 꺼지면 사라지므로 파일에 남기지 않는다. */
    private val holograms = HashMap<UUID, TextDisplay>()

    // --- 생성 --------------------------------------------------------------------

    /**
     * 죽은 자리에 무덤을 세운다.
     *
     * @return 세운 무덤. 무덤을 만들 수 없는 상황(기능 꺼짐·제외 월드·빈 손)이면 null 이고,
     *         그때는 부르는 쪽이 예전처럼 바닥에 흘려야 한다.
     */
    fun create(player: Player, contents: GraveContents, now: Long): Grave? {
        val settings = inv.config.grave
        if (!settings.enabled) return null
        if (settings.isWorldDisabled(player.world.name)) return null
        if (contents.isEmpty()) return null

        enforceLimit(player, now)

        val spot = findSpot(player.location) ?: return null
        val replaced = spot.block.type

        val expireSeconds = settings.expireSecondsFor(player::hasPermission)
        val grave = Grave(
            id = UUID.randomUUID(),
            ownerId = player.uniqueId,
            ownerName = player.name,
            worldName = spot.world?.name ?: player.world.name,
            x = spot.blockX,
            y = spot.blockY,
            z = spot.blockZ,
            createdAt = now,
            expireAt = if (expireSeconds <= 0L) Grave.NEVER else now + expireSeconds * 1000L,
            replacedMaterial = replaced,
            contents = contents,
            original = contents.copy(),
        )

        placeBlock(grave)
        store.add(grave)
        spawnHologram(grave, now)

        inv.messages.send(player, "grave-created", Ph.of().at(spot))
        return grave
    }

    /** 개수 제한을 넘겼으면 가장 오래된 것부터 치운다. */
    private fun enforceLimit(player: Player, now: Long) {
        val max = inv.config.grave.maxPerPlayer
        if (max <= 0) return
        val mine = store.of(player.uniqueId)
        if (mine.size < max) return

        inv.messages.send(player, "grave-max-reached", Ph.of().max(max))
        // size - max + 1 개를 치워야 새 것을 넣을 자리가 난다.
        for (grave in mine.take(mine.size - max + 1)) dispose(grave, now)
    }

    /**
     * 무덤을 놓을 자리.
     *
     * 죽은 지점이 공기가 아니면(물·용암·블록 안) 위로 올려 본다. 끝까지 못 찾으면 null 을
     * 돌려 바닥 드랍으로 넘긴다 — 무덤을 억지로 놓아 남의 건물을 파묻는 것보다 낫다.
     */
    private fun findSpot(from: Location): Location? {
        val world = from.world ?: return null
        val maxY = world.maxHeight - 1
        var y = from.blockY.coerceIn(world.minHeight, maxY)
        repeat(SEARCH_HEIGHT) {
            val candidate = Location(world, from.blockX.toDouble(), y.toDouble(), from.blockZ.toDouble())
            if (candidate.block.type.isAir || candidate.block.isLiquid) return candidate
            if (y >= maxY) return null
            y++
        }
        return null
    }

    private fun placeBlock(grave: Grave) {
        val block = grave.location()?.block ?: return
        val ref = graveBlockRef()
        inv.blockPlacer.place(block, ref)
        grave.block = ref.serialize()
        grave.placed = block.type
    }

    /** 지금 설정의 무덤 블록. */
    fun graveBlockRef(): BlockRef {
        val settings = inv.config.grave
        return when (settings.containerType) {
            GraveContainerType.VANILLA -> BlockRef.Vanilla(settings.vanillaMaterial)
            GraveContainerType.CUSTOM_BLOCK -> BlockRef.Custom(
                namespace = settings.customBlockProvider.lowercase(),
                id = settings.customBlockId,
                fallback = settings.vanillaMaterial,
            )
        }
    }

    // --- 소멸 --------------------------------------------------------------------

    /**
     * 무덤을 치운다. **모든 소멸 경로가 여기를 지난다.**
     *
     * 남아 있는 내용물은 기록에 그대로 담긴다. 바닥에 흘리지 않는 것이 의도다 — 만료된 무덤이
     * 아이템을 토해내면 만료가 의미를 잃고, 관리자가 기록에서 돌려줄 수 있다.
     */
    fun dispose(grave: Grave, now: Long) {
        removeHologram(grave)
        restoreBlock(grave)
        history.record(grave, now)
        store.remove(grave)
    }

    private fun restoreBlock(grave: Grave) {
        val block = grave.location()?.block ?: return
        // 이 무덤이 놓은 블록으로 치운다 — 그 뒤에 설정을 바꿨어도(옛 기록은 지금 설정).
        val ref = grave.block.takeIf { it.isNotBlank() }?.let { BlockRef.parse(it) } ?: graveBlockRef()
        inv.blockPlacer.clear(block, ref)
        // 놓기 전에 있던 블록으로 되돌린다. 그 사이 누가 바꿔놨어도 우리 무덤 블록일 때만
        // 손댄다 - 아니면 남의 건축물을 덮어쓴다.
        val expected = grave.placed ?: (ref as? BlockRef.Vanilla)?.material ?: inv.config.grave.vanillaMaterial
        if (block.type != expected) return
        block.setBlockData(grave.replacedMaterial.createBlockData(), false)
    }

    /**
     * 무덤 블록을 바꾼다(관리 화면 — urb 의 상자 블록처럼 손에 든 블록으로, 테섭 요청 2026-10-02). `config.yml` 의 `grave.container` 에
     * 적고(주석은 남는다) 그 자리에서 반영한다. null = 기본값(통). 이미 있는 무덤은 놓인 블록 그대로다.
     */
    fun setBlock(ref: BlockRef?) {
        val file = inv.io.file("config.yml")
        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
        when (ref) {
            is BlockRef.Custom -> {
                yaml.set("grave.container.type", "CUSTOM_BLOCK")
                yaml.set("grave.container.custom-block-provider", ref.namespace)
                yaml.set("grave.container.custom-block-id", ref.id)
            }
            else -> {
                yaml.set("grave.container.type", "VANILLA")
                yaml.set("grave.container.vanilla-material", ((ref as? BlockRef.Vanilla)?.material ?: Material.BARREL).name)
                yaml.set("grave.container.custom-block-provider", "")
                yaml.set("grave.container.custom-block-id", "")
            }
        }
        yaml.save(file)
        inv.config = com.inmc.invkeeper.config.PluginConfig.from(yaml)
    }

    // --- 조회 --------------------------------------------------------------------

    fun at(location: Location): Grave? {
        val world = location.world?.name ?: return null
        return store.at(world, location.blockX, location.blockY, location.blockZ)
    }

    // --- 틱 ---------------------------------------------------------------------

    fun tick(now: Long) {
        for (grave in store.expired(now)) {
            val owner = org.bukkit.Bukkit.getPlayer(grave.ownerId)
            owner?.let { inv.messages.send(it, "grave-expired", Ph.of().at(grave.location() ?: it.location)) }
            dispose(grave, now)
        }
        repairLoaded(now)
        updateHolograms(now)
    }

    /**
     * 청크가 다시 올라온 무덤을 되살린다 — 1세대 `repairChunk`(거기선 `ChunkLoadEvent`).
     *
     * 홀로그램은 `isPersistent = false` 라 **청크가 내려가면 버려진다.** 주인이 멀리서 되살아나면 무덤
     * 청크가 곧 내려가므로, 돌아왔을 때 홀로그램이 없었다(테스트 서버에서 사망 화면에 머무는 동안 실제로
     * 사라졌다). 블록이 비어 있으면(바깥 도구가 지웠으면) 다시 놓는다.
     * 청크가 안 올라온 무덤은 건드리지 않는다 — 여기서 청크를 올리면 안 된다.
     */
    private fun repairLoaded(now: Long) {
        for (grave in store.all()) {
            val at = grave.location() ?: continue
            val world = at.world ?: continue
            val cx = grave.x shr 4
            val cz = grave.z shr 4
            // 엔티티까지 올라온 청크만. 블록만 올라온 청크에서는 남아 있는 홀로그램이 안 보여
            // 새로 띄우면 겹친다(재부팅 직후 실제로 무덤마다 두 개씩 떴다).
            if (!world.isChunkLoaded(cx, cz) || !world.getChunkAt(cx, cz).isEntitiesLoaded) continue
            if (at.block.type.isAir) placeBlock(grave)
            if (inv.config.grave.hologram.enabled) ensureHologram(grave, now)
        }
    }

    fun flush() {
        store.flush()
        history.flush()
    }

    fun flushBlocking() {
        store.flushBlocking()
        history.flushBlocking()
    }

    // --- 홀로그램 ------------------------------------------------------------------

    private fun spawnHologram(grave: Grave, now: Long) {
        if (!inv.config.grave.hologram.enabled) return
        val base = grave.location() ?: return
        val at = base.clone().add(0.5, inv.config.grave.hologram.offsetY, 0.5)
        val display = base.world?.spawn(at, TextDisplay::class.java) { entity ->
            entity.isPersistent = false
            entity.billboard = org.bukkit.entity.Display.Billboard.CENTER
            entity.persistentDataContainer.set(HOLOGRAM_KEY, PersistentDataType.STRING, grave.id.toString())
        } ?: return
        holograms[grave.id] = display
        drawHologram(grave, display, now)
    }

    /**
     * 추적 중인 홀로그램이 살아 있으면 그대로 두고, 아니면 **남아 있는 것을 거두거나** 새로 띄운다.
     *
     * `isValid` 가 false 라고 사라진 것은 아니다 — 청크의 엔티티가 잠시 내려가 있어도 false 다.
     * 그래서 새로 띄우기 전에 이 무덤 몫으로 표시한 것([HOLOGRAM_KEY])을 찾아 하나만 남긴다(1세대 `purgeOrphansAt`).
     */
    private fun ensureHologram(grave: Grave, now: Long) {
        if (holograms[grave.id]?.isValid == true) return
        val found = taggedHolograms(grave)
        found.drop(1).forEach { it.remove() }
        val keep = found.firstOrNull()
        if (keep == null) {
            holograms.remove(grave.id)
            spawnHologram(grave, now)
            return
        }
        holograms[grave.id] = keep
        drawHologram(grave, keep, now)
    }

    /** 이 무덤 몫으로 띄운 홀로그램. 우리가 놓친 것도 찾는다. 올라와 있지 않은 청크는 빈 목록이다. */
    private fun taggedHolograms(grave: Grave): List<TextDisplay> {
        val base = grave.location()?.clone()?.add(0.5, 0.0, 0.5) ?: return emptyList()
        val world = base.world ?: return emptyList()
        val id = grave.id.toString()
        // 위로 넉넉히 — offset-y 를 리로드로 바꿨어도 옛 높이의 것을 찾는다.
        return world.getNearbyEntitiesByType(TextDisplay::class.java, base, 1.0, 6.0, 1.0) {
            it.persistentDataContainer.get(HOLOGRAM_KEY, PersistentDataType.STRING) == id
        }.toList()
    }

    private fun removeHologram(grave: Grave) {
        holograms.remove(grave.id)?.remove()
        taggedHolograms(grave).forEach { it.remove() }
    }

    /**
     * 홀로그램 글자를 다시 그린다.
     *
     * 설정된 주기로만 돈다. 남은 시간이 초 단위라 매 틱 다시 그릴 이유가 없고, 엔티티 메타데이터
     * 갱신은 보는 사람 전원에게 패킷이 나간다.
     */
    private fun updateHolograms(now: Long) {
        val settings = inv.config.grave.hologram
        if (!settings.enabled) return
        val everySeconds = (settings.updateIntervalTicks / 20L).coerceAtLeast(1L)
        if ((now / 1000L) % everySeconds != 0L) return

        for (grave in store.all()) {
            val display = holograms[grave.id] ?: continue
            if (!display.isValid) {
                holograms.remove(grave.id)
                continue
            }
            drawHologram(grave, display, now)
        }
    }

    private fun drawHologram(grave: Grave, display: TextDisplay, now: Long) {
        val settings = inv.config.grave.hologram
        val remaining = grave.remainingMillis(now)
        val first = if (grave.hasExpiry) {
            settings.lineFormat.replace("{remaining}", Durations.formatShort(remaining / 1000L))
        } else {
            settings.lineFormatUnlimited
        }.replace("{player}", grave.ownerName)

        val second = when (grave.state) {
            GraveState.BEING_LOOTED -> settings.lootingLineFormat
                .replace("{remaining}", inv.looting.remainingText(grave, now))

            GraveState.LOOTED -> settings.lootedLineFormat
                .replace("{looter}", grave.looterName ?: "?")
                .replace("{owner}", grave.ownerName)

            GraveState.ACTIVE -> ""
        }

        val text = if (second.isBlank()) first else "$first\n$second"
        display.text(Text.render(text))
    }

    /**
     * 서버가 꺼졌다 켜지면 홀로그램 엔티티는 사라진다 (`isPersistent = false`).
     * 살아 있는 무덤에 다시 띄운다 — **올라와 있는 청크만.** 나머지는 청크가 올라올 때 틱이 띄운다.
     * 안 올라온 청크에 띄우면 보이지 않는 채로 남았다가 겹친다.
     */
    fun respawnHolograms(now: Long) {
        repairLoaded(now)
    }

    /** 남은 아이템을 전부 이 사람에게 준다. 넘치는 것은 발밑에 흘린다. */
    fun giveAll(player: Player, contents: GraveContents) {
        for (slot in contents.slots.indices) {
            val stack = contents.take(slot) ?: continue
            give(player, stack)
        }
        if (contents.exp > 0) {
            player.giveExp(contents.exp)
            contents.exp = 0
        }
    }

    fun give(player: Player, stack: ItemStack) {
        for (leftover in player.inventory.addItem(stack).values) {
            player.world.dropItemNaturally(player.location, leftover)
        }
    }

    private companion object {
        /** 죽은 지점에서 위로 이만큼까지 빈 자리를 찾아본다. */
        const val SEARCH_HEIGHT = 16

        /**
         * 홀로그램에 무덤 id 를 적어 두는 PDC 키. 1세대(`NamespacedKey(plugin, "grave_hologram")`)와 같은
         * 키라, 1세대가 남긴 홀로그램도 같은 무덤 것으로 찾아 정리한다.
         */
        val HOLOGRAM_KEY = NamespacedKey("invkeeper", "grave_hologram")
    }
}
