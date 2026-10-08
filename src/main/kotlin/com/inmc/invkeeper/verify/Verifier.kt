package com.inmc.invkeeper.verify

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.grave.GraveContents
import com.inmc.invkeeper.item.BindMode
import com.inmc.invkeeper.item.BindStrength
import com.inmc.invkeeper.util.Ph
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BundleMeta
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `/인벤키퍼 검증` — 각인·무덤·보호·아이템 등록을 서버 안에서 실제로 돌려 확인한다(드랍·상점 검증기와 같은 틀, 2026-10-08).
 *
 * - 각인은 **손에 쥐지 않은 사본 아이템**에 찍어 판정만 본다 — 가방은 건드리지 않는다. "남의 것" 판정은 검증하는 사람의 우회를 잠깐 끈다
 *   (`SoulbindGuard.setBypassTest`, 끝나면 되돌린다).
 * - 무덤은 돌 한 개짜리 사본으로 만들었다가 바로 거둔다(블록 복구·기록). 제외 월드면 건너뛴다.
 * - 죽음 자체는 일으키지 않는다. 두 사람이 필요한 것(남의 각인 아이템 줍기·꾸러미 전달)은 판정 함수로만 본다.
 */
class Verifier(private val inv: InvKeeper) {

    data class Result(val name: String, val failure: String?) {
        val skipped: Boolean get() = failure?.startsWith(SKIP) == true
    }

    private class Check(val name: String, val run: (Stage) -> String?)

    fun run(player: Player) {
        val stage = Stage(inv, player)
        val results = try {
            CHECKS.map { check ->
                val failure = try {
                    check.run(stage)
                } catch (t: Throwable) {
                    "검증기 오류: " + t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
                }
                Result(check.name, failure)
            }
        } finally {
            stage.tearDown()
        }
        player.closeInventory()

        val failures = results.filter { it.failure != null && !it.skipped }
        val skips = results.filter { it.skipped }
        inv.messages.send(
            player, "verify-done",
            Ph.of().duration(results.size - failures.size - skips.size).item(failures.size.toString())
                .remaining(if (skips.isEmpty()) "" else " · 건너뜀 ${skips.size}"),
        )
        for (f in failures) inv.messages.send(player, "verify-failure", Ph.of().item("${f.name} — ${f.failure}"))
        for (s in skips) inv.messages.send(player, "verify-skipped", Ph.of().item("${s.name} — ${s.failure!!.removePrefix(SKIP).trim()}"))

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = inv.io.file("verify", "invkeeper-$stamp.txt")
        val text = buildString {
            appendLine("# inmc-invkeeper 검증 - ${LocalDateTime.now()} - ${player.name}")
            for (r in results) {
                appendLine((if (r.failure == null) "PASS " else if (r.skipped) "SKIP " else "FAIL ") + r.name + (r.failure?.let { " — $it" } ?: ""))
            }
        }
        inv.io.asyncRun {
            file.parentFile.mkdirs()
            kr.inmc.core.util.AtomicFiles.write(file, text)
        }
        inv.messages.send(player, "verify-report", Ph.of().item("plugins/${inv.plugin.name}/verify/${file.name}"))
    }

    /** 검사들이 쓰는 무대 — 남의 uuid 하나, 우회를 껐으면 되돌린다. */
    class Stage(val inv: InvKeeper, val player: Player) {
        val other: UUID = UUID.randomUUID()
        var bypassOff = false

        /** 남의 시간형 각인 다이아 사본. */
        fun othersItem(): ItemStack = inv.soulbinds.apply(ItemStack(Material.DIAMOND), other, BindStrength(BindMode.TIME, 60, 0))

        fun withoutBypass(block: () -> String?): String? {
            inv.guard.setBypassTest(player, true)
            bypassOff = true
            return try { block() } finally { inv.guard.setBypassTest(player, false); bypassOff = false }
        }

        fun tearDown() {
            if (bypassOff) inv.guard.setBypassTest(player, false)
        }
    }

