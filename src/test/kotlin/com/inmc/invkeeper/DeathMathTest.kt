package com.inmc.invkeeper

import com.inmc.invkeeper.death.DropCount
import com.inmc.invkeeper.death.Experience
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 죽을 때 실제로 얼마를 잃는지를 못박는다.
 *
 * 드랍 비율은 정책이고 이 계산은 그 정책의 집행이다. 여기가 한 칸 틀리면 사람들이 잃지
 * 않아야 할 것을 잃는다. 서버 없이 전부 검증할 수 있다.
 */
class DropCountTest {

    @Test
    fun `칸 수는 내림이다`() {
        // 3칸에 50% 면 1칸이다. 반올림이면 2칸이 되어 절반보다 많이 잃는다.
        assertEquals(1, DropCount.slots(3, 50))
        assertEquals(2, DropCount.slots(5, 50))
        assertEquals(0, DropCount.slots(1, 50))
    }

    @Test
    fun `한 칸만 든 사람은 50 퍼센트 월드에서 아무것도 잃지 않는다`() {
        // 내림을 고른 이유가 이것이다.
        assertEquals(0, DropCount.slots(1, 99))
        assertEquals(1, DropCount.slots(1, 100))
    }

    @Test
    fun `0 퍼센트와 100 퍼센트는 경계에서 정확하다`() {
        assertEquals(0, DropCount.slots(40, 0))
        assertEquals(40, DropCount.slots(40, 100))
    }

    @Test
    fun `빈 인벤토리는 0 이다`() {
        assertEquals(0, DropCount.slots(0, 100))
    }

    @Test
    fun `경험치는 반올림이다`() {
        // 점수는 칸과 달리 잘게 나눌 수 있어 내림을 쓸 이유가 없다.
        assertEquals(5, DropCount.experience(10, 50))
        assertEquals(2, DropCount.experience(3, 50))
        assertEquals(0, DropCount.experience(0, 100))
        assertEquals(0, DropCount.experience(100, 0))
    }

    @Test
    fun `실제 손실 비율은 결과로 계산한다`() {
        // 요청 50% 였어도 3칸 중 1칸을 잃었으면 메시지에는 33% 이 찍혀야 한다.
        assertEquals(33, DropCount.actualPercent(1, 3))
        assertEquals(0, DropCount.actualPercent(0, 10))
        assertEquals(0, DropCount.actualPercent(5, 0), "빈 인벤토리로 나누면 안 된다")
    }
}

/** 레벨 ↔ 점수 환산. 레벨 경계에서 엉뚱한 양이 사라지지 않아야 한다. */
class ExperienceTest {

    @Test
    fun `레벨 0 은 점수 0 이다`() {
        assertEquals(0, Experience.total(0, 0f))
    }

    @Test
    fun `구간마다 다음 레벨 비용이 다르다`() {
        // 바닐라 공식. 15 와 30 에서 기울기가 바뀐다.
        assertEquals(7, Experience.toNextLevel(0))
        assertEquals(37, Experience.toNextLevel(15))
        assertEquals(112, Experience.toNextLevel(30))
        assertEquals(121, Experience.toNextLevel(31))
    }

    @Test
    fun `점수로 바꿨다 되돌리면 같은 레벨이다`() {
        for (level in intArrayOf(0, 1, 14, 15, 16, 29, 30, 31, 50, 100)) {
            val (back, progress) = Experience.toLevel(Experience.total(level, 0f))
            assertEquals(level, back, "레벨 $level 왕복 실패")
            assertTrue(progress < 0.001f, "레벨 $level 의 진행도가 0 이 아니다: $progress")
        }
    }

    @Test
    fun `진행도가 섞여도 왕복한다`() {
        val total = Experience.total(20, 0.5f)
        val (level, progress) = Experience.toLevel(total)

        assertEquals(20, level)
        // 점수가 정수라 반올림 오차가 한 점 있을 수 있다.
        assertTrue(kotlin.math.abs(progress - 0.5f) < 0.03f, "진행도 $progress")
    }

    @Test
    fun `음수 점수는 레벨 0 이 된다`() {
        assertEquals(0 to 0f, Experience.toLevel(-100))
    }
}
