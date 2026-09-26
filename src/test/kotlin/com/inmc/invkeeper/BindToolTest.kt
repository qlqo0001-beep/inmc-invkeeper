package com.inmc.invkeeper

import com.inmc.invkeeper.item.BindMode
import com.inmc.invkeeper.item.BindStrength
import com.inmc.invkeeper.item.ItemKind
import com.inmc.invkeeper.soulbind.BindTool
import com.inmc.invkeeper.soulbind.SoulbindState
import com.inmc.invkeeper.soulbind.SoulbindType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 각인 도구 열두 갈래를 전부 못박는다.
 *
 * 1세대에서는 이 판단이 리스너 한복판의 if 사다리라 Bukkit 없이는 한 줄도 검증할 수 없었다.
 * 순수 함수로 떼어냈으니 전부 여기서 잡는다.
 */
class BindToolTest {

    private val owner: UUID = UUID.randomUUID()
    private val now = 1_700_000_000_000L
    private val noCap = -1

    private val none = SoulbindState.NONE
    private fun timed(minutesLeft: Long) =
        SoulbindState(SoulbindType.TIME, owner, now + minutesLeft * 60_000L, -1)
    private val infiniteTime = SoulbindState(SoulbindType.TIME, owner, BindStrength.INFINITE, -1)
    private fun stacked(stacks: Int) =
        SoulbindState(SoulbindType.STACK, owner, BindStrength.INFINITE, stacks)

    private fun timeTool(minutes: Int) = BindStrength(BindMode.TIME, minutes, -1)
    private fun stackTool(stacks: Int) = BindStrength(BindMode.STACK, 0, stacks)

    // --- 해제 도구 -----------------------------------------------------------------

    @Test
    fun `해제 도구는 각인을 지운다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_UNBIND_TOOL, timeTool(0), timed(10), noCap, now)

        assertEquals(BindTool.Result.Unbind, result)
    }

    @Test
    fun `각인 없는 아이템에 해제 도구를 쓰면 아무 일도 없다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_UNBIND_TOOL, timeTool(0), none, noCap, now)

        assertEquals(BindTool.Result.Ignore, result)
    }

    @Test
    fun `도구가 아니면 무시한다`() {
        val result = BindTool.decide(ItemKind.CONSUMABLE_PROTECTION, timeTool(60), none, noCap, now)

        assertEquals(BindTool.Result.Ignore, result)
    }

    // --- 타입 충돌 -----------------------------------------------------------------

    @Test
    fun `스택형 각인에 시간 도구를 쓰면 거절한다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_TIME, timeTool(60), stacked(3), noCap, now)

        assertEquals(BindTool.Result.Reject("soulbound-conflict-type", type = "스택형"), result)
    }

    @Test
    fun `시간형 각인에 스택 도구를 쓰면 거절한다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_STACK, stackTool(1), timed(10), noCap, now)

        assertEquals(BindTool.Result.Reject("soulbound-conflict-type", type = "시간형"), result)
    }

    // --- 시간형 -------------------------------------------------------------------

    @Test
    fun `각인 없는 아이템에 시간 도구를 쓰면 새로 찍는다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_TIME, timeTool(60), none, noCap, now)

        val bind = assertIs<BindTool.Result.Bind>(result)
        assertEquals(60, bind.strength.durationMinutes)
        assertEquals(false, bind.extended)
    }

    @Test
    fun `duration 0 인 시간 도구는 영구 각인이다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_TIME, timeTool(0), none, noCap, now)

        val bind = assertIs<BindTool.Result.Bind>(result)
        assertEquals(true, bind.strength.isPermanent)
    }

    @Test
    fun `이미 각인된 아이템에 시간 도구를 쓰면 연장된다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_TIME, timeTool(30), timed(10), noCap, now)

        val bind = assertIs<BindTool.Result.Bind>(result)
        assertEquals(true, bind.extended)
        // 남은 10분 + 30분.
        assertEquals(40, bind.strength.durationMinutes)
    }

    @Test
    fun `무한 각인에는 시간 도구를 쓸 수 없다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_TIME, timeTool(30), infiniteTime, noCap, now)

        assertEquals(BindTool.Result.Reject("soulbound-already-infinite"), result)
    }

    @Test
    fun `유한 각인을 영구 도구로 무한으로 올릴 수 있다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_TIME, timeTool(0), timed(10), noCap, now)

        val bind = assertIs<BindTool.Result.Bind>(result)
        assertEquals(true, bind.strength.isPermanent)
        assertEquals(true, bind.extended)
    }

    @Test
    fun `무한 각인에 영구 도구를 또 쓰면 거절한다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_TIME, timeTool(0), infiniteTime, noCap, now)

        assertEquals(BindTool.Result.Reject("soulbound-already-infinite"), result)
    }

    // --- 스택형 -------------------------------------------------------------------

    @Test
    fun `각인 없는 아이템에 스택 도구를 쓰면 새로 찍는다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_STACK, stackTool(3), none, noCap, now)

        val bind = assertIs<BindTool.Result.Bind>(result)
        assertEquals(3, bind.strength.stacks)
        assertEquals(false, bind.extended)
    }

    @Test
    fun `스택은 더해진다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_STACK, stackTool(2), stacked(3), noCap, now)

        val bind = assertIs<BindTool.Result.Bind>(result)
        assertEquals(5, bind.strength.stacks)
        assertEquals(true, bind.extended)
    }

    @Test
    fun `상한을 넘기면 거절한다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_STACK, stackTool(5), stacked(3), 5, now)

        assertEquals(BindTool.Result.Reject("soulbound-max-stack", max = 5), result)
    }

    @Test
    fun `상한과 정확히 같으면 통과한다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_STACK, stackTool(2), stacked(3), 5, now)

        val bind = assertIs<BindTool.Result.Bind>(result)
        assertEquals(5, bind.strength.stacks)
    }

    @Test
    fun `상한이 있으면 무한으로 올릴 수 없다`() {
        // 상한을 둔 의미가 없어지기 때문이다.
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_STACK, stackTool(-1), stacked(3), 5, now)

        assertEquals(BindTool.Result.Reject("soulbound-max-stack", max = 5), result)
    }

    @Test
    fun `상한이 없으면 무한으로 올릴 수 있다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_STACK, stackTool(-1), stacked(3), noCap, now)

        val bind = assertIs<BindTool.Result.Bind>(result)
        assertEquals(-1, bind.strength.stacks)
        assertEquals(true, bind.extended)
    }

    @Test
    fun `무한 스택 각인에는 더 쌓을 수 없다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_STACK, stackTool(2), stacked(-1), noCap, now)

        assertEquals(BindTool.Result.Reject("soulbound-already-infinite"), result)
    }

    @Test
    fun `새 각인도 상한을 넘기면 거절한다`() {
        val result = BindTool.decide(ItemKind.SOULBIND_TOOL_STACK, stackTool(10), none, 5, now)

        assertEquals(BindTool.Result.Reject("soulbound-max-stack", max = 5), result)
    }
}
