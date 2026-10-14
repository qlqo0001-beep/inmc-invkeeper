package com.inmc.invkeeper.listener

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.death.DropCount
import com.inmc.invkeeper.death.Experience
import com.inmc.invkeeper.grave.GraveContents
import com.inmc.invkeeper.protection.ProtectionResult
import com.inmc.invkeeper.soulbind.AutoBindService
import com.inmc.invkeeper.soulbind.SoulbindType
import com.inmc.invkeeper.util.Ph
import kr.inmc.core.integration.ExtraInventory
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 사망 처리. 이 플러그인이 존재하는 이유다.
 *
 * 바닐라의 드랍을 **통째로 끄고**(`keepInventory = true`, drops 비우기, 경험치 0) 우리가 직접
 * 계산한다. 그래야 "몇 %만 잃는다"가 가능하다. 그래서 `keepInventory` 게임룰이 켜져 있으면
 * 인벤토리가 복제되고, [WorldRuleListener] 가 그걸 막는다.
 *
 * 순서가 의미를 갖는다.
 *
 * 1. **보호 판정** — 시간형이 먼저, 없으면 소모형 한 장. 보호되면 아무것도 잃지 않는다.
 * 2. **스택형 각인 소모** — 보호에 실패했을 때만. 각인이 "사망 N회를 버틴다"는 뜻이므로,
 *    보호권으로 막은 죽음은 세지 않는다.
 * 3. **드랍** — 각인된 아이템은 후보에서 통째로 빠진다.
 *
 * 가방 밖의 칸(커스텀아이템 장착 칸 등, core [ExtraInventory])도 **가방과 같이** 후보에 든다 — 같은 비율·같은 무덤.
 */
class PlayerDeathListener(private val inv: InvKeeper) : Listener {

    /** 마지막 사망 처리 틱·시각. 같은 틱에 또 오면 같은 죽음의 중복 발화다. */
    private data class DeathMark(val ms: Long, val tick: Int)

    private val lastDeaths = ConcurrentHashMap<UUID, DeathMark>()
    private val lastRespawnMs = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        lastRespawnMs[event.player.uniqueId] = System.currentTimeMillis()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val now = System.currentTimeMillis()
        val tick = Bukkit.getCurrentTick()

        val prev = logDeathDiag(event, player, now, tick)

        // 바닐라 드랍을 끈다. 이 셋을 빠뜨리면 우리가 계산한 몫에 더해 바닐라가 한 번 더 흘린다.
        event.keepInventory = true
        event.keepLevel = true
        event.drops.clear()
        event.droppedExp = 0

        // 동일틱 중복 사망은 무효. 같은 틱에 리스폰 없이 또 오면 같은 죽음이다 —
        // 정상 플레이라면 같은 틱에 두 번 죽을 수 없다. 무덤도, 손실도, 보호권·각인 소모도,
        // 사망 방송도 없이 넘긴다. 바닐라 드랍 끄기는 위에서 한 것으로 끝낸다.
        if (isSameTickDeath(player, tick, prev)) {
            event.deathMessage(null)
            if (inv.config.deathDebugLog) {
                inv.logger.info("[사망진단] ${player.name} 동일틱 중복 사망 무효 (tick=$tick)")
            }
            return
        }

        when (inv.protection.checkAndConsume(player, now)) {
            ProtectionResult.TIMED -> {
                inv.messages.send(
                    player,
                    "timed-protected",
                    Ph.of().remaining(inv.protection.timedRemainingText(player, now)),
                )
                return
            }

            ProtectionResult.CONSUMABLE -> {
                inv.messages.send(player, "protected")
                return
            }

            ProtectionResult.NONE -> Unit
        }

        consumeSoulbindStacks(player, now)

        val rule = inv.config.dropRules.resolve(player.world.name, player::hasPermission)
        val inventoryPercent = Math.round(rule.inventoryPercent).toInt().coerceIn(0, 100)
        val expPercent = Math.round(rule.expPercent).toInt().coerceIn(0, 100)

