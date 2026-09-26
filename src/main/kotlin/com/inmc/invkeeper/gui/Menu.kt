package com.inmc.invkeeper.gui

import com.inmc.invkeeper.InvKeeper
import net.kyori.adventure.text.Component

/**
 * core 의 [kr.inmc.core.gui.Menu] 에 이 플러그인의 서비스 로케이터를 다시 붙인 얇은 층.
 *
 * core 는 [InvKeeper] 를 알지 못하고 알 필요도 없다. 반대로 이 플러그인의 화면들은 `inv` 로
 * 레지스트리·설정에 닿아야 한다. 그 둘을 잇는 것이 이 파일의 전부다.
 */
abstract class Menu(
    protected val inv: InvKeeper,
    size: Int,
    title: Component,
) : kr.inmc.core.gui.Menu(size, title) {

    /** 리로드 때 열린 화면을 닫는 청소가 이 값으로 우리 것을 가려낸다. */
    override val owner: Any get() = inv
}
