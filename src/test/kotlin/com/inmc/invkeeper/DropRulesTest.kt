package com.inmc.invkeeper

import com.inmc.invkeeper.config.DropRule
import com.inmc.invkeeper.config.DropRules
import com.inmc.invkeeper.config.PermissionDropRule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 드랍 비율 결정을 못박는다.
 *
 * 1세대에서 옮겨온 계산이고, 여기서 동작이 바뀌면 **사람들이 죽을 때 잃는 양이 바뀐다.**
 * 되돌릴 수 없는 종류의 회귀라, "이상해 보이지만 그대로 둔 것"까지 단정으로 남긴다.
 */
class DropRulesTest {

    private val rules = DropRules(
        default = DropRule(0.0, 0.0),
        worlds = mapOf(
            "world" to DropRule(50.0, 50.0),
            "world_nether" to DropRule(70.0, 70.0),
        ),
        permissions = listOf(
            PermissionDropRule("invkeeper.drop.1", 10, DropRule(100.0, 100.0)),
            PermissionDropRule("invkeeper.drop.11", 100, DropRule(0.0, 0.0)),
        ),
    )

    private fun none(): (String) -> Boolean = { false }
    private fun only(vararg held: String): (String) -> Boolean = { it in held }

    @Test
    fun `권한이 없으면 월드 설정을 따른다`() {
        assertEquals(DropRule(50.0, 50.0), rules.resolve("world", none()))
        assertEquals(DropRule(70.0, 70.0), rules.resolve("world_nether", none()))
    }

    @Test
    fun `모르는 월드는 default 로 떨어진다`() {
        assertEquals(DropRule(0.0, 0.0), rules.resolve("minigame", none()))
        assertEquals(DropRule(0.0, 0.0), rules.resolve(null, none()))
    }

    @Test
    fun `권한 규칙은 월드 설정을 이긴다`() {
        // 사양이다 - 등급(권한)이 월드보다 강한 결정권을 갖는다.
        assertEquals(DropRule(100.0, 100.0), rules.resolve("world", only("invkeeper.drop.1")))
    }

    @Test
    fun `여러 권한을 가지면 priority 가 높은 쪽만 적용된다`() {
        val held = only("invkeeper.drop.1", "invkeeper.drop.11")

        // drop.1 은 100% 드랍(priority 10), drop.11 은 0% 드랍(priority 100).
        assertEquals(DropRule(0.0, 0.0), rules.resolve("world_nether", held))
    }

    @Test
    fun `priority 가 같으면 먼저 선언된 규칙이 이긴다`() {
        // 1세대는 안정 정렬 뒤 첫 항목을 골랐다. 같은 결과여야 한다.
        val tied = DropRules(
            default = DropRule(0.0, 0.0),
            worlds = emptyMap(),
            permissions = listOf(
                PermissionDropRule("a", 50, DropRule(10.0, 10.0)),
                PermissionDropRule("b", 50, DropRule(90.0, 90.0)),
            ),
        )

        assertEquals(DropRule(10.0, 10.0), tied.resolve(null, only("a", "b")))
    }

    @Test
    fun `priority 0 인 권한 규칙은 월드 설정을 이기지 못한다`() {
        // 월드 규칙이 후보 목록의 맨 앞에 들어가므로 동점에서 월드가 이긴다.
        val zero = DropRules(
            default = DropRule(0.0, 0.0),
            worlds = mapOf("world" to DropRule(50.0, 50.0)),
            permissions = listOf(PermissionDropRule("a", 0, DropRule(90.0, 90.0))),
        )

        assertEquals(DropRule(50.0, 50.0), zero.resolve("world", only("a")))
    }

    @Test
    fun `중복 priority 를 진단으로 찾아낸다`() {
        val tied = DropRules(
            default = DropRule.KEEP_ALL,
            worlds = emptyMap(),
            permissions = listOf(
                PermissionDropRule("a", 50, DropRule.KEEP_ALL),
                PermissionDropRule("b", 50, DropRule.KEEP_ALL),
                PermissionDropRule("c", 60, DropRule.KEEP_ALL),
            ),
        )

        assertEquals(mapOf(50 to listOf("a", "b")), tied.duplicatePriorities())
    }
}