        val candidates = droppableSlots(player)
        val lostSlots = takeSlots(player, candidates, inventoryPercent)
        val lostExp = takeExperience(player, expPercent)

        inv.messages.send(
            player,
            "death",
            Ph.of()
                .invPercent(DropCount.actualPercent(lostSlots.size, candidates.size))
                .expPercent(expPercent)
                .itemsDropped(lostSlots.size)
                .expDropped(lostExp),
        )

        if (lostSlots.isEmpty() && lostExp <= 0) return

        // 잃은 몫을 무덤에 담아 본다. 무덤을 만들 수 없는 상황(기능 꺼짐·제외 월드·자리 없음)
        // 이면 null 이 오고, 그때는 예전처럼 바닥에 흘린다. **둘 중 하나만 일어나야 한다** —
        // 여기서 양쪽을 다 하면 아이템이 복제된다.
        // 무덤은 가방 크기만큼 담는다. 가방 밖 칸까지 잃어 넘치면 넘친 것만 바닥에.
        val contents = GraveContents(exp = lostExp)
        val overflow = ArrayList<ItemStack>()
        for ((index, stack) in lostSlots.withIndex()) {
            if (index < GraveContents.SIZE) contents.slots[index] = stack else overflow += stack
        }

        val grave = inv.graves.create(player, contents, now)
        if (inv.config.deathDebugLog) {
            inv.logger.info(
                "[사망진단] ${player.name} 무덤=${grave?.id} " +
                    "at=${grave?.worldName},${grave?.x},${grave?.y},${grave?.z} " +
                    "잃은칸=${lostSlots.size}/${candidates.size} 넘침=${overflow.size}",
            )
        }
        if (grave != null) {
            for (stack in overflow) player.world.dropItemNaturally(player.location, stack)
            return
        }

