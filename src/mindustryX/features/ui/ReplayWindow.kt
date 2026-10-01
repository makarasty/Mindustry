package mindustryX.features.ui

import arc.Core
import arc.func.Prov
import arc.scene.ui.Label
import arc.scene.ui.ScrollPane
import arc.scene.ui.Slider
import arc.scene.ui.TextField
import arc.math.geom.Vec2
import arc.scene.ui.layout.Table
import arc.util.Strings
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import mindustryX.VarsX
import mindustryX.features.ProtocolMap
import mindustryX.features.ReplayController
import mindustryX.features.ReplayData
import mindustryX.features.UIExt.i
import mindustryX.features.ui.comp.VirtualTable
import java.text.SimpleDateFormat
import java.util.Locale

/** 回放控制窗口：显示回放信息，并提供跳转、统计、停止控制。 */
class ReplayWindow : Table(Tex.pane) {
    companion object {
        private var managerDialog: ReplayManagerDialog? = null

        @JvmField
        var replayMeta: ReplayData? = null

        /** OverlayUI 注册出来的窗口实例，在 UIExtKt 里赋值。 */
        @JvmField
        var window: OverlayUI.Window? = null

        /** startReplay 时首次预设窗口位置：水平居中，y 在屏幕高度 30% 处；用户改过就保留。 */
        @JvmStatic
        fun presetPosition() {
            val w = window ?: return
            if (w.data.modified) return
            val root = Core.scene?.root ?: return
            w.data.set(OverlayUI.WindowData(
                enabled = true,
                pinned = true,
                center = Vec2(root.width / 2f, root.height * 0.3f),
                constraintX = AdsorptionSystem.Constraint(AdsorptionSystem.Axis.X, "scene", AdsorptionSystem.ConstraintType.AlignCenter),
            ))
        }

        @JvmStatic
        fun showManagerDialog() {
            if (managerDialog == null) managerDialog = ReplayManagerDialog()
            managerDialog!!.show()
        }

        /** 回放统计。文件读取放到后台线程，避免卡住 UI。 */
        @JvmStatic
        fun showInfo() {
            val dialog = BaseDialog(i("回放统计"))
            val meta = replayMeta
            if (meta == null) {
                dialog.cont.add(i("未加载回放!"))
                dialog.addCloseButton()
                dialog.show()
                return
            }

            val summary = Label(i("读取回放头信息中..."))
            dialog.cont.add(summary).left().row()

            //按类型聚合
            val aggregate = Table()
            dialog.cont.add(aggregate).growX().row()

            //虚拟滚动时间线
            dialog.cont.add(i("时间线")).left().padTop(6f).row()
            val timeline = VirtualTable<ReplayData.PacketInfo>(rowHeight = 22f) { row, p, _ ->
                row.clearChildren()
                row.defaults().pad(2f)
                row.add(Strings.format("+@s", Strings.fixed(p.offset / 60f, 2))).left().width(160f)
                row.add(packetName(p.id)).left().growX()
                row.add("L=" + p.length).right().width(70f)
            }
            timeline.onClick = { p -> ReplayController.seekTo(p.offset) }
            val timelineCell = dialog.cont.pane(timeline)
            timelineCell.grow().row()
            timelineCell.get().setScrollingDisabled(true, false)

            Vars.mainExecutor.execute {
                val packets = ReplayController.allPacketsInfo()
                Core.app.post {
                    if (!dialog.isShown) return@post
                    if (packets == null) {
                        summary.setText(i("无法读取回放头信息"))
                        return@post
                    }
                    //旧格式没有 tail，退回扫到最后一条
                    val ticks = replayMeta?.tail?.totalTicks ?: packets.lastOrNull()?.offset ?: -1f
                    summary.setText(buildString {
                        append(VarsX.bundle.packetCount(packets.size))
                        if (ticks >= 0f) {
                            append('\n')
                            append(VarsX.bundle.playbackLength(formatDuration((ticks / 60f).toInt())))
                        }
                    })

                    //聚合，比逐条 dump 更能看出录像构成
                    val stats = packets.groupBy { packetName(it.id) }.entries
                        .map { e -> PacketStat(e.key, e.value.size, e.value.fold(0L) { sum, p -> sum + p.length }) }
                        .sortedByDescending { it.bytes }
                    aggregate.top().defaults().pad(3f).padLeft(6f)
                    aggregate.add(i("类型")).left()
                    aggregate.add(i("次数")).right()
                    aggregate.add(i("字节")).right()
                    aggregate.row()
                    for (s in stats) {
                        aggregate.add(s.name).left().growX()
                        aggregate.add(s.count.toString()).right()
                        aggregate.add(s.bytes.toString()).right()
                        aggregate.row()
                    }

                    timeline.items = packets
                }
            }

            dialog.addCloseButton()
            dialog.show()
        }

        private class PacketStat(val name: String, val count: Int, val bytes: Long)

        private fun packetName(id: Byte): String {
            val oldId = id.toInt() and 0xFF
            val name = ProtocolMap.version().mapping.getOrNull(oldId) ?: "???"
            return "$name(ID=$oldId)"
        }

        private fun formatDuration(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds / 60 % 60
            val s = seconds % 60
            return "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        }
    }

    private val input = TextField("")
    private val error = Label("")
    private var sliderDuration = -1f
    private var knownMax = 0f
    private var lastMeta: ReplayData? = null
    private var cachedMeta: ReplayData? = null
    private var createdText = ""
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)

