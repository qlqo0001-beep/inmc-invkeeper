package com.inmc.invkeeper.listener

import com.inmc.invkeeper.InvKeeper
import org.bukkit.GameRule
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent

/**
 * `keepInventory` 를 꺼진 상태로 유지한다.
 *
 * 이 플러그인이 드랍을 직접 계산하므로, 게임룰이 켜져 있으면 **인벤토리가 복제된다** — 죽어도
 * 들고 있고, 우리가 무덤에도 넣어준다. 나중에 로드되는 월드가 켜진 채로 들어오는 경우가
 * 실제로 있어서 이벤트로도 받는다.
 */
class WorldRuleListener(private val inv: InvKeeper) : Listener {

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        if (!inv.config.forceKeepInventoryFalse) return
        @Suppress("removal", "DEPRECATION")
        event.world.setGameRule(GameRule.KEEP_INVENTORY, false)
    }

    /** 기동 직후 이미 로드된 월드들에 한 번 적용한다. */
    fun applyToLoadedWorlds() {
        if (!inv.config.forceKeepInventoryFalse) return
        for (world in inv.plugin.server.worlds) {
            @Suppress("removal", "DEPRECATION")
            world.setGameRule(GameRule.KEEP_INVENTORY, false)
        }
    }
}
