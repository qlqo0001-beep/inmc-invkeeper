package com.inmc.invkeeper.item

import com.inmc.invkeeper.InvKeeper
import kr.inmc.core.integration.ItemRoles
import kr.inmc.core.store.YamlFileStore
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 등록된 아이템 전부. `items.yml` 한 파일에 담긴다.
 *
 * [identify] 는 **핫 패스**다 — 줍기·인벤토리 클릭·사망마다 불린다. 그래서 재질로 먼저 거르고
 * 나서야 core 의 [kr.inmc.core.item.ItemMatcher] 를 부른다. 등록 수가 수십 개라도 후보는
 * 보통 0~1 개로 줄어든다.
 *
 * ## 커스텀아이템이 있으면 ([hub])
 * 정의는 커스텀아이템의 역할([InvKeeperRoles])에서 온다 — 이 파일은 처음 한 번 **그리로 옮기고**(`items.yml.migrated`) 다시는
 * 쓰지 않는다. 등록·수정·해제는 전부 역할로 간다(core `ItemRoles`), 그래서 인벤키퍼 화면에서 고쳐도 커스텀아이템 화면에서 고쳐도
 * 같은 곳이 바뀐다. 옛 아이템(옮기기 전의 것)도 [ItemDefinition.legacy] 로 계속 알아본다.
 */
