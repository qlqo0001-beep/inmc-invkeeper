package com.inmc.invkeeper.command

import com.inmc.invkeeper.InvKeeper
import kr.inmc.core.store.DefinitionKey
import com.inmc.invkeeper.grave.GraveListMenu
import com.inmc.invkeeper.item.ItemKind
import com.inmc.invkeeper.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * `/인벤키퍼` 한 트리.
 *
 * 한글 인자는 전부 [StringArgumentType.greedyString] 이다. Brigadier 의 `word()`/`string()` 은
 * 한글 첫 글자에서 멈춘다 (가이드 함정 6).
 */
class InvKeeperCommand(private val inv: InvKeeper) {

    fun register(plugin: JavaPlugin) {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(tree().build(), "INMC 인벤키퍼", listOf("invkeeper", "ik"))
        }
    }

    // --- 추천 -------------------------------------------------------------------

    private val itemKeys = SuggestionProvider<CommandSourceStack> { _, builder ->
        inv.items.keys()
            .filter { it.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    private val kinds = SuggestionProvider<CommandSourceStack> { _, builder ->
        ItemKind.entries
            .filter { it.name.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it.name) }
        builder.buildFuture()
    }

    private val players = SuggestionProvider<CommandSourceStack> { _, builder ->
        Bukkit.getOnlinePlayers()
            .filter { it.name.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it.name) }
        builder.buildFuture()
    }

    // --- 트리 -------------------------------------------------------------------

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("인벤키퍼")
            .executes { ctx -> usage(ctx.source.sender) }

            .then(
                Commands.literal("상태").requires { it.sender.hasPermission(STATUS) }
                    .executes { ctx -> status(ctx.source.sender) },
            )

            .then(
                Commands.literal("리로드").requires(::isAdmin)
                    .executes { ctx -> reload(ctx.source.sender) },
            )

            .then(
                Commands.literal("관리").requires(::isAdmin)
                    .executes { ctx -> openGui(ctx.source.sender) },
            )

            .then(
                Commands.literal("등록").requires(::isAdmin)
                    .then(
                        Commands.argument("종류", StringArgumentType.word()).suggests(kinds)
                            .then(
                                Commands.argument("이름", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        register(
                                            ctx.source.sender,
                                            StringArgumentType.getString(ctx, "종류"),
                                            StringArgumentType.getString(ctx, "이름"),
                                        )
                                    },
                            ),
                    ),
            )

            .then(
                Commands.literal("등록해제").requires(::isAdmin)
                    .then(
                        Commands.argument("이름", StringArgumentType.greedyString()).suggests(itemKeys)
                            .executes { ctx ->
                                unregister(ctx.source.sender, StringArgumentType.getString(ctx, "이름"))
                            },
                    ),
            )

            .then(
                Commands.literal("목록").requires(::isAdmin)
                    .executes { ctx -> list(ctx.source.sender) },
            )

            .then(
                Commands.literal("지급").requires(::isAdmin)
                    .then(
                        Commands.argument("대상", StringArgumentType.word()).suggests(players)
                            .then(
                                Commands.argument("개수", IntegerArgumentType.integer(1, 64))
                                    .then(
                                        Commands.argument("이름", StringArgumentType.greedyString())
                                            .suggests(itemKeys)
                                            .executes { ctx ->
                                                give(
                                                    ctx.source.sender,
                                                    StringArgumentType.getString(ctx, "대상"),
                                                    IntegerArgumentType.getInteger(ctx, "개수"),
                                                    StringArgumentType.getString(ctx, "이름"),
                                                )
                                            },
                                    ),
                            ),
                    ),
            )

            .then(
                Commands.literal("각인")
                    .then(
                        Commands.literal("확인")
                            .executes { ctx -> inspect(ctx.source.sender) },
                    )
                    .then(
                        Commands.literal("해제").requires(::isAdmin)
                            .executes { ctx -> unbind(ctx.source.sender) },
                    ),
            )

            .then(
                Commands.literal("무덤").requires(::isGraveAdmin)
                    .then(
                        Commands.literal("목록")
                            .executes { ctx ->
                                // 1세대처럼 화면으로 연다(클릭하면 그 무덤으로 간다). 콘솔은 채팅 목록.
                                val sender = ctx.source.sender
                                if (sender is Player) GraveListMenu(inv, sender).open(sender).let { 1 } else graveList(sender)
                            },
                    )
                    .then(
                        // 1세대 `/invkeeper grave reload` — 무덤 설정 다시 읽기 + 홀로그램 다시 띄우기.
                        Commands.literal("리로드")
                            .executes { ctx ->
                                val sender = ctx.source.sender
                                (inv.plugin as? com.inmc.invkeeper.InvKeeperPlugin)?.reload {
                                    inv.graves.respawnHolograms(System.currentTimeMillis())
                                    inv.messages.send(sender, "reloaded")
                                }
                                1
                            },
                    )
                    .then(
                        Commands.literal("기록")
                            .then(
                                Commands.argument("대상", StringArgumentType.greedyString())
                                    .suggests(players)
                                    .executes { ctx ->
                                        graveHistory(
                                            ctx.source.sender,
                                            StringArgumentType.getString(ctx, "대상"),
                                        )
                                    },
                            ),
                    ),
            )

    // --- 동작 -------------------------------------------------------------------

    private fun isAdmin(source: CommandSourceStack): Boolean = source.sender.hasPermission(ADMIN)

    /**
     * 무덤 관리. 1세대는 `invkeeper.grave.admin` 을 따로 두어 운영진에게 무덤만 맡길 수 있었다.
     * 그걸 `admin` 하나로 합치면 그렇게 권한을 나눠 둔 서버에서 운영진이 무덤 명령을 잃는다.
     */
    private fun isGraveAdmin(source: CommandSourceStack): Boolean =
        source.sender.hasPermission(ADMIN) || source.sender.hasPermission(GRAVE_ADMIN)

    private fun usage(sender: CommandSender): Int {
        inv.messages.send(sender, "usage")
        return 1
    }

    private fun openGui(sender: CommandSender): Int {
        val player = sender as? Player ?: return notPlayer(sender)
        if (!inv.ready) {
            inv.messages.send(sender, "not-ready")
            return 1
        }
        com.inmc.invkeeper.gui.ItemListMenu(inv, player).open(player)
        return 1
    }

    private fun status(sender: CommandSender): Int {
        val player = sender as? Player ?: return notPlayer(sender)
        val now = System.currentTimeMillis()
        val remaining = inv.protection.timedRemainingMillis(player, now)
        if (remaining > 0) {
            inv.messages.send(
                player,
                "timed-already-active",
                Ph.of().remaining(inv.protection.timedRemainingText(player, now)),
            )
        } else {
            val rule = inv.config.dropRules.resolve(player.world.name, player::hasPermission)
            sender.sendMessage(
                Text.render(
                    "<gray>현재 월드에서 사망 시 <white>인벤 ${Math.round(rule.inventoryPercent)}%" +
                        " · 경험치 ${Math.round(rule.expPercent)}%</white> 를 잃습니다.</gray>",
                ),
            )
        }
        return 1
    }

    private fun reload(sender: CommandSender): Int {
        (inv.plugin as? com.inmc.invkeeper.InvKeeperPlugin)?.reload {
            inv.messages.send(sender, "reloaded")
        }
        return 1
    }

    private fun register(sender: CommandSender, rawKind: String, rawKey: String): Int {
        val player = sender as? Player ?: return notPlayer(sender)
        val kind = ItemKind.parse(rawKind) ?: run {
            inv.messages.send(sender, "unknown-item", Ph.of().item(rawKind))
            return 1
        }
        val key = rawKey.trim().lowercase()
        if (!DefinitionKey.isValid(key)) {
            sender.sendMessage(Text.render("<red>이름은 소문자 영문·숫자·한글·_- 만 쓸 수 있습니다 (최대 32자).</red>"))
            return 1
        }
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir) {
            inv.messages.send(sender, "hand-empty")
            return 1
        }

        inv.items.register(key, kind, hand)
        inv.messages.send(sender, "registered", Ph.of().item(key))
        return 1
    }

    private fun unregister(sender: CommandSender, rawKey: String): Int {
        val key = rawKey.trim().lowercase()
        if (!inv.items.unregister(key)) {
            inv.messages.send(sender, "unknown-item", Ph.of().item(key))
            return 1
        }
        inv.messages.send(sender, "unregistered", Ph.of().item(key))
        return 1
    }

    private fun list(sender: CommandSender): Int {
        val all = inv.items.all()
        if (all.isEmpty()) {
            sender.sendMessage(Text.render("<gray>등록된 아이템이 없습니다. <white>/인벤키퍼 등록</white> 으로 추가하세요.</gray>"))
            return 1
        }
        sender.sendMessage(Text.render("<gray>등록 아이템 <white>${all.size}</white>개</gray>"))
        for (definition in all) {
            val auto = if (definition.autoBind.enabled) " <aqua>[자동각인]</aqua>" else ""
            sender.sendMessage(
                Text.render(
                    "<gray>· <white>${definition.key}</white> <dark_gray>(${definition.kind.label})</dark_gray>" +
                        " ${definition.label()}$auto",
                ),
            )
        }
        return 1
    }

    private fun give(sender: CommandSender, targetName: String, amount: Int, rawKey: String): Int {
        val target = Bukkit.getPlayerExact(targetName) ?: run {
            inv.messages.send(sender, "player-not-found", Ph.of().player(targetName))
            return 1
        }
        val key = rawKey.trim().lowercase()
        val definition = inv.items.get(key) ?: run {
            inv.messages.send(sender, "unknown-item", Ph.of().item(key))
            return 1
        }
        val stack = inv.items.create(definition, amount, target) ?: run {
            sender.sendMessage(Text.render("<red>'${key}' 아이템을 만들 수 없습니다 (연동 플러그인 확인).</red>"))
            return 1
        }

        inv.graves.give(target, stack)
        inv.messages.send(
            sender,
            "given",
            Ph.of().player(target.name).item(key).amount(amount),
        )
        return 1
    }

    private fun inspect(sender: CommandSender): Int {
        val player = sender as? Player ?: return notPlayer(sender)
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir) {
            inv.messages.send(sender, "hand-empty")
            return 1
        }
        val now = System.currentTimeMillis()
        val state = inv.soulbinds.read(hand)
        if (state.type == com.inmc.invkeeper.soulbind.SoulbindType.NONE) {
            sender.sendMessage(Text.render("<gray>각인이 없는 아이템입니다.</gray>"))
            return 1
        }
        sender.sendMessage(
            Text.render(
                "<gray>주인 <white>${inv.soulbinds.nameOf(state.owner)}</white> · " +
                    if (state.type == com.inmc.invkeeper.soulbind.SoulbindType.STACK) {
                        "남은 횟수 <white>${if (state.stacks < 0) "무한" else state.stacks}</white>"
                    } else {
                        "남은 시간 <white>${state.remainingText(now)}</white>"
                    },
            ),
        )
        return 1
    }

    private fun unbind(sender: CommandSender): Int {
        val player = sender as? Player ?: return notPlayer(sender)
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir) {
            inv.messages.send(sender, "hand-empty")
            return 1
        }
        inv.soulbinds.remove(hand)
        player.inventory.setItemInMainHand(hand)
        inv.messages.send(sender, "soulbound-unbound")
        return 1
    }

    private fun graveList(sender: CommandSender): Int {
        val all = inv.graves.store.all()
        sender.sendMessage(Text.render("<gray>살아 있는 무덤 <white>${all.size}</white>개</gray>"))
        val now = System.currentTimeMillis()
        for (grave in all.take(GRAVE_LIST_LIMIT)) {
            val remaining = if (grave.hasExpiry) {
                kr.inmc.core.util.Durations.formatShort(grave.remainingMillis(now) / 1000L)
            } else {
                "무제한"
            }
            sender.sendMessage(
                Text.render(
                    "<gray>· <white>${grave.ownerName}</white> " +
                        "(${grave.worldName}, ${grave.x}, ${grave.y}, ${grave.z}) " +
                        "<dark_gray>${grave.state.name} · $remaining</dark_gray>",
                ),
            )
        }
        if (all.size > GRAVE_LIST_LIMIT) {
            sender.sendMessage(Text.render("<dark_gray>… 외 ${all.size - GRAVE_LIST_LIMIT}개</dark_gray>"))
        }
        return 1
    }

    /**
     * 기록 화면을 연다.
     *
     * 오프라인 플레이어도 볼 수 있어야 한다 - 잃은 것을 돌려달라는 문의는 보통 접속을 끊은
     * 뒤에 온다. 이름 -> uuid 는 core 의 [kr.inmc.core.store.Profile] 이 들고 있다.
     */
    private fun graveHistory(sender: CommandSender, rawName: String): Int {
        val viewer = sender as? Player ?: return notPlayer(sender)
        val name = rawName.trim()

        val online = Bukkit.getPlayerExact(name)
        val ownerId = online?.uniqueId ?: knownPlayerId(name)
        if (ownerId == null) {
            inv.messages.send(sender, "player-not-found", Ph.of().player(name))
            return 1
        }

        com.inmc.invkeeper.grave.GraveHistoryMenu(inv, viewer, ownerId, name).open(viewer)
        return 1
    }

    /** core 의 공용 플레이어 저장소에서 이름으로 uuid 를 찾는다. 없으면 null. */
    private fun knownPlayerId(name: String): java.util.UUID? {
        val store = runCatching { kr.inmc.core.CorePlugin.get().players }.getOrNull() ?: return null
        return store.knownPlayers().firstOrNull { id ->
            kr.inmc.core.store.Profile.nameOf(store, id).equals(name, ignoreCase = true)
        }
    }

    private fun notPlayer(sender: CommandSender): Int {
        inv.messages.send(sender, "player-only")
        return 1
    }

    private companion object {
        const val ADMIN = "invkeeper.admin"
        const val GRAVE_ADMIN = "invkeeper.grave.admin"
        const val STATUS = "invkeeper.status"
        const val GRAVE_LIST_LIMIT = 20
    }
}
