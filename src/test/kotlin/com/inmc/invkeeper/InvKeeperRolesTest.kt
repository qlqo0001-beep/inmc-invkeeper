package com.inmc.invkeeper

import com.inmc.invkeeper.item.AutoBind
import com.inmc.invkeeper.item.BindMode
import com.inmc.invkeeper.item.BindStrength
import com.inmc.invkeeper.item.InvKeeperRoles
import com.inmc.invkeeper.item.ItemDefinition
import com.inmc.invkeeper.item.ItemKind
import kr.inmc.core.integration.ItemRoles
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 인벤키퍼의 정의가 커스텀아이템의 역할 값을 거쳐 돌아와도 그대로인지. 인벤키퍼 화면에서 고친 것이 커스텀아이템에 적혔다가 읽힌다.
 */
class InvKeeperRolesTest {

    private val ref = ItemRef.Namespaced("inmc", "보호권")
    private val old = StoredItem(ItemRef.Vanilla(Material.PAPER), Material.PAPER, displayName = "보호권")

    private fun same(a: ItemDefinition, b: ItemDefinition) {
        assertEquals(listOf(a.key, a.kind, a.protectionMinutes, a.applies, a.castSeconds, a.selfBind, a.autoBind, a.legacy),
            listOf(b.key, b.kind, b.protectionMinutes, b.applies, b.castSeconds, b.selfBind, b.autoBind, b.legacy))
    }

    @Test
    fun `종류마다 알맞은 역할로 가고 모든 칸이 돌아온다`() {
        for (kind in ItemKind.entries) {
            val original = ItemDefinition(
                key = "보호권", kind = kind, item = old, protectionMinutes = 45,
                applies = BindStrength(BindMode.STACK, 0, 3), castSeconds = 120,
                selfBind = AutoBind(true, BindStrength(BindMode.TIME, 60, 1)),
                autoBind = AutoBind(true, BindStrength(BindMode.STACK, 0, -1)),
                legacy = old,
            )
            val role = InvKeeperRoles.roleOf(kind)
            val holder = ItemRoles.Holder(ref, ItemRoles.withLegacy(InvKeeperRoles.values(original), old), Material.PAPER, "보호권")
            val back = InvKeeperRoles.definition(role, holder)!!
            same(original, back)
            assertEquals(ref, back.item.ref, "$kind: 아이템은 역할을 맡은 커스텀아이템이어야 한다")
        }
    }

    @Test
    fun `역할의 종류 칸이 다른 역할의 종류면 그 역할의 첫 종류로`() {
        val holder = ItemRoles.Holder(ref, mapOf("kind" to ItemKind.GRAVE_LOOT_TOOL.name), Material.PAPER)
        assertEquals(ItemKind.CONSUMABLE_PROTECTION, InvKeeperRoles.definition(InvKeeperRoles.PROTECTION, holder)!!.kind)
    }
}
