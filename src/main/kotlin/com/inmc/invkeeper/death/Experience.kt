package com.inmc.invkeeper.death

/**
 * 레벨·진행도와 총 경험치 점수 사이의 환산.
 *
 * 바닐라는 경험치를 "레벨 + 다음 레벨까지의 비율"로 들고 있어서, 비율만 깎으면 레벨 경계에서
 * 엉뚱한 양이 사라진다. **점수로 바꿔 계산하고 다시 레벨로 되돌린다.**
 *
 * 서버 없이 도는 순수 계산이다. 공식은 바닐라 것이고 1세대에서 그대로 옮겼다.
 */
object Experience {

    /** [level] 에서 다음 레벨까지 필요한 점수. */
    fun toNextLevel(level: Int): Int = when {
        level >= 30 -> 112 + (level - 30) * 9
        level >= 15 -> 37 + (level - 15) * 5
        else -> 7 + level * 2
    }

    /** 레벨과 진행도를 총 점수로. */
    fun total(level: Int, progress: Float): Int {
        var total = 0
        for (i in 0 until level) total += toNextLevel(i)
        total += Math.round(toNextLevel(level) * progress)
        return total
    }

    /** 총 점수를 레벨과 진행도로 되돌린다. */
    fun toLevel(experience: Int): Pair<Int, Float> {
        var level = 0
        var remaining = experience.coerceAtLeast(0)
        while (remaining >= toNextLevel(level)) {
            remaining -= toNextLevel(level)
            level++
        }
        val span = toNextLevel(level)
        return level to if (span == 0) 0f else remaining.toFloat() / span
    }
}

/**
 * 사망 시 무엇을 얼마나 잃는지 세는 순수 계산.
 *
 * 아이템은 **칸 단위**로 잃는다. 스택 안의 개수를 나누지 않는다 — 64개짜리 한 칸에서 32개만
 * 잃는 것이 아니라, 그 칸을 통째로 잃거나 통째로 지킨다. 1세대의 동작이고, 어느 칸을 잃을지는
 * 무작위로 고른다.
 */
object DropCount {

    /**
     * 드랍할 칸 수.
     *
     * **내림이다.** 3칸에 50% 면 1칸이고, 1칸에 50% 면 0칸이다. 반올림으로 바꾸면 한 칸만
     * 든 사람이 50% 월드에서 그 한 칸을 잃게 된다.
     */
    fun slots(occupied: Int, percent: Int): Int {
        if (occupied <= 0 || percent <= 0) return 0
        return Math.floor(occupied * percent / 100.0).toInt().coerceIn(0, occupied)
    }

    /** 잃을 경험치 점수. 이쪽은 **반올림**이다 - 점수는 칸과 달리 잘게 나눌 수 있다. */
    fun experience(totalExp: Int, percent: Int): Int {
        if (totalExp <= 0 || percent <= 0) return 0
        return Math.round(totalExp * percent / 100.0).toInt().coerceIn(0, totalExp)
    }

    /** 실제로 잃은 비율. 메시지에 찍는 값이라 요청 비율이 아니라 결과를 보여준다. */
    fun actualPercent(dropped: Int, occupied: Int): Int {
        if (occupied <= 0) return 0
        return Math.round(dropped * 100.0 / occupied).toInt().coerceIn(0, 100)
    }
}
