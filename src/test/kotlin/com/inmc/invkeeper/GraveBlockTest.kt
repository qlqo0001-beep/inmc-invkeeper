package com.inmc.invkeeper

import com.inmc.invkeeper.grave.Grave
import com.inmc.invkeeper.grave.GraveContents
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 무덤이 놓은 블록을 기억한다 — 설정을 바꿔도 사라질 때 그것을 치운다. */
class GraveBlockTest {

    private fun grave() = Grave(
        UUID.randomUUID(), UUID.randomUUID(), "tester", "world", 1, 64, -3, 1000L, Grave.NEVER,
        Material.GRASS_BLOCK, GraveContents(), GraveContents(),
    )

    @Test
    fun `놓은 블록과 받침 재질이 저장 왕복한다`() {
        val grave = grave()
        grave.block = "inmc:grave_marble"
        grave.placed = Material.NOTE_BLOCK
        val yaml = YamlConfiguration()
        grave.save(yaml.createSection("g"))
        val back = Grave.load(grave.id.toString(), YamlConfiguration().apply { loadFromString(yaml.saveToString()) }.getConfigurationSection("g")!!)!!
        assertEquals("inmc:grave_marble", back.block)
        assertEquals(Material.NOTE_BLOCK, back.placed)
        assertEquals(Material.GRASS_BLOCK, back.replacedMaterial)
    }

    @Test
    fun `옛 기록은 빈 칸 — 지금 설정으로 치운다`() {
        val yaml = YamlConfiguration()
        grave().save(yaml.createSection("g"))
        val back = Grave.load(UUID.randomUUID().toString(), yaml.getConfigurationSection("g")!!)!!
        assertEquals("", back.block)
        assertNull(back.placed)
    }
}
