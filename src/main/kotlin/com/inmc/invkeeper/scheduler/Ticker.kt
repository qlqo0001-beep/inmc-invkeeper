package com.inmc.invkeeper.scheduler

import com.inmc.invkeeper.InvKeeper
import kr.inmc.core.scheduler.TickerBase

/**
 * 이 플러그인의 **유일한** 반복 작업, 1Hz.
 *
 * 주기적인 것은 전부 여기 얹는다 — 각인 만료 스캔, 시간형 보호 만료, 무덤 만료, 도굴 시전,
 * 홀로그램 갱신, 프롬프트 만료, 디스크 플러시. 한 틱커에 목록으로 모아두면 timings 리포트에서
 * 무엇이 비싼지 바로 보인다. 익명 태스크 열두 개로 흩어놓으면 그게 안 된다.
 *
 * 각 단계는 [step] 으로 격리된다. 무덤 하나가 터져도 그 뒤의 저장 단계까지 멈추면 안 된다.
 */
class Ticker(private val inv: InvKeeper) : TickerBase(inv.plugin) {


    override val periodTicks = PERIOD_TICKS

    override fun ready(): Boolean = inv.ready

    override fun tick(now: Long) {
        step("graves") { inv.graves.tick(now) }
        step("loot") { inv.looting.tick(now) }
        step("players") { inv.players.tick(now) }
        step("prompts") { inv.prompts.tick(now) }
        step("flush") {
            if (!inv.items.retired) inv.items.flush()
            inv.graves.flush()
        }
    }

    companion object {
        const val PERIOD_TICKS = 20L
    }
}
