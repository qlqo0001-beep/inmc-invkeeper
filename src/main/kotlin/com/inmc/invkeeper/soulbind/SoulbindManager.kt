package com.inmc.invkeeper.soulbind

import com.inmc.invkeeper.InvKeeper
import com.inmc.invkeeper.item.BindMode
import com.inmc.invkeeper.item.BindStrength
import com.inmc.invkeeper.util.Ph
import kr.inmc.core.CorePlugin
import kr.inmc.core.store.Profile
import kr.inmc.core.util.Text
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 아이템에 각인을 읽고 쓰는 곳. 각인의 **유일한** 진실 저장소는 아이템의 PDC 다.
 *
 * 별도 파일을 두지 않는 것이 설계다. 아이템은 상자·엔더상자·다른 서버로도 옮겨다니는데,
 * 플러그인 쪽 목록에 각인을 들고 있으면 그 목록과 아이템이 갈라지는 순간 복구할 방법이 없다.
 */
class SoulbindManager(private val inv: InvKeeper) {

    // --- 읽기 -------------------------------------------------------------------

    /**
     * 각인이 찍혀 있는지 **싸게** 확인한다.
     *
     * Paper 의 `ItemStack.getPersistentDataContainer()` 는 `ItemMeta` 를 통째로 복제하지 않고
     * PDC 만 본다. 만료 스캐너가 접속자 인벤토리를 훑을 때 대부분의 아이템은 각인이 없으므로,
     * 이 한 줄이 스캔 비용의 대부분을 결정한다.
     */
    fun isSoulbound(stack: ItemStack?): Boolean {
        if (stack == null || stack.type.isAir) return false
        val pdc = stack.persistentDataContainer
        return pdc.has(SoulbindKeys.OWNER, SoulbindKeys.OWNER_TYPE) ||
            pdc.has(SoulbindKeys.EXPIRY, SoulbindKeys.EXPIRY_TYPE) ||
            pdc.has(SoulbindKeys.STACKS, SoulbindKeys.STACKS_TYPE)
    }

    /** 각인 상태를 통째로 읽는다. 각인이 없으면 [SoulbindState.NONE]. */
    fun read(stack: ItemStack?): SoulbindState {
        if (stack == null || stack.type.isAir) return SoulbindState.NONE
        val meta = stack.itemMeta ?: return SoulbindState.NONE
        val pdc = meta.persistentDataContainer

        val hasExpiry = pdc.has(SoulbindKeys.EXPIRY, SoulbindKeys.EXPIRY_TYPE)
        val hasStacks = pdc.has(SoulbindKeys.STACKS, SoulbindKeys.STACKS_TYPE)
        val owner = pdc.get(SoulbindKeys.OWNER, SoulbindKeys.OWNER_TYPE)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        // 시간형이 먼저다 - 두 키가 동시에 있으면 안 되지만, 다른 플러그인이 건드린 아이템이
        // 들어올 수 있으므로 판정 순서를 1세대와 같게 고정한다.
        return when {
            hasExpiry -> SoulbindState(
                type = SoulbindType.TIME,
                owner = owner,
                expiry = pdc.get(SoulbindKeys.EXPIRY, SoulbindKeys.EXPIRY_TYPE) ?: BindStrength.INFINITE,
                stacks = -1,
            )

            hasStacks -> SoulbindState(
                type = SoulbindType.STACK,
                owner = owner,
                expiry = BindStrength.INFINITE,
                stacks = pdc.get(SoulbindKeys.STACKS, SoulbindKeys.STACKS_TYPE) ?: -1,
            )

            // 소유자만 있고 세는 값이 없는 아이템. 1세대는 이걸 NONE 으로 보면서도
            // isSoulbound 에서는 true 를 돌려줬다. 영구 시간형으로 읽는 편이 그 모순이 없다.
            owner != null -> SoulbindState(SoulbindType.TIME, owner, BindStrength.INFINITE, -1)

            else -> SoulbindState.NONE
        }
    }

