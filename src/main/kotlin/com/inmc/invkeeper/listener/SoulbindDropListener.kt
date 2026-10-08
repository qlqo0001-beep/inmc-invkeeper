package com.inmc.invkeeper.listener

import com.inmc.invkeeper.InvKeeper
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent

/**
 * 바닥에 떨어진 각인 아이템을 불·용암·선인장·폭발 같은 피해에서 지킨다(사용자 요청 2026-10-07 "불과 선인장 같은 가시에 사라지지 않게").
 *
 * 바닥의 아이템은 피해를 한 번 받으면 그대로 사라진다. 각인은 "잃지 않는 아이템"이라는 뜻이라 그 길도 막는다 — 남은 아이템은
 * 주인이 주울 때까지 그 자리에 있다(남의 줍기는 [SoulbindMoveListener] 가 막는다).
 * **공허(VOID)는 막지 않는다** — 막으면 세계 밑에서 끝없이 떨어진다. 바닥 아이템이 몇 분 뒤 없어지는 것은 피해가 아니라 여기 대상이 아니다.
 */
class SoulbindDropListener(private val inv: InvKeeper) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val item = event.entity as? Item ?: return
        if (event.cause == EntityDamageEvent.DamageCause.VOID) return
        val stack = item.itemStack
        if (!inv.soulbinds.isSoulbound(stack)) return
        if (!inv.soulbinds.read(stack).isAlive(System.currentTimeMillis())) return
        event.isCancelled = true
        // 불이 붙은 채 남아 매 틱 다시 타지 않게 끈다.
        if (item.fireTicks > 0) item.fireTicks = 0
    }
}
