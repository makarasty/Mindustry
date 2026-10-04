package mindustryX.features.ui.comp

import arc.scene.Element
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import arc.scene.ui.layout.WidgetGroup
import kotlin.math.ceil

/**
 * 固定行高的虚拟滚动列表：只为可视区域创建并复用行。
 *
 * 放进 [ScrollPane] 使用（把实例传给 `Table.pane`），设置 [items]，并用 [bind] 填充行内容。
 * 行是绝对定位的孩子节点，[getPrefHeight] 提供整体滚动范围。
 */
class VirtualTable<T>(
    private val rowHeight: Float,
    private val bind: (row: Table, item: T, index: Int) -> Unit,
) : WidgetGroup() {
    var items: List<T> = emptyList()
        set(value) {
            field = value
            firstBound = -1
            invalidateHierarchy()
        }

    /** 点击某行时回调，传入该行当前绑定的元素。 */
    var onClick: ((T) -> Unit)? = null

    private val rows = ArrayList<Table>()
    private var scroller: ScrollPane? = null
    private var firstBound = -1

    /** 内容总高，决定滚动范围。 */
    override fun getPrefHeight(): Float = items.size * rowHeight

    override fun layout() {
        if (scroller == null) {
            var p: Element? = parent
            while (p != null && p !is ScrollPane) p = p.parent
            scroller = p as? ScrollPane
        }
        ensureRows()
        update()
    }

    override fun act(delta: Float) {
        super.act(delta)
        ensureRows()
        update()
    }

    private fun ensureRows() {
        val visible = ceil((scroller?.height ?: 0f) / rowHeight).toInt() + 2
        if (rows.size == visible) return
        while (rows.size < visible) {
            val row = Table()
            row.clicked {
                val item = row.userObject as? T
                if (item != null) onClick?.invoke(item)
            }
            rows += row
            addChild(row)
        }
        while (rows.size > visible && rows.size > 1) {
            removeChild(rows.removeAt(rows.size - 1))
        }
        //池大小变了，必须重绑所有行
        firstBound = -1
    }

    private fun update() {
        val s = scroller ?: return
        if (items.isEmpty()) {
            rows.forEach { it.visible = false }
            return
        }
        val first = (s.visualScrollY / rowHeight).toInt().coerceIn(0, items.size - 1)
        if (first == firstBound) return

        rows.forEachIndexed { i, row ->
            val index = first + i
            row.visible = index < items.size
            if (row.visible) {
                //scene2d y 轴向上，index 0 在最上面
                row.userObject = items[index]
                row.setBounds(0f, height - (index + 1) * rowHeight, width, rowHeight)
                bind(row, items[index], index)
            }
        }
        firstBound = first
    }
}