    /** 이 플레이어가 이 아이템을 쓸 수 있는지. 각인이 없으면 누구나 쓸 수 있다. */
    fun isUsableBy(stack: ItemStack?, player: Player, now: Long = System.currentTimeMillis()): Boolean {
        val state = read(stack)
        if (state.type == SoulbindType.NONE || state.owner == null) return true
        if (!state.isAlive(now)) return true
        return state.owner == player.uniqueId
    }

    // --- 쓰기 -------------------------------------------------------------------

    /**
     * 각인을 찍는다. 반대 타입의 키는 지운다 — 시간형과 스택형은 동시에 존재할 수 없다.
     *
     * @return 로어까지 갱신된 스택. 인자를 직접 고치므로 같은 객체다.
     */
    fun apply(
        stack: ItemStack,
        owner: UUID?,
        strength: BindStrength,
        now: Long = System.currentTimeMillis(),
    ): ItemStack {
        val meta = stack.itemMeta ?: return stack
        val pdc = meta.persistentDataContainer

        if (owner != null) pdc.set(SoulbindKeys.OWNER, SoulbindKeys.OWNER_TYPE, owner.toString())

        when (strength.mode) {
            BindMode.TIME -> {
                pdc.set(SoulbindKeys.EXPIRY, SoulbindKeys.EXPIRY_TYPE, strength.expiryAt(now))
                pdc.remove(SoulbindKeys.STACKS)
            }

            BindMode.STACK -> {
                pdc.set(SoulbindKeys.STACKS, SoulbindKeys.STACKS_TYPE, cappedStacks(strength.stacks))
                pdc.remove(SoulbindKeys.EXPIRY)
            }
        }
        stack.itemMeta = meta
        refreshLore(stack, now)
        return stack
    }

    /** 설정의 최대 스택으로 자른다. 무한(-1)은 자르지 않는다. */
    fun cappedStacks(requested: Int): Int {
        val max = inv.config.maxSoulbindStack
        if (requested < 0 || max < 0) return requested
        return requested.coerceAtMost(max)
    }

    fun remove(stack: ItemStack) {
        val meta = stack.itemMeta ?: return
        stripLore(stack)
        val refreshed = stack.itemMeta ?: meta
        val pdc = refreshed.persistentDataContainer
        pdc.remove(SoulbindKeys.OWNER)
        pdc.remove(SoulbindKeys.EXPIRY)
        pdc.remove(SoulbindKeys.STACKS)
        pdc.remove(SoulbindKeys.LORE)
        stack.itemMeta = refreshed
    }

    /**
     * 각인 흔적을 전부 지운 **사본**을 만든다. 원본은 건드리지 않는다.
     *
     * 아이템 식별 때문에 필요하다. core 의 [kr.inmc.core.item.ItemMatcher] 는 참조를 못 만드는
     * 수제 아이템을 스냅샷의 `isSimilar` 로 비교하는데, 각인이 로어 한 줄과 PDC 키 넷을 더하는
     * 순간 그 비교가 거짓이 된다. 그러면 **각인된 보호권이 사망 시 보호권으로 인식되지 않는다.**
     * 조용히 깨지는 종류라, 식별 경로가 이 사본으로 한 번 더 물어본다.
     */
    fun stripped(stack: ItemStack): ItemStack = stack.clone().also { remove(it) }

    /**
     * 스택형 각인을 1 소모한다. 무한이면 아무것도 하지 않는다.
     *
     * 0 까지만 내려간다. 0 이 된 각인은 [expireIfDue] 가 다음에 지운다 — 여기서 바로 지우면
     * "마지막 한 번을 버텼다"는 사실을 부르는 쪽이 알 수 없다.
     */
    fun consumeStack(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val current = pdc.get(SoulbindKeys.STACKS, SoulbindKeys.STACKS_TYPE) ?: return false
        if (current < 0) return true
        if (current == 0) return false
        pdc.set(SoulbindKeys.STACKS, SoulbindKeys.STACKS_TYPE, current - 1)
        stack.itemMeta = meta
        return true
    }