    companion object {
        const val SKIP = "건너뜀:"

        private fun ok(condition: Boolean, failure: String): String? = if (condition) null else failure

        private val CHECKS: List<Check> = listOf(
            Check("각인 — 찍으면 읽히고, 풀면 평범한 아이템") { s ->
                val stack = s.othersItem()
                val state = s.inv.soulbinds.read(stack)
                ok(s.inv.soulbinds.isSoulbound(stack), "찍었는데 각인으로 안 읽힙니다")
                    ?: ok(state.owner == s.other, "주인이 ${state.owner} (${s.other} 여야)")
                    ?: run { s.inv.soulbinds.remove(stack); ok(!s.inv.soulbinds.isSoulbound(stack), "풀었는데 아직 각인입니다") }
            },
            Check("각인 — 남의 것은 못 쓰고(우회 끔), 내 것은 쓴다") { s ->
                s.withoutBypass {
                    val theirs = s.othersItem()
                    val mine = s.inv.soulbinds.apply(ItemStack(Material.DIAMOND), s.player.uniqueId, BindStrength(BindMode.TIME, 60, 0))
                    ok(!s.inv.soulbinds.isUsableBy(theirs, s.player), "남의 각인 아이템을 쓸 수 있다고 합니다")
                        ?: ok(s.inv.guard.isBlocked(theirs, s.player), "남의 각인 아이템이 막히지 않습니다")
                        ?: ok(s.inv.soulbinds.isUsableBy(mine, s.player), "내 각인 아이템을 못 쓴다고 합니다")
                        ?: ok(!s.inv.guard.isBlocked(mine, s.player), "내 각인 아이템이 막힙니다")
                }
            },
            Check("각인 — 꾸러미 속 남의 각인도 막힌다(2026-10-07 버그)") { s ->
                s.withoutBypass {
                    val bundle = ItemStack(Material.BUNDLE)
                    val meta = bundle.itemMeta as? BundleMeta ?: return@withoutBypass "$SKIP 꾸러미 메타를 못 만듭니다"
                    meta.addItem(s.othersItem())
                    bundle.itemMeta = meta
                    ok(s.inv.guard.isBlocked(bundle, s.player), "남의 각인이 든 꾸러미가 막히지 않습니다")
                }
            },
            Check("각인 — 우회를 켜면 OP 는 남의 것도 쓴다") { s ->
                if (!s.inv.guard.canBypass(s.player)) return@Check "$SKIP 우회 권한이 없는 계정입니다"
                ok(!s.inv.guard.isBlocked(s.othersItem(), s.player), "우회가 켜졌는데 막힙니다")
            },
            Check("각인 — 시간형은 기간이 지나면 풀린다") { s ->
                val stack = s.inv.soulbinds.apply(ItemStack(Material.DIAMOND), s.other, BindStrength(BindMode.TIME, 1, 0), now = 0L)
                ok(s.inv.soulbinds.isSoulbound(stack), "찍히지 않았습니다")
                    ?: ok(s.inv.soulbinds.expireIfDue(stack, now = 10L * 60_000L), "1분짜리가 10분 뒤에도 안 풀립니다")
                    ?: ok(!s.inv.soulbinds.isSoulbound(stack), "풀렸다는데 아직 각인입니다")
            },
            Check("무덤 — 사본으로 만들기 → 자리에 있음 → 거두기(블록 복구·기록)") { s ->
                val settings = s.inv.config.grave
                if (!settings.enabled) return@Check "$SKIP 무덤 기능이 꺼져 있습니다"
                if (settings.isWorldDisabled(s.player.world.name)) return@Check "$SKIP '${s.player.world.name}' 은 무덤 제외 월드입니다"
                val contents = GraveContents().also { it.slots[0] = ItemStack(Material.STONE, 3) }
                val now = System.currentTimeMillis()
                val grave = s.inv.graves.create(s.player, contents, now) ?: return@Check "무덤을 만들지 못했습니다(자리 없음)"
                val at = grave.location() ?: return@Check "무덤 자리를 모릅니다"
                val found = s.inv.graves.at(at)
                val material = at.block.type
                s.inv.graves.dispose(grave, now)
                ok(found != null && found.id == grave.id, "만든 자리에서 무덤을 못 찾습니다")
                    ?: ok(material != grave.replacedMaterial || material == Material.AIR, "무덤 블록이 놓이지 않았습니다")
                    ?: ok(s.inv.graves.at(at) == null, "거뒀는데 아직 등록돼 있습니다")
                    ?: ok(at.block.type == grave.replacedMaterial, "거둔 뒤 블록이 ${at.block.type} (${grave.replacedMaterial} 여야)")
            },
            Check("보호 — 시간형 보호 켜기 → 활성 → 끄기") { s ->
                if (s.inv.protection.isTimedActive(s.player)) return@Check "$SKIP 이미 시간형 보호가 켜져 있습니다"
                s.inv.protection.activateTimed(s.player, 60)
                val active = s.inv.protection.isTimedActive(s.player)
                s.inv.protection.clearTimed(s.player)
                ok(active, "켰는데 활성이 아닙니다") ?: ok(!s.inv.protection.isTimedActive(s.player), "껐는데 아직 활성입니다")
            },
            Check("아이템 등록 — items.yml 의 아이템을 만들고 되알아본다") { s ->
                val def = s.inv.items.all().firstOrNull() ?: return@Check "$SKIP 등록된 아이템이 없습니다"
                val stack = s.inv.itemResolver.create(def.item, 1) ?: return@Check "'${def.key}' 를 만들지 못합니다(공급처 없음)"
                ok(s.inv.items.identify(stack)?.key == def.key, "만든 '${def.key}' 를 ${s.inv.items.identify(stack)?.key} 로 알아봅니다")
            },
        )
    }
}
