package com.inmc.invkeeper.util

import kr.inmc.core.util.TokenBag
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * 메시지 한 번 렌더링에 쓰이는 토큰 주머니.
 *
 * 1세대는 호출부마다 `String.replace("{owner}", …)` 를 손으로 이어 붙였다. 토큰 이름이 코드
 * 여기저기 흩어져 있어 `messages.yml` 의 어떤 키에 어떤 토큰이 유효한지 아무도 몰랐고, 오타는
 * 조용히 원문을 그대로 출력했다. 여기 한 곳에 모으면 별칭표가 곧 문서가 된다.
 *
 * **별칭표는 1세대 `messages.yml` 에서 그대로 뽑았다.** 관리자가 고쳐 쓰던 파일을 그대로
 * 옮겨와도 돌아야 하기 때문이다. 한글 표기는 이 서버의 다른 5세대 플러그인과 맞추려고 덧붙인
 * 것이고, 둘 중 어느 쪽으로 적어도 같은 값이 들어간다.
 */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES

    fun player(name: String): Ph = put(PLAYER, name)

    fun player(player: Player): Ph = put(PLAYER, player.name)

    /** 각인·무덤의 소유자. 비소유자에게 "누구 것인지" 알릴 때 쓴다. */
    fun owner(name: String): Ph = put(OWNER, name)

    fun looter(name: String): Ph = put(LOOTER, name)

    fun item(name: String): Ph = put(ITEM, name)

    /** 이미 사람이 읽을 수 있게 만들어진 남은 시간 문자열. */
    fun remaining(text: String): Ph = put(REMAINING, text)

    /** 만료 시각(절대 시각 표기). 각인 로어에 쓴다. */
    fun expiry(text: String): Ph = put(EXPIRY, text)

    fun duration(value: Int): Ph = put(DURATION, value.toString())

    fun seconds(value: Long): Ph = put(SECONDS, value.toString())

    /** 각인 스택 잔량. 음수는 무한을 뜻한다. */
    fun stacks(value: Int): Ph = put(STACKS, if (value < 0) "무한" else value.toString())

    /** 각인 타입 이름 (충돌 안내용). */
    fun type(text: String): Ph = put(TYPE, text)

    fun max(value: Int): Ph = put(MAX, value.toString())

    fun invPercent(value: Int): Ph = put(INV_PERCENT, value.toString())

    fun expPercent(value: Int): Ph = put(EXP_PERCENT, value.toString())

    fun itemsDropped(value: Int): Ph = put(ITEMS_DROPPED, value.toString())

    fun expDropped(value: Int): Ph = put(EXP_DROPPED, value.toString())

    fun amount(value: Int): Ph = put(AMOUNT, value.toString())

    fun world(name: String): Ph = put(WORLD, name)

    /** 무덤 위치. 월드와 좌표 넷을 한 번에 채운다 - 따로 넣다 빠뜨리는 일이 잦았다. */
    fun at(location: Location): Ph = apply {
        put(WORLD, location.world?.name ?: "?")
        put(X, location.blockX.toString())
        put(Y, location.blockY.toString())
        put(Z, location.blockZ.toString())
    }

    fun copy(): Ph = copyValuesInto(Ph())

    companion object {

        fun of(): Ph = Ph()

        const val PLAYER = "player"
        const val OWNER = "owner"
        const val LOOTER = "looter"
        const val ITEM = "item"
        const val REMAINING = "remaining"
        const val EXPIRY = "expiry"
        const val DURATION = "duration"
        const val SECONDS = "seconds"
        const val STACKS = "stacks"
        const val TYPE = "type"
        const val MAX = "max"
        const val INV_PERCENT = "invPercent"
        const val EXP_PERCENT = "expPercent"
        const val ITEMS_DROPPED = "itemsDropped"
        const val EXP_DROPPED = "expDropped"
        const val AMOUNT = "amount"
        const val WORLD = "world"
        const val X = "x"
        const val Y = "y"
        const val Z = "z"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어}", "{player}"),
            OWNER to listOf("{주인}", "{소유자}", "{owner}"),
            LOOTER to listOf("{도굴자}", "{looter}"),
            ITEM to listOf("{아이템}", "{item_name}", "{item}"),
            REMAINING to listOf("{남은시간}", "{remaining}"),
            EXPIRY to listOf("{만료}", "{expiry}"),
            DURATION to listOf("{기간}", "{duration}"),
            SECONDS to listOf("{초}", "{seconds}"),
            STACKS to listOf("{스택}", "{stacks}"),
            TYPE to listOf("{타입}", "{type}"),
            MAX to listOf("{최대}", "{max}"),
            INV_PERCENT to listOf("{인벤비율}", "{inv_percent}"),
            EXP_PERCENT to listOf("{경험치비율}", "{exp_percent}"),
            ITEMS_DROPPED to listOf("{잃은개수}", "{items_dropped}"),
            EXP_DROPPED to listOf("{잃은경험치}", "{exp_dropped}"),
            AMOUNT to listOf("{개수}", "{amount}"),
            WORLD to listOf("{월드}", "{world}"),
            X to listOf("{x}"),
            Y to listOf("{y}"),
            Z to listOf("{z}"),
        )
    }
}
