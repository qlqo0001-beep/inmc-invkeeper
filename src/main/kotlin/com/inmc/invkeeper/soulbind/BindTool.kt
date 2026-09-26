package com.inmc.invkeeper.soulbind

import com.inmc.invkeeper.item.BindMode
import com.inmc.invkeeper.item.BindStrength
import com.inmc.invkeeper.item.ItemKind

/**
 * 각인 도구를 아이템 위에 놓았을 때 무슨 일이 일어나는지 **결정만** 하는 순수 함수.
 *
 * 1세대는 이 판단이 리스너 한복판에 200줄짜리 if 사다리로 박혀 있었다. 분기가 열두 갈래인데
 * Bukkit 이벤트 없이는 한 줄도 검증할 수 없었고, 실제로 "무한 각인에 시간 도구를 쓰면"
 * 같은 경우를 사람이 손으로 시험해야 했다. 결정을 떼어내면 전부 테스트로 덮인다.
 */
object BindTool {

    sealed interface Result {

        /** 각인을 찍는다. [extended] 면 새로 찍는 게 아니라 기존 것을 늘린 것이다. */
        data class Bind(val strength: BindStrength, val extended: Boolean) : Result

        /** 각인을 지운다. */
        data object Unbind : Result

        /** 거절. [key] 는 messages.yml 키다. */
        data class Reject(val key: String, val type: String = "", val max: Int = 0) : Result

        /** 도구가 아니거나 할 일이 없다. 이벤트를 건드리지 않는다. */
        data object Ignore : Result
    }

    /**
     * @param tool      손에 든(또는 커서의) 도구의 종류
     * @param applies   그 도구가 대상에 찍는 각인의 세기
     * @param current   대상에 이미 찍혀 있는 각인
     * @param maxStack  설정의 최대 스택. -1 이면 무제한
     */
    fun decide(
        tool: ItemKind,
        applies: BindStrength,
        current: SoulbindState,
        maxStack: Int,
        now: Long,
    ): Result {
        if (!tool.isSoulbindTool) return Result.Ignore

        if (tool == ItemKind.SOULBIND_UNBIND_TOOL) {
            return if (current.type == SoulbindType.NONE) Result.Ignore else Result.Unbind
        }

        val timeTool = tool == ItemKind.SOULBIND_TOOL_TIME

        // 타입이 다르면 섞지 않는다. 시간과 횟수를 동시에 세는 각인은 없다.
        if (timeTool && current.type == SoulbindType.STACK) {
            return Result.Reject("soulbound-conflict-type", type = "스택형")
        }
        if (!timeTool && current.type == SoulbindType.TIME) {
            return Result.Reject("soulbound-conflict-type", type = "시간형")
        }

        return if (timeTool) decideTime(applies, current, now) else decideStack(applies, current, maxStack)
    }

    private fun decideTime(applies: BindStrength, current: SoulbindState, now: Long): Result {
        val toolIsPermanent = applies.durationMinutes <= 0
        val added = applies.durationMinutes * 60_000L

        if (current.type == SoulbindType.NONE) {
            return Result.Bind(
                BindStrength(BindMode.TIME, applies.durationMinutes, -1),
                extended = false,
            )
        }

        // 이미 무한이면 더 해줄 것이 없다. 시간 도구든 무한 도구든 마찬가지다.
        if (current.isInfinite) return Result.Reject("soulbound-already-infinite")

        if (toolIsPermanent) {
            return Result.Bind(BindStrength(BindMode.TIME, 0, -1), extended = true)
        }

        // 연장은 **남은 시간에 더하는 것**이 아니라 만료 시각에 더한다. 결과는 같지만,
        // 기준을 만료 시각으로 잡아야 도구를 여러 번 써도 오차가 쌓이지 않는다.
        val newExpiry = current.expiry + added
        val minutes = ((newExpiry - now).coerceAtLeast(0L) / 60_000L).toInt().coerceAtLeast(1)
        return Result.Bind(BindStrength(BindMode.TIME, minutes, -1), extended = true)
    }

    private fun decideStack(applies: BindStrength, current: SoulbindState, maxStack: Int): Result {
        val toolIsInfinite = applies.stacks < 0

        if (current.type == SoulbindType.NONE) {
            if (!toolIsInfinite && maxStack >= 0 && applies.stacks > maxStack) {
                return Result.Reject("soulbound-max-stack", max = maxStack)
            }
            return Result.Bind(BindStrength(BindMode.STACK, 0, applies.stacks), extended = false)
        }

        if (toolIsInfinite) {
            if (current.isInfinite) return Result.Reject("soulbound-already-infinite")
            // 상한이 걸려 있으면 무한으로 올릴 수 없다. 상한을 둔 의미가 없어지기 때문이다.
            if (maxStack >= 0) return Result.Reject("soulbound-max-stack", max = maxStack)
            return Result.Bind(BindStrength(BindMode.STACK, 0, -1), extended = true)
        }

        if (current.isInfinite) return Result.Reject("soulbound-already-infinite")

        val total = current.stacks + applies.stacks
        if (maxStack >= 0 && total > maxStack) {
            return Result.Reject("soulbound-max-stack", max = maxStack)
        }
        return Result.Bind(BindStrength(BindMode.STACK, 0, total), extended = true)
    }
}
