package com.inmc.invkeeper

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 1세대와 권한 선언이 같은가.
 *
 * **선언하지 않은 권한 노드는 Bukkit 이 `default: op` 로 다룬다.** 5세대가 처음에
 * `invkeeper.admin` 하나만 선언해서, 설정이 쓰는 등급 노드(`drop.11` 드랍 0% ·
 * `grave.time.vvip` 무덤 무제한)가 **OP 에게 자동으로 붙었다** — OP 는 죽어도 아무것도
 * 안 떨어지고 무덤이 영원했다. 1세대는 전부 `default: false` 였다.
 *
 * 오류도 로그도 없는 종류라 테스트로 고정한다.
 */
class PermissionParityTest {

    private val descriptor: YamlConfiguration =
        YamlConfiguration.loadConfiguration(File("src/main/resources/paper-plugin.yml"))

    private fun default(node: String): String? = descriptor.getString("permissions.$node.default")

    @Test
    fun `1세대의 권한이 기본값까지 같다`() {
        val expected = buildMap {
            put("invkeeper.status", "true")
            put("invkeeper.admin", "op")
            put("invkeeper.grave.admin", "op")
            put("invkeeper.grave.bypass", "op")
            put("invkeeper.soulbind.bypass", "false")
            put("invkeeper.grave.time.vip", "false")
            put("invkeeper.grave.time.vvip", "false")
            for (level in 1..11) put("invkeeper.drop.$level", "false")
        }
        for ((node, value) in expected) {
            assertEquals(value, default(node), "$node 의 기본값")
        }
    }

    @Test
    fun `설정 파일이 쓰는 권한 노드는 전부 선언돼 있다`() {
        // 선언이 빠지면 그 노드는 OP 에게 자동으로 붙는다. 등급 노드라면 OP 가 그 등급을 받는다.
        val config = File("src/main/resources/config.yml").readText(Charsets.UTF_8)
        val used = Regex("""permission:\s*"([^"]+)"""").findAll(config).map { it.groupValues[1] }.toSet()
        assertTrue(used.isNotEmpty(), "config.yml 에서 권한 노드를 찾지 못했습니다")

        // getKeys 로 모으면 안 된다 - YamlConfiguration 이 노드 이름의 점을 경로로 나눠 최상위가
        // `invkeeper` 하나만 나온다. 경로가 있는지로 본다.
        for (node in used) assertTrue(descriptor.isSet("permissions.$node.default"), "$node 가 선언되지 않았습니다")
    }

    @Test
    fun `코드가 직접 부르는 권한 노드는 전부 선언돼 있다`() {
        val source = File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText(Charsets.UTF_8) }
        val used = Regex("""const val [A-Z_]+ = "(invkeeper\.[a-z0-9_.]+)"""").findAll(source)
            .map { it.groupValues[1] }.toSet()
        assertTrue(used.isNotEmpty())

        // getKeys 로 모으면 안 된다 - YamlConfiguration 이 노드 이름의 점을 경로로 나눠 최상위가
        // `invkeeper` 하나만 나온다. 경로가 있는지로 본다.
        for (node in used) assertTrue(descriptor.isSet("permissions.$node.default"), "$node 가 선언되지 않았습니다")
    }
}