    init {
        margin(4f)

        //单表两列，标签对齐
        table { t ->
            t.defaults().padBottom(2f)
            t.infoRow({ i("录制者") }, { replayMeta?.recordPlayer ?: "" })
            t.infoRow({ i("服务器") }, { replayMeta?.serverIp ?: "" })
            t.infoRow({ i("版本") }, { replayMeta?.version?.toString() ?: "" })
            t.infoRow({ i("创建时间") }, { createdText() })
            t.infoRow({ i("位置") }, { "${formatTime(ReplayController.replayTime)} / ${formatTime(replayMeta?.tail?.totalTicks ?: -1f)}" })
        }.growX().row()

        //进度条：拖动松手后跳转；老回放没有 tail，就用已见到的最大位置当上限
        val slider = Slider(0f, 1f, 1f, false)
        slider.moved { value -> if (!slider.isDragging) ReplayController.seekTo(value) }
        slider.update {
            val meta = replayMeta
            if (meta !== lastMeta) {
                lastMeta = meta
                knownMax = 0f
            }
            val pos = ReplayController.replayTime
            if (pos > knownMax) knownMax = pos
            val max = meta?.tail?.totalTicks?.takeIf { it > 0f } ?: knownMax
            if (!slider.isDragging && max != sliderDuration) {
                sliderDuration = max
                slider.setValue(0f, false)
                slider.setRange(0f, if (max > 0f) max else 1f)
                slider.setDisabled(max <= 0f)
            }
            if (!slider.isDragging) slider.setValue(pos, false)
        }
        add(slider).growX().padTop(6f).row()

        table { t ->
            t.label { i("跳转到(秒)") }.padRight(6f)
            t.add(input).width(80f)
            t.button(i("跳转")) { jump() }.padLeft(6f)
            t.button(Icon.info, Styles.clearNonei, Vars.iconMed) { showInfo() }
                .tooltip(i("回放统计")).padLeft(10f)
            t.button(Icon.cancel, Styles.clearNonei, Vars.iconMed) { ReplayController.stopPlay() }
                .tooltip(i("停止回放")).padLeft(6f)
        }.growX().row()

        input.setTextFieldListener { _, c -> if (c == '\n' || c == '\r') jump() }

        add(error).growX().left().row()
        error.visible { error.text.isNotEmpty() }
    }

    private fun createdText(): String {
        val meta = replayMeta
        if (meta !== cachedMeta) {
            cachedMeta = meta
            createdText = meta?.time?.let { dateFormat.format(it) } ?: ""
        }
        return createdText
    }

    private fun jump() {
        try {
            ReplayController.seekTo(input.text.trim().toFloat() * 60f)
            error.setText("")
        } catch (e: NumberFormatException) {
            error.setText(i("输入无效"))
        }
    }

    private fun Table.infoRow(name: Prov<CharSequence>, value: Prov<CharSequence>) {
        label(name).left().padRight(8f)
        label(value).left().growX()
        row()
    }

    private fun formatTime(ticks: Float): String {
        if (ticks < 0f) return "-"
        val total = (ticks / 60f).toInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}
