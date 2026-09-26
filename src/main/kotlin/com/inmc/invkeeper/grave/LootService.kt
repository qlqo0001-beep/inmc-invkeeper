package com.inmc.invkeeper.grave

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.util.Ph
import kr.inmc.core.util.Durations
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 도굴 시전.
 *
 * 남의 무덤을 도굴 도구로 우클릭하면 시전이 시작되고, 설정된 시간이 지나야 열린다. 그 사이
 * **주인이 무덤을 열면 시전은 취소된다** — 이게 도굴을 도박으로 만드는 규칙이다.
 *
 * 시전 상태는 메모리에만 둔다. 서버가 꺼지면 취소되는 것이 맞다 — 다시 켜졌을 때 도굴이
 * 몇 시간 진행된 채로 남아 있으면 주인이 손쓸 틈이 없다.
 */
class LootService(private val inv: InvKeeper) {

    /** 무덤 id → 진행 중인 시전. */
    private val sessions = ConcurrentHashMap<UUID, Session>()

    class Session(val looterId: UUID, val looterName: String, val endsAt: Long)

    fun isLooting(grave: Grave): Boolean = sessions.containsKey(grave.id)

    fun remainingText(grave: Grave, now: Long): String {
        val session = sessions[grave.id] ?: return "0초"
        return Durations.formatShort(((session.endsAt - now).coerceAtLeast(0L)) / 1000L)
    }

    /**
     * 시전을 시작한다.
     *
     * @return 시작했으면 true. 실패 사유는 이 안에서 알린다.
     */
    fun start(player: Player, grave: Grave, castSeconds: Int, now: Long): Boolean {
        if (grave.ownerId == player.uniqueId) {
            inv.messages.send(player, "grave-loot-self-blocked")
            return false
        }
        if (sessions.containsKey(grave.id)) {
            inv.messages.send(player, "grave-loot-already-in-progress")
            return false
        }

        sessions[grave.id] = Session(player.uniqueId, player.name, now + castSeconds * 1000L)
        grave.state = GraveState.BEING_LOOTED
        grave.looterId = player.uniqueId
        grave.looterName = player.name
        inv.graves.store.markDirty()

        inv.messages.send(player, "grave-loot-start-caster", Ph.of().seconds(castSeconds.toLong()))
        Bukkit.getPlayer(grave.ownerId)?.let { inv.messages.send(it, "grave-loot-start-owner-alert") }
        return true
    }

    /**
     * 주인이 무덤을 확인해 도굴을 막는다.
     *
     * 주인이 자기 무덤을 여는 **모든** 경로에서 불린다. 도굴 중이 아니면 아무 일도 하지 않는다.
     */
    fun cancelByOwner(grave: Grave, owner: Player) {
        val session = sessions.remove(grave.id) ?: return
        grave.state = GraveState.ACTIVE
        grave.looterId = null
        grave.looterName = null
        inv.graves.store.markDirty()

        inv.messages.send(owner, "grave-loot-blocked-owner")
        Bukkit.getPlayer(session.looterId)?.let {
            inv.messages.send(it, "grave-loot-cancelled", Ph.of().owner(owner.name))
        }
    }

    /** 무덤이 사라질 때 딸린 시전도 버린다. */
    fun forget(graveId: UUID) {
        sessions.remove(graveId)
    }

    /** 시전이 끝난 무덤을 [GraveState.LOOTED] 로 넘긴다. */
    fun tick(now: Long) {
        if (sessions.isEmpty()) return
        for ((graveId, session) in sessions.entries.toList()) {
            if (now < session.endsAt) continue
            sessions.remove(graveId)

            val grave = inv.graves.store.get(graveId)
            if (grave == null) continue

            grave.state = GraveState.LOOTED
            grave.looterId = session.looterId
            grave.looterName = session.looterName
            inv.graves.store.markDirty()

            Bukkit.getPlayer(session.looterId)
                ?.let { inv.messages.send(it, "grave-loot-complete-caster") }
        }
    }
}
