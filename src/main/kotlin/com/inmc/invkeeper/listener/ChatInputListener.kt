package com.inmc.invkeeper.listener

import com.inmc.invkeeper.InvKeeper
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * 관리 화면의 채팅 입력을 core 의 [kr.inmc.core.input.ChatPrompt] 로 넘긴다.
 *
 * 비동기 스레드에서 돈다. 콜백을 메인(정확히는 그 플레이어의 리전)으로 되돌리는 일은
 * `ChatPrompt` 가 안에서 처리한다.
 */
class ChatInputListener(private val inv: InvKeeper) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!inv.prompts.isWaiting(player.uniqueId)) return
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (inv.prompts.submit(player, text)) event.isCancelled = true
    }
}
