package com.inmc.invkeeper.config

import com.inmc.invkeeper.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `messages.yml` 한 벌.
 *
 * 읽고 보내는 부분은 전부 core 의 [MessageCatalog] 가 갖고 있다. 여기 남는 것은 기본값
 * 표뿐이고 그게 이 플러그인의 도메인이다.
 *
 * **표의 내용은 1세대 `messages.yml` 을 글자 그대로 옮긴 것이다.** 관리자가 고쳐 쓰던 파일을
 * 그대로 가져와도 돌아야 하고, 한 줄을 지워도 여기 기본값으로 떨어져 빈 메시지가 나가지 않는다.
 * 1세대에 없던 키(자동각인·아이템 등록)는 표 아래쪽에 따로 모았다.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        val DEFAULTS: Map<String, String> = mapOf(
            // 1세대에는 접두사가 없었다. 빈 값이면 core 가 아무것도 붙이지 않는다.
            PREFIX to "",

            // --- 사망 / 보호 -------------------------------------------------------
            "death" to "<red>인벤토리 {inv_percent}% ({items_dropped}개), 경험치 {exp_percent}% ({exp_dropped}exp)를 잃었습니다.</red>",
            "protected" to "<green>인벤토리 보호권을 소모하여 아무것도 잃지 않았습니다!</green>",
            "timed-protected" to "<green>인벤토리 보호 상태 임으로 아무것도 잃지 않았습니다! (남은 시간: {remaining})</green>",
            "timed-already-active" to "<yellow>이미 보호 상태입니다. (남은 시간: {remaining})</yellow>",
            "timed-activated" to "<green>인벤토리 보호가 {duration}분간 활성화되었습니다.</green>",
            "timed-remaining-five-minutes" to "<yellow>보호 상태가 5분 남았습니다. 남은 시간: {remaining}</yellow>",
            "timed-remaining-one-minute" to "<yellow>보호 상태가 1분 남았습니다. 남은 시간: {remaining}</yellow>",

            // --- 영혼각인 -----------------------------------------------------------
            "soulbound-applied" to "<green>아이템에 영혼각인이 적용되었습니다. (대상: {owner}, 지속시간: {remaining})</green>",
            "soulbound-extended" to "<green>이미 각인된 아이템의 유지시간이 연장되었습니다. (남은 시간: {remaining})</green>",
            "soulbound-unbound" to "<green>아이템의 영혼각인이 해제되었습니다.</green>",
            "soulbound-cant-pickup" to "<red>{item_name}은(는) {owner}의 각인 아이템입니다. 획득할 수 없습니다.</red>",
            "soulbound-cant-use" to "<red>{item_name}은(는) {owner}의 각인 아이템입니다. 사용할 수 없습니다.</red>",
            "soulbound-forced-dropped" to "<yellow>이 플레이어가 소유자가 아니라서 아이템을 강제로 드랍했습니다.</yellow>",
            "soulbound-already-infinite" to "<yellow>이 아이템은 이미 무한 각인 상태입니다.</yellow>",
            "soulbound-lore-format" to "<gray>각인: <aqua>{owner}</aqua> | 만료: <aqua>{expiry}</aqua></gray>",
            "soulbound-lore-format-stack" to "<gray>각인: <aqua>{owner}</aqua> | 횟수: <aqua>{stacks}</aqua></gray>",
            "soulbound-conflict-type" to "<red>이 아이템은 {type} 각인 상태입니다. 다른 타입의 각인을 적용할 수 없습니다.</red>",
            "soulbound-max-stack" to "<red>최대 각인 스택({max})을 초과하여 적용할 수 없습니다.</red>",

            // --- 무덤 ---------------------------------------------------------------
            "grave-created" to "<yellow>무덤이 생성되었습니다. ({world}, {x}, {y}, {z})</yellow>",
            "grave-not-owner" to "<red>이 무덤의 주인은 {owner} 입니다.</red>",
            "grave-opened" to "<green>무덤을 열었습니다.</green>",
            "grave-max-reached" to "<red>무덤 최대 개수({max})에 도달하여 가장 오래된 무덤이 정리되었습니다.</red>",
            "grave-expired" to "<gray>({world}, {x}, {y}, {z}) 의 무덤이 시간이 지나 사라졌습니다.</gray>",
            "grave-fully-recovered" to "<yellow>무덤의 모든 아이템을 회수하여 무덤이 사라졌습니다.</yellow>",
            "grave-loot-start-caster" to "<green>도굴을 시작합니다. {seconds}초 후 무덤이 열립니다.</green>",
            "grave-loot-start-owner-alert" to "<red>누군가 당신의 무덤을 도굴중입니다!</red>",
            "grave-loot-cancelled" to "<red>도굴중, {owner}이(가) 무덤을 확인하여 도굴이 취소되었습니다.</red>",
            "grave-loot-blocked-owner" to "<green>무덤 도굴을 막았습니다!</green>",
            "grave-loot-already-in-progress" to "<red>이미 다른 플레이어가 이 무덤을 도굴하고 있습니다.</red>",
            "grave-loot-self-blocked" to "<red>자신의 무덤은 도굴할 수 없습니다.</red>",
            "grave-loot-complete-caster" to "<green>도굴이 완료되었습니다! 이제 무덤을 열 수 있습니다.</green>",
            "grave-loot-item-required" to "<red>도굴 아이템이 필요합니다.</red>",
            "grave-history-emptied" to "<green>히스토리에 남아있던 아이템을 모두 회수했습니다. (히스토리는 유지됩니다)</green>",
            "grave-history-no-items" to "<red>이 무덤에는 남아있는 아이템이 없습니다.</red>",

            // --- 명령어 공통 (1세대는 코드에 하드코딩돼 있었다) ------------------------
            "no-permission" to "<red>권한이 없습니다.</red>",
            "player-only" to "<red>이 명령어는 플레이어만 사용할 수 있습니다.</red>",
            "not-ready" to "<gray>플러그인이 아직 준비 중입니다. 잠시 후 다시 시도해주세요.</gray>",
            "usage" to "<gray>/인벤키퍼 <white>[관리|상태|리로드|지급|각인|무덤]</white></gray>",
            "reloaded" to "<green>설정을 다시 불러왔습니다.</green>",
            "unknown-item" to "<red>'{item}' 이라는 아이템 설정이 없습니다.</red>",
            "player-not-found" to "<red>'{player}' 을(를) 찾을 수 없습니다.</red>",
            "given" to "<green>{player} 에게 {item} {amount}개를 지급했습니다.</green>",
            "hand-empty" to "<red>손에 아이템을 들고 있어야 합니다.</red>",

            // --- 아이템 등록 (신규) ---------------------------------------------------
            "registered" to "<green>손에 든 아이템을 '{item}' 으로 등록했습니다.</green>",
            "unregistered" to "<green>'{item}' 등록을 해제했습니다.</green>",

            // --- 자동각인 (신규) ------------------------------------------------------
            "autobind-applied" to "<green>{item_name}이(가) 당신에게 자동으로 각인되었습니다. ({remaining})</green>",
        )
    }
}
