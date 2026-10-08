package com.inmc.invkeeper

import com.inmc.invkeeper.command.InvKeeperCommand
import com.inmc.invkeeper.config.Messages
import com.inmc.invkeeper.config.PluginConfig
import com.inmc.invkeeper.hook.MetricsHook
import com.inmc.invkeeper.listener.ChatInputListener
import com.inmc.invkeeper.listener.GraveListener
import com.inmc.invkeeper.listener.PlayerDeathListener
import com.inmc.invkeeper.listener.ProtectionUseListener
import com.inmc.invkeeper.listener.SoulbindMoveListener
import com.inmc.invkeeper.listener.SoulbindToolListener
import com.inmc.invkeeper.listener.SoulbindUseListener
import com.inmc.invkeeper.listener.WorldRuleListener
import com.inmc.invkeeper.scheduler.Ticker
import org.bukkit.plugin.java.JavaPlugin

/**
 * 진입점. **배선만** 한다.
 *
 * 도메인 로직은 한 줄도 여기 두지 않는다. 1세대의 `InvKeeperPlugin` 은 160줄이었고 그 안에
 * keepInventory 강제·bStats·명령어 등록·리스너 등록이 뒤섞여 있어서, 무엇이 언제 준비되는지
 * 읽어내려면 전체를 훑어야 했다.
 */
class InvKeeperPlugin : JavaPlugin() {

    private lateinit var inv: InvKeeper
    private lateinit var ticker: Ticker
    private lateinit var worldRules: WorldRuleListener
    private var metrics: MetricsHook? = null

    override fun onEnable() {
        inv = InvKeeper(this)
        ticker = Ticker(inv)

        registerListeners()
        InvKeeperCommand(inv).register(this)
        // 커스텀아이템에 역할을 내놓는다. 커스텀아이템이 먼저 켜졌든 나중에 켜지든 알림이 다시 읽게 한다.
        for (role in com.inmc.invkeeper.item.InvKeeperRoles.roles()) kr.inmc.core.integration.ItemRoles.register(role)
        kr.inmc.core.integration.ItemRoles.listen(com.inmc.invkeeper.item.InvKeeperRoles.OWNER) { role ->
            if (inv.ready) inv.items.onRolesChanged(role)
        }

        // reload 가 config·messages·items 를 읽는다. 무덤은 기동 때 한 번만 읽으면 된다 —
        // 살아 있는 무덤을 리로드마다 다시 읽으면 그 사이 열려 있던 화면의 변경이 날아간다.
        reload {
            inv.graves.store.load {
                inv.graves.history.load {
                    inv.markReady()
                    // 커스텀아이템이 우리가 다 읽기 전에 꽂혔으면 그 알림은 지나갔다 — 한 번 따라잡는다.
                    if (kr.inmc.core.integration.ItemRoles.active) inv.items.onRolesChanged(null)
                    ticker.start()
                    worldRules.applyToLoadedWorlds()
                    // 홀로그램은 서버가 꺼지면 사라진다. 살아 있는 무덤에 다시 띄운다.
                    inv.graves.respawnHolograms(System.currentTimeMillis())
                    // 연동 확인이 끝난 뒤에 시작해야 집계가 정확하다.
                    metrics = MetricsHook(inv).also { it.start() }
                    logger.info(
                        "inmc-invkeeper 활성화 완료 — 아이템 ${inv.items.size}개 · 무덤 ${inv.graves.store.size}개",
                    )
                }
            }
        }
    }

    override fun onDisable() {
        if (!::inv.isInitialized) return
        metrics?.stop()
        metrics = null
        ticker.stop()
        kr.inmc.core.integration.ItemRoles.unregisterAll(com.inmc.invkeeper.item.InvKeeperRoles.OWNER)
        // 종료 경로에서는 워커를 못 믿는다. 여기서 바로 쓰고 내려간다. 커스텀아이템으로 옮긴 뒤면 이 파일은 쓰지 않는다.
        if (!inv.items.retired) inv.items.flushBlocking()
        inv.graves.flushBlocking()
        inv.io.shutdown()
    }

    private fun registerListeners() {
        worldRules = WorldRuleListener(inv)
        val manager = server.pluginManager
        manager.registerEvents(worldRules, this)
        manager.registerEvents(PlayerDeathListener(inv), this)
        manager.registerEvents(GraveListener(inv), this)
        manager.registerEvents(SoulbindToolListener(inv), this)
        manager.registerEvents(SoulbindMoveListener(inv), this)
        manager.registerEvents(SoulbindUseListener(inv), this)
        manager.registerEvents(com.inmc.invkeeper.listener.SoulbindDropListener(inv), this)
        manager.registerEvents(ProtectionUseListener(inv), this)
        manager.registerEvents(ChatInputListener(inv), this)
        manager.registerEvents(kr.inmc.core.listener.MenuListener(inv), this)
    }

    /**
     * `config.yml` · `messages.yml` · `items.yml` 을 다시 읽는다.
     *
     * 파일 읽기는 워커에서, 파싱과 반영은 메인에서 한다 — core 의 [kr.inmc.core.config.ConfigService]
     * 가 지키는 계약이다.
     */
    fun reload(then: () -> Unit = {}) {
        inv.io.copyDefault("config.yml", inv.io.file("config.yml"))
        inv.io.copyDefault("messages.yml", inv.io.file("messages.yml"))
        // 옮긴 뒤에는 배포 기본값을 다시 깔지 않는다 — 깔면 다음 시작에 또 옮긴다.
        if (!kr.inmc.core.integration.ItemRoles.isRetired(inv.io.file("items.yml"))) inv.io.copyDefault("items.yml", inv.io.file("items.yml"))

        inv.io.async({
            inv.io.load(inv.io.file("config.yml")) to inv.io.load(inv.io.file("messages.yml"))
        }) { (configYaml, messagesYaml) ->
            inv.config = PluginConfig.from(configYaml)
            inv.messages = Messages.from(messagesYaml)

            for ((priority, permissions) in inv.config.dropRules.duplicatePriorities()) {
                logger.severe("드랍 규칙 priority $priority 중복: ${permissions.joinToString(", ")}")
            }

            // 관리자가 items.yml 을 손으로 고쳤을 수 있다. 정의 객체가 전부 교체되므로
            // 열려 있는 화면은 닫는다 - 그대로 두면 버려진 객체를 계속 편집하게 되고,
            // 저장은 되는데 반영이 안 된다.
            closeOpenMenus()
            inv.items.load { then() }
        }
    }

    /**
     * 이 플러그인이 띄운 화면을 전부 닫는다.
     *
     * core 가 소유한 화면(공용 확인창)도 잡아야 하므로 클래스가 아니라 [kr.inmc.core.gui.Menu.owner]
     * 로 가려낸다. 클래스로 판별하면 core 메뉴가 청소에서 빠지고 로그에도 아무것도 안 남는다.
     */
    private fun closeOpenMenus() {
        for (player in server.onlinePlayers) {
            val holder = player.openInventory.topInventory.holder
            if (holder !is kr.inmc.core.gui.Menu || holder.owner !== inv) continue
            player.closeInventory()
        }
    }
}
