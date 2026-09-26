package com.inmc.invkeeper

import com.inmc.invkeeper.config.Messages
import com.inmc.invkeeper.item.BindMode
import com.inmc.invkeeper.item.BindStrength
import kr.inmc.core.store.DefinitionKey
import com.inmc.invkeeper.item.ItemKind
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 배포 리소스를 플러그인이 기동 때 읽는 방식 그대로 읽어본다.
 *
 * 배포본의 YAML 오타는 이 테스트가 없으면 **첫 기동이 깨지고 나서야** 드러난다. 한글이
 * 많아 인코딩 회귀도 눈에 잘 띄지 않는다.
 */
class ResourceTest {

    private fun load(path: String): YamlConfiguration {
        val stream = javaClass.classLoader.getResourceAsStream(path)
        assertNotNull(stream, "리소스를 찾을 수 없습니다: $path")
        return stream.use {
            YamlConfiguration.loadConfiguration(InputStreamReader(it, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `config yml 이 플러그인이 읽는 키를 전부 갖고 있다`() {
        val config = load("config.yml")

        assertTrue(config.getBoolean("force-keep-inventory-false"))
        assertEquals(5, config.getInt("soulbind-scan-batches"))
        assertEquals("Asia/Seoul", config.getString("timezone"))
        assertEquals(-1, config.getInt("max-soulbind-stack"))
        assertEquals(5L, config.getLong("soulbind-pickup-message-cooldown-seconds"))
        assertEquals(3L, config.getLong("soulbind-use-message-cooldown-seconds"))

        assertEquals(0.0, config.getDouble("rules.world.default.inventory-drop-percent"))
        assertEquals(50.0, config.getDouble("rules.world.world.inventory-drop-percent"))
        assertEquals(11, config.getMapList("rules.permissions").size)

        assertTrue(config.getBoolean("grave.enabled"))
        assertEquals("VANILLA", config.getString("grave.container.type"))
        assertEquals("BARREL", config.getString("grave.container.vanilla-material"))
        assertEquals(5, config.getInt("grave.max-graves-per-player"))
        assertEquals(3600L, config.getLong("grave.expire.default-seconds"))
        assertEquals(2, config.getMapList("grave.expire.permission-overrides").size)
        assertEquals(30, config.getInt("grave.history.retention-days"))
        assertEquals(50, config.getInt("grave.history.max-entries-per-player"))
        assertTrue(config.getBoolean("grave.hologram.enabled"))
        assertTrue(config.getBoolean("grave.protection.prevent-block-break"))
        assertTrue(config.getBoolean("grave.protection.prevent-hopper"))
    }

    @Test
    fun `드랍 규칙의 priority 가 겹치지 않는다`() {
        // 겹치면 어느 규칙이 이길지 설정만 보고는 알 수 없다. 기동 때 경고가 나가지만
        // 배포본에서만큼은 애초에 없어야 한다.
        val config = load("config.yml")
        val priorities = config.getMapList("rules.permissions").map { it["priority"] }

        assertEquals(priorities.size, priorities.toSet().size, "priority 중복: $priorities")
    }

    @Test
    fun `messages yml 이 내장 기본값과 같은 키를 갖는다`() {
        // 배포 파일에만 있는 키는 코드가 절대 읽지 않고, 코드에만 있는 키는 관리자가
        // 파일에서 찾을 수 없다. 양쪽이 같아야 한다.
        val shipped = load("messages.yml").getKeys(true).filterNot { it.contains('.') }.toSet()
        val builtIn = Messages.DEFAULTS.keys

        assertEquals(emptySet(), builtIn - shipped, "배포 messages.yml 에 빠진 키")
        assertEquals(emptySet(), shipped - builtIn, "코드가 읽지 않는 키")
    }

    @Test
    fun `items yml 의 모든 항목이 올바른 종류와 이름을 쓴다`() {
        val items = load("items.yml").getConfigurationSection("items")
        assertNotNull(items, "items 섹션이 없습니다")

        val keys = items.getKeys(false)
        assertTrue(keys.isNotEmpty(), "배포 예시가 비어 있습니다")

        for (key in keys) {
            assertTrue(DefinitionKey.isValid(key), "등록 이름 규칙 위반: $key")
            val section = items.getConfigurationSection(key)
            assertNotNull(section, "$key 섹션이 비어 있습니다")
            assertNotNull(ItemKind.parse(section.getString("kind")), "$key 의 kind 를 알 수 없습니다")
            assertTrue(section.getString("item").orEmpty().isNotBlank(), "$key 에 item 이 없습니다")
        }
    }

    @Test
    fun `items yml 의 각인 설정이 읽힌다`() {
        val items = load("items.yml").getConfigurationSection("items")!!

        val timeTool = items.getConfigurationSection("시간형각인기")!!
        val applies = BindStrength.load(timeTool.getConfigurationSection("applies"))
        assertEquals(BindMode.TIME, applies.mode)
        assertEquals(60, applies.durationMinutes)

        val stackTool = items.getConfigurationSection("스택형각인기")!!
        val stacks = BindStrength.load(stackTool.getConfigurationSection("applies"))
        assertEquals(BindMode.STACK, stacks.mode)
        assertEquals(1, stacks.stacks)

        // 보호권의 self-bind 는 영구여야 한다. 지급받은 사람 것이 되는 것이 배포 기본값이다.
        val consumable = items.getConfigurationSection("소모형보호권")!!
        val selfBind = BindStrength.load(consumable.getConfigurationSection("self-bind"))
        assertTrue(selfBind.isPermanent)
    }

    @Test
    fun `한글이 깨지지 않고 읽힌다`() {
        // UTF-8 을 명시하지 않은 스크립트가 파일을 다시 쓰면 여기서 잡힌다 (가이드 함정 18).
        val messages = load("messages.yml")

        assertTrue(messages.getString("protected").orEmpty().contains("보호권"))
        assertTrue(load("items.yml").getKeys(true).any { it.contains("각인기") })
    }
}
