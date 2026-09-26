plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.invkeeper"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmc-invkeeper"
}

dependencies {
    // 셰이딩 대상은 bStats 하나뿐이다.
    implementation(libs.bstats.bukkit)

    // MMOItems / MythicLib / ItemsAdder 는 전부 리플렉션이다 — core 의 훅이 대신 닿는다.
    // 1세대 pom.xml 은 MMOItems-API 와 MythicLib-dist 를 provided 로 걸고 저장소까지 열어뒀지만
    // import 가 한 줄도 없었다. 옮기지 않는다.
}

tasks.shadowJar {
    // bStats 는 relocate 가 필수다. 안 하면 다른 플러그인의 bStats 와 충돌하고,
    // 라이브러리 자체가 relocate 여부를 검사해 예외를 던진다 (가이드 함정 4).
    relocate("org.bstats", "com.inmc.invkeeper.lib.bstats")
}