    /**
     * 만료됐거나 스택이 0 이 된 각인을 지운다.
     *
     * @return 실제로 지웠으면 true.
     */
    fun expireIfDue(stack: ItemStack?, now: Long = System.currentTimeMillis()): Boolean {
        if (stack == null || !isSoulbound(stack)) return false
        val state = read(stack)
        if (state.isAlive(now)) return false
        remove(stack)
        return true
    }

    // --- 로어 -------------------------------------------------------------------

    /**
     * 각인 정보를 로어 한 줄로 다시 찍는다.
     *
     * 이전에 찍은 줄은 PDC 에 통째로 저장해 두고 그것과 **같은 줄만** 지운다. 형식 문자열로
     * 다시 만들어 맞추려 하면 그 사이 관리자가 형식을 바꾼 경우 옛 줄이 영원히 남는다.
     */
    fun refreshLore(stack: ItemStack, now: Long = System.currentTimeMillis()) {
        val state = read(stack)
        if (state.type == SoulbindType.NONE) {
            stripLore(stack)
            return
        }

        val ownerName = nameOf(state.owner)
        val key = if (state.type == SoulbindType.STACK) "soulbound-lore-format-stack" else "soulbound-lore-format"
        val ph = Ph.of().owner(ownerName).stacks(state.stacks).expiry(formatExpiry(state.expiry))
        val line = Text.plain(inv.messages.component(key, ph))
        if (line.isBlank()) {
            stripLore(stack)
            return
        }

        stripLore(stack)
        val meta = stack.itemMeta ?: return
        val lore = meta.lore()?.toMutableList() ?: mutableListOf()
        lore.add(inv.messages.component(key, ph))
        meta.lore(lore)
        meta.persistentDataContainer.set(SoulbindKeys.LORE, SoulbindKeys.OWNER_TYPE, line)
        stack.itemMeta = meta
    }

    /** 우리가 찍었던 로어 줄만 걷어낸다. 다른 줄은 건드리지 않는다. */
    fun stripLore(stack: ItemStack) {
        val meta = stack.itemMeta ?: return
        val marker = meta.persistentDataContainer.get(SoulbindKeys.LORE, SoulbindKeys.OWNER_TYPE)
        if (marker.isNullOrBlank()) return
        val lore = meta.lore() ?: return
        val kept = lore.filterNot { Text.plain(it) == marker }
        if (kept.size == lore.size) return
        meta.lore(kept)
        stack.itemMeta = meta
    }

    // --- 이름 ------------------------------------------------------------------

    /**
     * 각인 주인의 이름.
     *
     * core 의 [Profile] 을 먼저 본다. 1세대는 `Bukkit.getOfflinePlayer(uuid).name` 을 썼는데
     * 그건 캐시에 없으면 디스크를, 최악에는 Mojang API 를 때린다 — 로어를 그리는 경로에서
     * 할 일이 아니다. core 의 저장소는 메모리에 있고 모든 INMC 플러그인이 같이 채운다.
     */
    fun nameOf(owner: UUID?): String {
        if (owner == null) return "?"
        org.bukkit.Bukkit.getPlayer(owner)?.let { return it.name }
        runCatching { Profile.nameOf(CorePlugin.get().players, owner) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return owner.toString()
    }

    /** 만료 시각을 관리자가 설정한 시간대로 찍는다. 무한이면 "무한". */
    fun formatExpiry(expiry: Long): String {
        if (expiry < 0) return "무한"
        val zone = runCatching { ZoneId.of(inv.config.timezone) }.getOrDefault(ZoneId.systemDefault())
        return EXPIRY_FORMAT.withZone(zone).format(Instant.ofEpochMilli(expiry))
    }

    private companion object {
        val EXPIRY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
