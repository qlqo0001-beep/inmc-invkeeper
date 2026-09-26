package com.inmc.invkeeper.scheduler

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.soulbind.AutoBindService
import com.inmc.invkeeper.util.Ph
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 매초 접속자를 훑는 단 하나의 경로.
 *
 * 두 가지 일을 한다.
 *
 * 1. **보호 만료 예고** — 전원, 매초. PDC 숫자 하나를 읽는 것이 전부라 싸다.
 * 2. **인벤토리 훑기** — 접속자를 [com.inmc.invkeeper.config.PluginConfig.soulbindScanBatches]
 *    개 그룹으로 나눠 **매초 한 그룹만** 본다. 100명·배치 5 면 매초 20명이고 5초에 한 바퀴다.
 *
 * 인벤토리 훑기 안에서 각인 만료와 자동각인을 **같이** 처리한다. 둘 다 "슬롯을 하나씩 보며
 * 아이템을 판단한다"라, 따로 돌면 같은 인벤토리를 두 번 걷게 된다. 한 번에 끝내면 자동각인의
 * 추가 비용이 사실상 0 이다.
 */
class PlayerScan(private val inv: InvKeeper) {

    /** 이미 보낸 예고. 같은 사람에게 5분 예고를 매초 보내지 않기 위한 것이다. */
    private val notified = ConcurrentHashMap<UUID, Notified>()

    private var round = 0

    fun tick(now: Long) {
        val online = Bukkit.getOnlinePlayers().toList()
        if (online.isEmpty()) {
            round++
            return
        }

        for (player in online) remindTimedProtection(player, now)

        val batches = inv.config.soulbindScanBatches
        var index = round % batches
        while (index < online.size) {
            walkInventory(online[index], now)
            index += batches
        }
        round++
    }

    fun forget(playerId: UUID) {
        notified.remove(playerId)
    }

    fun clear() {
        notified.clear()
    }

    // --- 보호 만료 예고 -------------------------------------------------------------

    private fun remindTimedProtection(player: Player, now: Long) {
        val remaining = inv.protection.timedRemainingMillis(player, now)
        if (remaining <= 0L) {
            // 보호가 끝났으면 다음 번 보호를 위해 예고 기록을 지운다.
            notified.remove(player.uniqueId)
            return
        }

        val state = notified.getOrPut(player.uniqueId) { Notified() }
        val text = inv.protection.timedRemainingText(player, now)

        if (!state.fiveMinutes && remaining <= FIVE_MINUTES_MS) {
            state.fiveMinutes = true
            inv.messages.send(player, "timed-remaining-five-minutes", Ph.of().remaining(text))
        }
        if (!state.oneMinute && remaining <= ONE_MINUTE_MS) {
            state.oneMinute = true
            inv.messages.send(player, "timed-remaining-one-minute", Ph.of().remaining(text))
        }
    }

    // --- 인벤토리 훑기 -------------------------------------------------------------

    private fun walkInventory(player: Player, now: Long) {
        val inventory = player.inventory
        for (slot in 0..AutoBindService.LAST_SLOT) {
            val stack = inventory.getItem(slot) ?: continue
            var changed = false

            // 만료가 먼저다. 각인이 풀린 아이템은 그 순간 "주인 없는 아이템"이 되고,
            // 자동각인 대상이면 바로 다음 줄에서 이 사람에게 다시 찍힌다.
            if (inv.soulbinds.expireIfDue(stack, now)) changed = true
            if (inv.autoBind.bindIfEligible(stack, player, now, notify = false)) changed = true

            if (changed) inventory.setItem(slot, stack)
        }
    }

    /** 한 번의 보호 구간 동안 어떤 예고를 이미 보냈는지. */
    private class Notified {
        var fiveMinutes = false
        var oneMinute = false
    }

    private companion object {
        const val FIVE_MINUTES_MS = 300_000L
        const val ONE_MINUTE_MS = 60_000L
    }
}
