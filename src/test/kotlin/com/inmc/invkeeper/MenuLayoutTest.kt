package com.inmc.invkeeper

import kr.inmc.core.gui.Paging
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 화면 슬롯 배치를 못박는다.
 *
 * 슬롯 충돌은 컴파일러가 잡지 못하고 테스트도 보통 놓친다 — 두 버튼이 같은 칸에 그려지면
 * 나중에 그린 쪽만 보이고, 안 보이는 버튼은 **클릭 핸들러만 남아** 엉뚱한 동작을 한다.
 * 배치를 소스에서 직접 읽어 검사하므로 상수를 옮기면 여기가 따라온다.
 */
class MenuLayoutTest {

    private fun slotsOf(file: String): Map<String, Int> {
        val source = File("src/main/kotlin/com/inmc/invkeeper/gui/$file")
        assertTrue(source.exists(), "소스를 찾을 수 없습니다: ${source.absolutePath}")
        return Regex("""const val (SLOT_[A-Z_]+) = (\d+)""")
            .findAll(source.readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    @Test
    fun `아이템 설정 화면의 슬롯이 겹치지 않는다`() {
        val slots = slotsOf("ItemEditMenu.kt")

        assertTrue(slots.isNotEmpty(), "슬롯 상수를 하나도 못 읽었습니다")
        assertEquals(
            slots.size,
            slots.values.toSet().size,
            "슬롯 충돌: " + slots.entries.groupBy { it.value }.filterValues { it.size > 1 },
        )
    }

    @Test
    fun `아이템 설정 화면의 슬롯이 창 범위 안에 있다`() {
        for ((name, slot) in slotsOf("ItemEditMenu.kt")) {
            assertTrue(slot in 0 until SIZE, "$name = $slot 이 54칸 창을 벗어납니다")
        }
    }

    @Test
    fun `목록 화면이 페이지 칸과 버튼을 겹쳐 쓰지 않는다`() {
        val slots = slotsOf("ItemListMenu.kt")
        val info = slots.getValue("SLOT_INFO")

        // 항목은 0 부터 PER_PAGE - 1 까지를 쓴다. 버튼이 그 안에 들어가면 항목을 덮는다.
        assertTrue(info >= Paging.PER_PAGE, "SLOT_INFO($info) 가 항목 칸을 덮습니다")

        val reserved = listOf(
            Paging.SLOT_BACK,
            Paging.SLOT_PREV,
            Paging.SLOT_NEXT,
            Paging.SLOT_CLOSE,
            info,
        )
        assertEquals(reserved.size, reserved.toSet().size, "버튼 슬롯 충돌: $reserved")
        for (slot in reserved) assertTrue(slot in 0 until SIZE, "$slot 이 창을 벗어납니다")
    }

    private companion object {
        const val SIZE = 54
    }
}