class ItemRegistry(private val inv: InvKeeper) : YamlFileStore(
    io = inv.io,
    path = listOf("items.yml"),
    header = "등록된 아이템. /인벤키퍼 등록 으로 손에 든 것을 그대로 넣을 수 있습니다.",
    what = "아이템",
) {

    private val byKey = LinkedHashMap<String, ItemDefinition>()

    /** 재질 → 그 재질을 쓰는 정의들. [identify] 의 1차 거르개. */
    private val byMaterial = HashMap<Material, MutableList<ItemDefinition>>()

    val size: Int get() = byKey.size

    // --- 조회 -------------------------------------------------------------------

    fun get(key: String?): ItemDefinition? = key?.let { byKey[it.lowercase()] }

    fun exists(key: String?): Boolean = get(key) != null

    fun all(): List<ItemDefinition> = byKey.values.toList()

    fun keys(): List<String> = byKey.keys.toList()

    fun ofKind(kind: ItemKind): List<ItemDefinition> = byKey.values.filter { it.kind == kind }

    /**
     * 이 스택이 어떤 등록 아이템인지. 아니면 null.
     *
     * 같은 재질에 여러 정의가 걸려 있으면 **먼저 등록된 것이 이긴다**. 등록 순서가 곧 우선순위다.
     *
     * 각인이 찍힌 아이템은 두 번 본다. 수제 아이템은 스냅샷의 `isSimilar` 로 비교되는데 각인이
     * 로어 한 줄과 PDC 키를 더하면 그 비교가 거짓이 되기 때문이다 — 그대로 두면 **각인된
     * 보호권이 사망 시 인식되지 않는다.** 두 번째 비교는 각인이 실제로 찍힌 아이템에만,
     * 그것도 첫 비교가 실패했을 때만 돈다.
     */
    fun identify(stack: ItemStack?): ItemDefinition? {
        if (stack == null || stack.type.isAir) return null
        val candidates = byMaterial[stack.type] ?: return null
        candidates.firstOrNull { matches(stack, it) }?.let { return it }

        if (!inv.soulbinds.isSoulbound(stack)) return null
        val bare = inv.soulbinds.stripped(stack)
        return candidates.firstOrNull { matches(bare, it) }
    }

    fun matches(stack: ItemStack?, definition: ItemDefinition): Boolean =
        inv.itemMatcher.matches(stack, definition.item) || definition.legacy?.let { inv.itemMatcher.matches(stack, it) } == true

    // --- 커스텀아이템 -----------------------------------------------------------------

    /** 커스텀아이템이 역할 창구를 꽂았다 — 정의는 거기서 온다. */
    val hub: Boolean get() = ItemRoles.active

    private val file get() = inv.io.file("items.yml")

    /** 이미 커스텀아이템으로 옮겼다 — 이 파일은 다시 쓰지 않는다(쓰면 다음 시작에 또 옮겨 둘이 된다). */
    val retired: Boolean get() = ItemRoles.isRetired(file)

    /** 역할이 바뀌었다(core `ItemRoles.listen`). 우리 역할이면 다시 읽는다. 처음 꽂혔고 파일이 남아 있으면 옮긴다. */
    fun onRolesChanged(role: String?) {
        if (role != null && role !in InvKeeperRoles.ALL) return
        if (hub && file.isFile) load() else if (hub) rebuild() else load()
    }

    private fun rebuild() {
        byKey.clear()
        byMaterial.clear()
        for (role in InvKeeperRoles.ALL) for (holder in ItemRoles.holders(role)) {
            if (byKey.containsKey(holder.ref.id)) continue
            InvKeeperRoles.definition(role, holder)?.let(::put)
        }
    }

    /** 파일의 정의를 커스텀아이템으로 옮기고 파일은 `.migrated` 로 바꿔 둔다. 옛 아이템은 역할 값에 숨겨 계속 알아본다. */
    private fun migrate(definitions: List<ItemDefinition>) {
        val target = ItemRoles.retire(file)
        var moved = 0
        for (definition in definitions) {
            val stack = inv.itemResolver.create(definition.item, 1)?.let { ItemRoles.sample(it, definition.item) }
            val ref = stack?.let { ItemRoles.adopt(it, definition.key) }
            if (ref == null) {
                inv.logger.warning("아이템 '${definition.key}' 을(를) 커스텀아이템으로 옮기지 못했습니다 — ${target.name} 에 남아 있습니다")
                continue
            }
            ItemRoles.assign(ref, InvKeeperRoles.roleOf(definition.kind), ItemRoles.withLegacy(InvKeeperRoles.values(definition), definition.item))
            moved++
        }
        inv.logger.info("인벤키퍼 아이템 ${moved}개를 커스텀아이템으로 옮겼습니다 (원본: ${target.name})")
    }

    // --- 변경 -------------------------------------------------------------------

    /**
     * 손에 든 아이템을 그대로 등록한다. 같은 키가 있으면 덮어쓴다.
     * 커스텀아이템이 있으면 그 아이템을 **커스텀아이템으로 만들어**(이미 그렇다면 그대로) 역할을 붙인다 — 키는 커스텀아이템 id 가 된다.
     */
    fun register(key: String, kind: ItemKind, stack: ItemStack): ItemDefinition {
        if (hub) {
            val captured = inv.itemResolver.capture(stack)
            val ref = ItemRoles.adopt(stack, key)
            if (ref != null) {
                val draft = ItemDefinition.of(ref.id, kind, captured)
                ItemRoles.assign(ref, InvKeeperRoles.roleOf(kind), ItemRoles.withLegacy(InvKeeperRoles.values(draft), captured.takeIf { it.ref !is kr.inmc.core.item.ItemRef.Namespaced }))
                return get(ref.id) ?: draft
            }
        }
        val definition = ItemDefinition.of(key.lowercase(), kind, inv.itemResolver.capture(stack))
        put(definition)
        if (!retired) markDirty()
        return definition
    }

    /** 설정값을 바꾼 정의로 갈아끼운다. 커스텀아이템이 있으면 역할 값으로(종류가 바뀌어 역할이 바뀌면 옛 역할은 뗀다). */
    fun replace(definition: ItemDefinition) {
        if (hub && definition.item.ref is kr.inmc.core.item.ItemRef.Namespaced) {
            val ref = definition.item.ref as kr.inmc.core.item.ItemRef.Namespaced
            val role = InvKeeperRoles.roleOf(definition.kind)
            for (other in InvKeeperRoles.ALL) if (other != role && ItemRoles.rolesOf(ref).containsKey(other)) ItemRoles.assign(ref, other, null)
            ItemRoles.assign(ref, role, ItemRoles.withLegacy(InvKeeperRoles.values(definition), definition.legacy))
            return
        }
        put(definition)
        if (!retired) markDirty()
    }

    /** 등록 해제. 커스텀아이템이 있으면 아이템은 그대로 두고 인벤키퍼 역할만 뗀다. */
    fun unregister(key: String): Boolean {
        val removed = byKey[key.lowercase()] ?: return false
        val ref = removed.item.ref
        if (hub && ref is kr.inmc.core.item.ItemRef.Namespaced) {
            for (role in InvKeeperRoles.ALL) if (ItemRoles.rolesOf(ref).containsKey(role)) ItemRoles.assign(ref, role, null)
            return true
        }
        byKey.remove(key.lowercase())
        byMaterial[removed.item.material]?.remove(removed)
        removed.legacy?.let { byMaterial[it.material]?.remove(removed) }
        if (!retired) markDirty()
        return true
    }

    private fun put(definition: ItemDefinition) {
        byKey.put(definition.key, definition)?.let { old ->
            byMaterial[old.item.material]?.remove(old)
            old.legacy?.let { byMaterial[it.material]?.remove(old) }
        }
        byMaterial.getOrPut(definition.item.material) { ArrayList(2) }.add(definition)
        definition.legacy?.takeIf { it.material != definition.item.material }?.let { byMaterial.getOrPut(it.material) { ArrayList(2) }.add(definition) }
    }

    // --- 영속화 -----------------------------------------------------------------

    /** 메인 스레드에서 돈다. 커스텀아이템이 있으면 파일의 것을 옮기고(처음 한 번) 역할에서 다시 짓는다. */
    override fun read(config: YamlConfiguration) {
        byKey.clear()
        byMaterial.clear()
        val loaded = ArrayList<ItemDefinition>()
        val items = config.getConfigurationSection("items")
        for (key in items?.getKeys(false).orEmpty()) {
            val section = items!!.getConfigurationSection(key) ?: continue
            val definition = ItemDefinition.load(key.lowercase(), section)
            if (definition == null) {
                inv.logger.warning("아이템 '$key' 을(를) 읽지 못했습니다 - kind 와 item 값을 확인해주세요")
                continue
            }
            loaded += definition
        }
        if (hub) {
            if (file.isFile) migrate(loaded)
            rebuild()
            return
        }
        for (definition in loaded) put(definition)
    }

    /** 메인 스레드에서 돈다. */
    override fun write(config: YamlConfiguration) {
        val items = config.createSection("items")
        for (definition in byKey.values) {
            definition.save(items.createSection(definition.key))
        }
    }

    /**
     * 지급용 스택을 만든다. 참조를 풀 수 없으면 null.
     *
     * [recipient] 를 주면 **자체 각인**([ItemDefinition.selfBind])을 그 사람 이름으로 찍는다.
     * 보호권이나 각인기를 받자마자 그 사람 것이 되게 하는 설정이고, 받는 사람을 모르는
     * 자리(미리보기 아이콘 등)에서는 찍지 않는다.
     */
    fun create(definition: ItemDefinition, amount: Int = 1, recipient: Player? = null): ItemStack? {
        val stack = inv.itemResolver.create(definition.item, amount) ?: return null
        val selfBind = definition.selfBind
        if (recipient != null && selfBind.enabled) {
            inv.soulbinds.apply(stack, recipient.uniqueId, selfBind.strength)
        }
        return stack
    }
}

