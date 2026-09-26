package com.inmc.invkeeper

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 이 플러그인이 밖에 대해 지키기로 한 약속들.
 *
 * 전부 컴파일 결과와 배포 파일을 직접 읽어 검사한다 — 소스만 보면 놓치는 것들이고, 서버를
 * 띄워야만 드러나는 종류라 여기서 잡지 않으면 운영 중에 드러난다.
 */
class ContractTest {

    // --- 리플렉션 순수성 ------------------------------------------------------------

    /** 컴파일된 코드에 절대 나타나면 안 되는 패키지. */
    private val reflectionOnly = listOf(
        "net/Indyuce/mmoitems",
        "io/lumine/mythic/lib",
        "dev/lone/itemsadder",
        "com/nexomc/nexo",
        "io/th0rgal/oraxen",
    )

    /**
     * 일부러 컴파일 의존으로 둔 것. 이 검사 자신의 대조군이다.
     *
     * 이것들이 안 잡히면 스캔이 엉뚱한 곳을 보고 있다는 뜻이고, 위의 단정은 전부 **틀린
     * 이유로** 통과하게 된다.
     */
    private val compiledAgainst = listOf("org/bstats", "kr/inmc/core")

    private fun classFiles(): List<File> {
        val root = File("build/classes/kotlin/main")
        assertTrue(root.isDirectory, "컴파일 결과를 찾을 수 없습니다: ${root.absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
    }

    private fun referencing(pkg: String): List<String> {
        val needle = pkg.toByteArray(Charsets.US_ASCII)
        return classFiles()
            .filter { it.readBytes().containsSequence(needle) }
            .map { it.path.substringAfter("main${File.separator}") }
    }

    @Test
    fun `선택 연동 플러그인을 직접 참조하지 않는다`() {
        // 서버에 MMOItems 가 없어도 우리 클래스가 전부 로드돼야 한다. import 하나가 섞이면
        // 첫 사망에서 NoClassDefFoundError 가 나고, 하필 진단할 여력이 가장 적은 서버에서 난다.
        val offenders = reflectionOnly.associateWith { referencing(it) }.filterValues { it.isNotEmpty() }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("선택 연동 타입이 컴파일 결과에 새어 들어왔습니다:")
                offenders.forEach { (pkg, files) ->
                    appendLine("  ${pkg.replace('/', '.')} <- ${files.joinToString(", ")}")
                }
                append("PluginClasses / 리플렉션으로 닿아야 합니다")
            },
        )
    }

    @Test
    fun `참조 스캔이 실제로 동작한다`() {
        val found = compiledAgainst.filter { referencing(it).isNotEmpty() }

        assertTrue(found.isNotEmpty(), "스캔이 아무것도 못 찾았습니다 - 검사 자체가 돌지 않고 있습니다")
    }

    // --- 디스크립터 ---------------------------------------------------------------

    private fun descriptor(): String =
        File("src/main/resources/paper-plugin.yml").readText(Charsets.UTF_8)

    @Test
    fun `main 클래스가 실제로 컴파일돼 있다`() {
        val main = descriptor().lineSequence()
            .first { it.startsWith("main:") }
            .substringAfter("main:")
            .trim()

        val compiled = File("build/classes/kotlin/main/" + main.replace('.', '/') + ".class")
        assertTrue(compiled.isFile, "paper-plugin.yml 은 $main 을 가리키는데 그런 클래스가 없습니다")
    }

    @Test
    fun `필수 의존은 inmc-core 하나뿐이다`() {
        // 필수 의존이 늘면 그 플러그인이 없는 서버에서 우리가 아예 켜지지 않는다.
        // 연동은 전부 선택이어야 하고, core 만 예외다 (stdlib 과 공용 프레임워크를 들고 있다).
        val required = descriptor().lineSequence()
            .filter { it.contains("required: true") }
            .map { it.substringBefore(':').trim() }
            .toList()

        assertEquals(listOf("inmc-core"), required, "필수 의존이 늘었습니다: $required")
    }

    @Test
    fun `연동은 전부 load OMIT 이다`() {
        // BEFORE 를 걸면 Paper 가 의존성 순환을 보고 우리 간선을 조용히 끊는다 (가이드 함정 1).
        val offenders = descriptor().lineSequence()
            .filter { it.contains("load: BEFORE") }
            .map { it.substringBefore(':').trim() }
            .filterNot { it == "inmc-core" }
            .toList()

        assertTrue(offenders.isEmpty(), "load: BEFORE 를 쓰면 안 되는 연동: $offenders")
    }

    @Test
    fun `api-version 을 손으로 적지 않는다`() {
        // 빌드가 주입한다. 손으로 적으면 컴파일 대상과 어긋나고, 1세대가 정확히 그랬다
        // (26.1.2 로 컴파일하면서 api-version 은 1.21 이라고 적어둠).
        assertTrue(
            descriptor().contains("api-version: '\${apiVersion}'"),
            "api-version 이 빌드에서 유도되지 않고 있습니다",
        )
    }

    @Test
    fun `commands 절이 없다`() {
        // paper-plugin.yml 은 commands 를 지원하지 않는다. 적어두면 조용히 무시되고
        // 명령어가 등록되지 않은 이유를 찾느라 시간을 쓴다.
        assertTrue(
            descriptor().lineSequence().none { it.trimStart().startsWith("commands:") },
            "paper-plugin.yml 에 commands 절이 있습니다 - Brigadier 로 등록해야 합니다",
        )
    }
}

/** 바이트 단위 부분 문자열 검색. 바이트코드 라이브러리 없이 클래스 파일을 훑는다. */
private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    val first = needle[0]
    outer@ for (start in 0..(size - needle.size)) {
        if (this[start] != first) continue
        for (offset in 1 until needle.size) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return true
    }
    return false
}
