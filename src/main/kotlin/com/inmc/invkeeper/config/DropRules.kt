package com.inmc.invkeeper.config

/** 사망 시 얼마를 잃는지. 0 은 완전 보호, 100 은 전부 드랍. */
data class DropRule(val inventoryPercent: Double, val expPercent: Double) {

    companion object {
        val KEEP_ALL = DropRule(0.0, 0.0)
    }
}

/** 권한 하나에 걸린 규칙. [priority] 가 높을수록 먼저 이긴다. */
data class PermissionDropRule(val permission: String, val priority: Int, val rule: DropRule)

/**
 * 월드별·권한별 드랍 비율을 하나로 합치는 순수 계산.
 *
 * **월드 규칙이 `priority = 0` 인 후보로 경쟁에 참가한다.** 배포 설정의 권한 규칙은 전부
 * priority 10 이상이므로, 권한을 가진 플레이어는 월드와 무관하게 그 권한의 드랍량을 따른다.
 * 이것이 사양이다 - 등급이 월드보다 강한 결정권을 갖는다. 1세대와 같은 계산이고 테스트로
 * 못박았다.
 *
 * [Player] 대신 `has` 람다를 받는다. 서버 없이 테스트하기 위해서다.
 */
class DropRules(
    private val default: DropRule,
    private val worlds: Map<String, DropRule>,
    private val permissions: List<PermissionDropRule>,
) {

    fun forWorld(worldName: String?): DropRule =
        worldName?.let { worlds[it] } ?: default

    /**
     * @param has 이 플레이어가 권한을 갖고 있는지. 보통 `player::hasPermission`.
     */
    fun resolve(worldName: String?, has: (String) -> Boolean): DropRule {
        var best = forWorld(worldName)
        var bestPriority = 0
        for (candidate in permissions) {
            // 동점이면 먼저 선언된 쪽이 이긴다 - 1세대의 안정 정렬과 같은 결과다.
            // (설정에 동점이 있으면 로드 때 경고가 나간다)
            if (candidate.priority <= bestPriority) continue
            if (!has(candidate.permission)) continue
            best = candidate.rule
            bestPriority = candidate.priority
        }
        return best
    }

    /** 설정 실수 진단용. 같은 priority 를 쓴 권한들을 묶어 돌려준다. */
    fun duplicatePriorities(): Map<Int, List<String>> =
        permissions.groupBy { it.priority }
            .filterValues { it.size > 1 }
            .mapValues { (_, rules) -> rules.map { it.permission } }

    companion object {
        val NONE = DropRules(DropRule.KEEP_ALL, emptyMap(), emptyList())
    }
}
