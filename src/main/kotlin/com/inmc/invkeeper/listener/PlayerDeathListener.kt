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
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack

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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val now = System.currentTimeMillis()

        // 바닐라 드랍을 끈다. 이 셋을 빠뜨리면 우리가 계산한 몫에 더해 바닐라가 한 번 더 흘린다.
        event.keepInventory = true
        event.keepLevel = true
        event.drops.clear()
        event.droppedExp = 0

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

        if (inv.graves.create(player, contents, now) != null) {
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
