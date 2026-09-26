package com.inmc.invkeeper.soulbind

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.item.ItemDefinition
import com.inmc.invkeeper.util.Ph
import kr.inmc.core.item.StoredItem
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 등록된 아이템을 **처음 손에 넣은 사람**에게 자동으로 각인을 찍는다.
 *
 * "처음"을 판정하려고 따로 기록을 두지 않는다 — **각인 소유자가 아직 없으면 처음**이다.
 * 각인 자체가 그 기록이므로 저장할 것이 없고, 그래서 이 판정은 자연히 멱등하다. 같은 아이템을
 * 백 번 집어도 두 번째부터는 주인이 있어 그냥 넘어간다.
 *
 * 각인의 세기는 **아이템마다 따로** 설정한다 ([ItemDefinition.autoBind]) — 어떤 무기는 영구,
 * 어떤 소모품은 30분, 어떤 것은 사망 3회까지. 동적(참조) 등록이든 스냅샷 등록이든 상관없다.
 * 식별은 core 의 [StoredItem] 이 하고, 이 클래스는 그 결과에만 반응한다.
 */
class AutoBindService(private val inv: InvKeeper) {

    /**
     * 대상이면 각인을 찍는다.
     *
     * @param notify 찍었을 때 본인에게 알릴지. 스캐너처럼 조용히 도는 경로는 false 로 부른다.
     * @return 실제로 찍었으면 true.
     */
    fun bindIfEligible(
        stack: ItemStack?,
        player: Player,
        now: Long = System.currentTimeMillis(),
        notify: Boolean = true,
    ): Boolean {
        val definition = eligibleFor(stack, now) ?: return false
        inv.soulbinds.apply(stack!!, player.uniqueId, definition.autoBind.strength, now)

        if (notify) {
            val state = inv.soulbinds.read(stack)
            inv.messages.send(
                player,
                "autobind-applied",
                Ph.of().item(definition.label()).remaining(state.remainingText(now)),
            )
        }
        return true
    }

    /**
     * 각인을 찍지 않고 "대상인지"만 본다. 스캐너가 배치로 돌 때 쓴다.
     *
     * 순서가 중요하다. 가장 싼 검사(각인 여부)를 먼저 한다 — 인벤토리를 훑는 경로에서
     * 대부분의 아이템은 등록조차 안 된 평범한 아이템이고, 등록 조회가 더 비싸다.
     */
    fun eligibleFor(stack: ItemStack?, now: Long = System.currentTimeMillis()): ItemDefinition? {
        if (stack == null || stack.type.isAir) return null

        // 이미 주인이 있으면 "처음"이 아니다. 만료돼 소유자가 지워졌다면 다음 사람이 새 주인이
        // 되는데, 그게 의도된 동작이다 - 각인이 풀린 아이템은 다시 주인 없는 아이템이다.
        if (inv.soulbinds.isSoulbound(stack)) {
            val state = inv.soulbinds.read(stack)
            if (state.owner != null && state.isAlive(now)) return null
        }

        val definition = inv.items.identify(stack) ?: return null
        return definition.takeIf { it.autoBind.enabled }
    }

    /**
     * 플레이어 인벤토리 전체를 훑어 대상에 각인을 찍는다.
     *
     * `/give` 로 받거나 다른 플러그인이 넣어준 아이템처럼 **이벤트가 없는 경로**를 이 쓸기가
     * 덮는다. 만료 스캐너가 어차피 같은 인벤토리를 도니 거기 얹어 추가 비용을 0 으로 만든다.
     *
     * @return 새로 각인한 개수.
     */
    fun sweep(player: Player, now: Long = System.currentTimeMillis()): Int {
        var bound = 0
        val inventory = player.inventory
        // 슬롯 번호로 읽고 다시 써야 한다. `contents` 가 돌려주는 배열의 ItemStack 은 복제본이라
        // 그걸 고쳐봐야 인벤토리에 반영되지 않는다.
        for (slot in 0..LAST_SLOT) {
            val stack = inventory.getItem(slot) ?: continue
            if (!bindIfEligible(stack, player, now, notify = false)) continue
            inventory.setItem(slot, stack)
            bound++
        }
        return bound
    }

    companion object {
        /** 보관칸 0~35 · 방어구 36~39 · 왼손 40. 1세대와 같은 범위다. */
        const val LAST_SLOT = 40
    }
}
