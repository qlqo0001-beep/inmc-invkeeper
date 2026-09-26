package com.inmc.invkeeper

import com.inmc.invkeeper.item.BindMode
import com.inmc.invkeeper.item.BindStrength
import com.inmc.invkeeper.soulbind.SoulbindState
import com.inmc.invkeeper.soulbind.SoulbindType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 각인이 언제 살아 있고 언제 죽는지를 못박는다.
 *
 * 이 판정이 틀리면 사람들이 남의 아이템을 줍거나, 반대로 자기 아이템을 못 쓴다.
 * 서버 없이 도는 순수 계산이라 전부 여기서 잡을 수 있다.
 */
class SoulbindStateTest {

    private val owner: UUID = UUID.randomUUID()
    private val now = 1_700_000_000_000L

    private fun timed(expiry: Long) = SoulbindState(SoulbindType.TIME, owner, expiry, -1)
    private fun stacked(stacks: Int) = SoulbindState(SoulbindType.STACK, owner, BindStrength.INFINITE, stacks)

    // --- 시간형 -------------------------------------------------------------------

    @Test
    fun `만료 시각 전이면 살아 있다`() {
        assertTrue(timed(now + 1).isAlive(now))
    }

    @Test
    fun `만료 시각과 정확히 같은 순간은 아직 살아 있다`() {
        // 1세대가 `now > expiry` 로 판정했다. 경계에서 한 틱 더 사는 쪽이고, 그대로 뒀다.
        assertTrue(timed(now).isAlive(now))
        assertFalse(timed(now - 1).isAlive(now))
    }

    @Test
    fun `음수 만료는 무한이다`() {
        val infinite = timed(BindStrength.INFINITE)

        assertTrue(infinite.isInfinite)
        assertTrue(infinite.isAlive(now))
        assertTrue(infinite.isAlive(Long.MAX_VALUE))
        assertEquals("무한", infinite.remainingText(now))
    }

    @Test
    fun `남은 시간은 0 밑으로 내려가지 않는다`() {
        assertEquals(0L, timed(now - 10_000).remainingMillis(now))
    }

    @Test
    fun `남은 시간을 사람이 읽을 수 있게 찍는다`() {
        assertEquals("1분 30초", timed(now + 90_000).remainingText(now))
    }

    // --- 스택형 -------------------------------------------------------------------

    @Test
    fun `스택이 남아 있으면 살아 있다`() {
        assertTrue(stacked(1).isAlive(now))
        assertTrue(stacked(5).isAlive(now))
    }

    @Test
    fun `스택 0 이면 죽는다`() {
        assertFalse(stacked(0).isAlive(now))
    }

    @Test
    fun `음수 스택은 무한이다`() {
        val infinite = stacked(-1)

        assertTrue(infinite.isInfinite)
        assertTrue(infinite.isAlive(now))
    }

    @Test
    fun `스택형은 시간이 지나도 만료되지 않는다`() {
        assertTrue(stacked(1).isAlive(Long.MAX_VALUE))
        assertEquals("무한", stacked(1).remainingText(now))
    }

    // --- 각인 없음 ----------------------------------------------------------------

    @Test
    fun `각인이 없으면 살아 있지 않다`() {
        assertFalse(SoulbindState.NONE.isAlive(now))
        assertFalse(SoulbindState.NONE.isInfinite)
    }
}

/** 설정값이 실제 각인으로 어떻게 번역되는지. */
class BindStrengthTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `시간형 0분 이하는 영구다`() {
        // items.yml 이 "0 또는 -1 이면 영구"라고 약속한다.
        assertTrue(BindStrength(BindMode.TIME, 0, 1).isPermanent)
        assertTrue(BindStrength(BindMode.TIME, -1, 1).isPermanent)
        assertFalse(BindStrength(BindMode.TIME, 1, 1).isPermanent)
    }

    @Test
    fun `시간형 만료 시각은 분을 밀리초로 환산한다`() {
        assertEquals(now + 60 * 60_000L, BindStrength(BindMode.TIME, 60, 1).expiryAt(now))
    }

    @Test
    fun `영구 각인의 만료 시각은 INFINITE 다`() {
        assertEquals(BindStrength.INFINITE, BindStrength(BindMode.TIME, 0, 1).expiryAt(now))
    }

    @Test
    fun `스택형은 음수일 때만 영구다`() {
        assertTrue(BindStrength(BindMode.STACK, 0, -1).isPermanent)
        assertFalse(BindStrength(BindMode.STACK, 0, 1).isPermanent)
        assertFalse(BindStrength(BindMode.STACK, 0, 0).isPermanent)
    }

    @Test
    fun `모드 문자열을 읽고 모르는 값은 시간형으로 떨어진다`() {
        assertEquals(BindMode.STACK, BindMode.parse("stack"))
        assertEquals(BindMode.STACK, BindMode.parse("STACK"))
        assertEquals(BindMode.TIME, BindMode.parse("없는값"))
        assertEquals(BindMode.TIME, BindMode.parse(null))
    }
}
