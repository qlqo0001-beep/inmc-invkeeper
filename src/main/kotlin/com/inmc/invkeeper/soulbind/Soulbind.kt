package com.inmc.invkeeper.soulbind

import com.inmc.invkeeper.item.BindStrength
import kr.inmc.core.util.Durations
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** 아이템에 찍힌 각인이 무엇으로 세어지는지. */
enum class SoulbindType { NONE, TIME, STACK }

/**
 * 각인 한 건을 읽어낸 값. 아이템에서 뽑아낸 스냅샷이라 아이템이 바뀌어도 변하지 않는다.
 *
 * [expiry] 는 **절대 시각**(epoch ms)이고 [BindStrength.INFINITE] 가 무한을 뜻한다.
 * 가이드 함정 14 가 "남은 시간을 절대 시각으로 저장하지 말라"고 경고하지만, 그건 서버가
 * 꺼져 있는 동안 시간이 흐르면 안 되는 값(시즌·쿨다운)에 대한 이야기다. 각인은 반대로
 * **실제 시간으로 만료되는 것이 사양**이다 — 서버를 꺼둬도 만료는 흘러야 한다.
 */
data class SoulbindState(
    val type: SoulbindType,
    val owner: UUID?,
    val expiry: Long,
    val stacks: Int,
) {

    val isInfinite: Boolean
        get() = when (type) {
            SoulbindType.TIME -> expiry < 0
            SoulbindType.STACK -> stacks < 0
            SoulbindType.NONE -> false
        }

    /** 이 각인이 지금 시점에 아직 살아 있는지. */
    fun isAlive(now: Long): Boolean = when (type) {
        SoulbindType.NONE -> false
        SoulbindType.TIME -> expiry < 0 || now <= expiry
        SoulbindType.STACK -> stacks != 0
    }

    /** 남은 밀리초. 무한이면 [Long.MAX_VALUE]. */
    fun remainingMillis(now: Long): Long =
        if (type != SoulbindType.TIME || expiry < 0) Long.MAX_VALUE
        else (expiry - now).coerceAtLeast(0L)

    /** 사람이 읽을 남은 시간. 무한이면 "무한". */
    fun remainingText(now: Long): String {
        val remaining = remainingMillis(now)
        if (remaining == Long.MAX_VALUE) return "무한"
        return Durations.formatShort(remaining / 1000L)
    }

    companion object {
        val NONE = SoulbindState(SoulbindType.NONE, null, BindStrength.INFINITE, -1)
    }
}

/**
 * 각인이 아이템에 남기는 PDC 키들.
 *
 * **네임스페이스가 `invkeeper` 로 고정돼 있다.** `NamespacedKey(plugin, …)` 를 쓰면 플러그인
 * 이름(`inmc-invkeeper`)이 네임스페이스가 되는데, 그러면 1세대가 찍어둔 각인 아이템을 하나도
 * 못 읽는다 — 서버에 이미 돌아다니는 각인 무기·방어구가 전부 평범한 아이템이 된다.
 * 키 이름도 1세대와 글자 단위로 같다. 이 파일에서 이 값들을 바꾸면 기존 아이템이 죽는다.
 */
object SoulbindKeys {

    private const val NAMESPACE = "invkeeper"

    @Suppress("DEPRECATION")
    private fun key(name: String) = NamespacedKey(NAMESPACE, name)

    val OWNER: NamespacedKey = key("soulbind_owner")
    val EXPIRY: NamespacedKey = key("soulbind_expiry")
    val STACKS: NamespacedKey = key("soulbind_stacks")

    /**
     * 우리가 찍어 넣은 로어 줄의 **완성된 문자열**.
     *
     * 지울 때 이것과 같은 줄만 지운다. 형식 문자열로 다시 만들어 맞추려 하면 그 사이 설정이
     * 바뀐 경우 못 지우고, 서버 재기동을 넘겨 살아남아야 하므로 메모리에 둘 수도 없다.
     */
    val LORE: NamespacedKey = key("soulbind_lore_text")

    val OWNER_TYPE: PersistentDataType<String, String> = PersistentDataType.STRING
    val EXPIRY_TYPE: PersistentDataType<Long, Long> = PersistentDataType.LONG
    val STACKS_TYPE: PersistentDataType<Int, Int> = PersistentDataType.INTEGER
}