        for (stack in lostSlots) player.world.dropItemNaturally(player.location, stack)
        if (lostExp > 0) {
            player.world.spawn(player.location, org.bukkit.entity.ExperienceOrb::class.java) {
                it.experience = lostExp
            }
        }
    }

    /**
     * 사망 진단 한 줄. `death-debug-log` 이 켜져 있을 때만 콘솔에 남긴다.
     *
     * 보는 값: 서버 틱·직전 사망 처리와 간격·리스폰 이후 경과·진입 시점 사망 여부·
     * 이벤트 객체 해시(같은 객체가 두 번 돌았는지)·마지막 피해 원인.
     * 리스폰 없이 같은 틱에 또 오면 같은 죽음의 중복 발화다.
     *
     * @return 직전 사망 기록. 없으면 null. 동일틱 판정이 쓴다.
     */
    private fun logDeathDiag(event: PlayerDeathEvent, player: Player, now: Long, tick: Int): DeathMark? {
        val id = player.uniqueId
        val prev = lastDeaths[id]
        val gapMs = prev?.let { now - it.ms } ?: -1L
        val respawnAgoMs = lastRespawnMs[id]?.let { now - it } ?: -1L
        lastDeaths[id] = DeathMark(now, tick)
        if (!inv.config.deathDebugLog) return prev
        inv.logger.info(
            "[사망진단] ${player.name} tick=$tick " +
                "gapMs=$gapMs respawnAgoMs=$respawnAgoMs isDead=${player.isDead} " +
                "event=${System.identityHashCode(event)} cause=[${describeCause(player)}]",
        )
        return prev
    }

    /**
     * 같은 틱의 중복 발화인지.
     *
     * 틱이 같고, 그 사이 리스폰이 없으면 무조건 중복이다. 즉시 리스폰 환경에서 리스폰 기록이
     * 직전 사망보다 뒤면 진짜 다음 죽음이므로 그대로 처리한다.
     */
    private fun isSameTickDeath(player: Player, tick: Int, prev: DeathMark?): Boolean {
        if (prev == null || tick != prev.tick) return false
        if ((lastRespawnMs[player.uniqueId] ?: -1L) > prev.ms) return false
        return true
    }

    /** 마지막 피해 원인 한 줄. 가해자가 있으면 종류·이름까지. 없으면 `none`. */
    private fun describeCause(player: Player): String {
        val cause = player.lastDamageCause ?: return "none"
        val damager = (cause as? EntityDamageByEntityEvent)?.damager
        val who = when (damager) {
            null -> "-"
            is Player -> "Player(${damager.name})"
            else -> "${damager.type.name}(${damager.name})"
        }
        return "${cause.cause} damager=$who damage=${cause.finalDamage}"
    }

    /**
     * 스택형 각인의 잔여 횟수를 하나씩 깎는다.
     *
     * 만료된 각인을 여기서 지우지는 않는다. 0 이 된 각인은 스캐너가 다음 바퀴에 정리한다 —
     * 사망 경로에서 로어까지 다시 그리면 할 일이 늘고, 몇 초 늦어도 아무 문제가 없다.
     */
    private fun consumeSoulbindStacks(player: Player, now: Long) {
        val inventory = player.inventory
        for (slot in 0..AutoBindService.LAST_SLOT) {
            val stack = inventory.getItem(slot) ?: continue
            if (!inv.soulbinds.isSoulbound(stack)) continue
            if (inv.soulbinds.read(stack).type != SoulbindType.STACK) continue
            if (inv.soulbinds.consumeStack(stack)) inventory.setItem(slot, stack)
        }
    }

    /** 잃을 수 있는 칸 하나 — 가방 칸이거나 가방 밖 칸([ExtraInventory]). */
    private sealed interface Slot {
        fun stack(player: Player): ItemStack?
        fun clear(player: Player)
    }

    private data class Bag(val index: Int) : Slot {
        override fun stack(player: Player): ItemStack? = player.inventory.getItem(index)
        override fun clear(player: Player) = player.inventory.setItem(index, null)
    }

    private data class Extra(val provider: ExtraInventory.Provider, val index: Int) : Slot {
        override fun stack(player: Player): ItemStack? = provider.items(player).getOrNull(index)
        override fun clear(player: Player) = provider.remove(player, index)
    }

    /** 잃을 수 있는 칸들. **각인된 아이템은 통째로 빠진다** — 각인의 존재 이유가 그것이다. */
    private fun droppableSlots(player: Player): List<Slot> {
        val inventory = player.inventory
        val slots = ArrayList<Slot>(AutoBindService.LAST_SLOT + 1)
        for (slot in 0..AutoBindService.LAST_SLOT) {
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type.isAir) continue
            if (inv.soulbinds.isSoulbound(stack)) continue
            slots.add(Bag(slot))
        }
        for (provider in ExtraInventory.providers()) {
            for ((index, stack) in provider.items(player).withIndex()) {
                if (stack == null || stack.type.isAir || inv.soulbinds.isSoulbound(stack)) continue
                slots.add(Extra(provider, index))
            }
        }
        return slots
    }

    /** 무작위로 고른 칸들을 비우고 그 내용물을 돌려준다. */
    private fun takeSlots(player: Player, candidates: List<Slot>, percent: Int): List<ItemStack> {
        val count = DropCount.slots(candidates.size, percent)
        if (count <= 0) return emptyList()

        val taken = ArrayList<ItemStack>(count)
        for (slot in candidates.shuffled().take(count)) {
            val stack = slot.stack(player)?.clone() ?: continue
            if (stack.type.isAir) continue
            taken.add(stack)
            slot.clear(player)
        }
        return taken
    }

    /** 경험치를 깎고 잃은 점수를 돌려준다. 오브는 뿌리지 않는다 - 드랍 경로는 호출부가 정한다. */
    private fun takeExperience(player: Player, percent: Int): Int {
        val total = Experience.total(player.level, player.exp)
        val lost = DropCount.experience(total, percent)
        val (level, progress) = Experience.toLevel(total - lost)
        player.level = level
        player.exp = progress
        return lost
    }
}
