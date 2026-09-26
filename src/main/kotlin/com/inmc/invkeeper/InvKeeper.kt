package com.inmc.invkeeper

import com.inmc.invkeeper.config.Messages
import com.inmc.invkeeper.config.PluginConfig
import com.inmc.invkeeper.util.Ph
import kr.inmc.core.InmcHost
import kr.inmc.core.config.ConfigService
import kr.inmc.core.input.ChatPrompt
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.MMOItemsHook
import kr.inmc.core.item.BlockPlacer
import kr.inmc.core.item.ItemMatcher
import com.inmc.invkeeper.item.ItemRegistry
import com.inmc.invkeeper.grave.GraveManager
import com.inmc.invkeeper.grave.LootService
import com.inmc.invkeeper.protection.ProtectionService
import com.inmc.invkeeper.scheduler.PlayerScan
import com.inmc.invkeeper.soulbind.SoulbindGuard
import com.inmc.invkeeper.soulbind.AutoBindService
import com.inmc.invkeeper.soulbind.SoulbindManager
import kr.inmc.core.item.ItemResolver
import kr.inmc.core.util.Placeholders
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin

/**
 * 플러그인을 엮는 서비스 로케이터.
 *
 * 전부 한 번 만들고 `inv.<서비스>` 로 닿는다. 리로드는 volatile 한 설정 스냅샷 둘만 갈아끼우고
 * 서비스는 절대 다시 만들지 않는다 — 리스너·화면·열린 무덤이 낡은 참조를 들게 되기 때문이다.
 *
 * [InmcHost] 를 구현하면 core 의 프레임워크(`Menu` · `ChatPrompt` · `MenuListener`)가 그대로
 * 얹힌다. core 는 이 클래스의 나머지를 알지도 못하고 알 필요도 없다.
 */
class InvKeeper(override val plugin: JavaPlugin) : InmcHost {

    val logger: java.util.logging.Logger = plugin.logger

    override val io = ConfigService(plugin)

    /** core 의 ChatPrompt·MenuListener 가 메시지를 보낼 때 쓰는 통로. */
    override fun tell(target: CommandSender, key: String, ph: Placeholders?) =
        messages.send(target, key, ph as? Ph)

    // --- 연동 (전부 선택) --------------------------------------------------------
    val mmoItems = MMOItemsHook(logger)
    val customItems = CustomItemHook(logger)

    // --- 아이템 계층 --------------------------------------------------------------
    val itemResolver = ItemResolver(mmoItems, customItems, logger)
    val itemMatcher = ItemMatcher(mmoItems, customItems)

    // --- 설정 (리로드로 통째 교체) --------------------------------------------------
    @Volatile
    var config: PluginConfig = PluginConfig.from(YamlConfiguration())

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    // --- 도메인 서비스 --------------------------------------------------------------
    val items = ItemRegistry(this)
    val soulbinds = SoulbindManager(this)
    val protection = ProtectionService(this)
    val guard = SoulbindGuard(this)
    val players = PlayerScan(this)
    val autoBind = AutoBindService(this)
    val blockPlacer = BlockPlacer(customItems, logger)
    val graves = GraveManager(this)
    val looting = LootService(this)

    // --- 입력 --------------------------------------------------------------------
    val prompts = ChatPrompt(this)

    /** 저장된 상태를 다 읽기 전에는 false. 그 전의 상호작용은 전부 보류한다. */
    @Volatile
    var ready: Boolean = false
        private set

    fun markReady() {
        ready = true
    }
}
