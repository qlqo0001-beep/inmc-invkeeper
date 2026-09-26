package com.inmc.invkeeper.hook

import com.inmc.invkeeper.InvKeeper
import org.bstats.bukkit.Metrics
import org.bstats.charts.SimplePie

/**
 * bStats 집계.
 *
 * **개인 식별 값은 보내지 않는다.** 이름·UUID·좌표·아이템 내용은 전부 제외하고 "이 기능을
 * 켜 둔 서버가 얼마나 되는가"만 센다. 정확한 개수도 의미가 없어 구간으로 뭉친다.
 *
 * 차트 콜백은 **비메인 스레드에서 돈다.** Bukkit API 를 건드리면 안 되고, 불변 스냅샷
 * ([com.inmc.invkeeper.config.PluginConfig])과 동시성 컬렉션만 읽는다.
 */
class MetricsHook(private val inv: InvKeeper) {

    private var metrics: Metrics? = null

    fun start() {
        val instance = Metrics(inv.plugin, PLUGIN_ID)
        instance.addCustomChart(SimplePie("grave_enabled") { onOff(inv.config.grave.enabled) })
        instance.addCustomChart(SimplePie("hologram_enabled") { onOff(inv.config.grave.hologram.enabled) })
        instance.addCustomChart(SimplePie("mmoitems") { onOff(inv.mmoItems.isEnabled) })
        instance.addCustomChart(SimplePie("custom_items") { onOff(inv.customItems.isEnabled) })
        instance.addCustomChart(SimplePie("registered_items") { bucket(inv.items.size) })
        instance.addCustomChart(
            SimplePie("auto_bind_items") { bucket(inv.items.all().count { it.autoBind.enabled }) },
        )
        instance.addCustomChart(
            SimplePie("protection_items") { bucket(inv.items.all().count { it.kind.isProtection }) },
        )
        metrics = instance
    }

    fun stop() {
        metrics?.shutdown()
        metrics = null
    }

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    private fun bucket(count: Int): String = when {
        count == 0 -> "0"
        count <= 5 -> "1-5"
        count <= 20 -> "6-20"
        count <= 50 -> "21-50"
        else -> "51+"
    }

    private companion object {
        /**
         * bStats 플러그인 id.
         *
         * ⚠ 1세대 `InvKeeper` 의 번호를 물려받았다. 플러그인 이름이 `inmc-invkeeper` 로
         * 바뀌었으므로 **배포 전에 bStats 에 새로 등록하고 이 값을 바꿔야** 통계가 섞이지
         * 않는다. 그때까지는 옛 플러그인과 같은 대시보드에 집계된다.
         */
        const val PLUGIN_ID = 32889
    }
}
