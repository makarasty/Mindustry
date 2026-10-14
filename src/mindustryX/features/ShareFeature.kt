package mindustryX.features

import arc.Core
import arc.math.Mathf
import arc.math.geom.Vec2
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.TextButton
import arc.scene.ui.layout.Table
import arc.util.Http
import arc.util.Log
import arc.util.Strings
import mindustry.Vars
import mindustry.content.Items
import mindustry.content.StatusEffects
import mindustry.core.UI
import mindustry.ctype.UnlockableContent
import mindustry.entities.Units
import mindustry.game.Schematic
import mindustry.gen.Icon
import mindustry.gen.Iconc
import mindustry.gen.Player
import mindustry.gen.Tex
import mindustry.type.Item
import mindustry.type.UnitType
import mindustry.ui.Fonts
import mindustry.ui.Styles
import mindustry.ui.fragments.ChatFragment
import mindustryX.VarsX
import mindustryX.features.UIExt.i
import mindustryX.features.UIExtKt.showFloatSettingsPanel
import mindustryX.features.ui.ArcMessageDialog
import mindustryX.features.ui.FormatDefault.duration
import mindustryX.features.ui.FormatDefault.format
import mindustryX.features.ui.comp.GridTable

object ShareFeature {
    private fun tag(icon: Char) = "<MDTX $icon>"

    @JvmStatic
    fun send(icon: Char, message: String?) {
        UIExt.sendChatMessage("${tag(icon)}$message")
    }

    @JvmStatic
    fun at(playerName: String?) {
        send('@', VarsX.bundle.atPlayer(playerName))
    }

    @JvmStatic
    fun shareSchematic(s: Schematic?) {
        uploadPasteBin(Vars.schematics.writeBase64(s)) { url ->
            if (url == null) return@uploadPasteBin
            val code = url.substring(url.lastIndexOf('/') + 1)
            send(Iconc.paste, "<ARCxMDTX><Schem>[black]一坨兼容[] $code")
        }
    }

    private fun uploadPasteBin(content: String, callback: (String?) -> Unit) {
        val req = Http.post("https://pastebin.com/api/api_post.php", "api_dev_key=sdBDjI5mWBnHl9vBEDMNiYQ3IZe0LFEk&api_option=paste&api_paste_expire_date=10M&api_paste_code=$content")
        req.submit { res ->
            val code = res!!.getResultAsString()
            Core.app.post { callback(code) }
        }
        req.error {
            Core.app.post {
                Vars.ui.showException(i("上传失败，再重试一下？"), it)
                Core.app.post { callback(null) }
            }
        }
    }

    @JvmStatic
    fun shareSchematicClipboard(schem: Schematic) {
        uploadPasteBin(Vars.schematics.writeBase64(schem)) { link: String? ->
            val msg = VarsX.bundle.shareSchematic(
                name = schem.name(),
                playerName = Vars.player.name,
                requirements = buildString {
                    val arr = schem.requirements()
                    for (stack in arr) {
                        append(stack.item.emoji()).append(stack.item.localizedName).append(stack.amount).append("|")
                    }
                },
                powerInfo = buildString {
                    val cons = schem.powerConsumption() * 60
                    val prod = schem.powerProduction() * 60
                    if (!Mathf.zero(prod)) {
                        append("+").append(Strings.autoFixed(prod, 2))
                        if (!Mathf.zero(cons)) {
                            append("|")
                        }
                    }
                    if (!Mathf.zero(cons)) {
                        append("-").append(Strings.autoFixed(cons, 2))
                    }
                },
                link = link, code = Vars.schematics.writeBase64(schem)
            )
            Core.app.setClipboardText(Strings.stripColors(msg))
            UIExt.announce(i("已保存至剪贴板"))
        }
    }

    @JvmStatic
    fun shareContent(content: UnlockableContent, description: Boolean) {
        val msg = buildString {
            append(content.localizedName).append(content.emoji())
            append("(").append(content.name).append(")")
            if (content.description != null && description) {
                append(": ").append(content.description)
            }
        }
        send(Iconc.info, msg)
    }

    //因为ArcMessageDialog共用了，所以单独提取出来
    fun waveInfo(wave: Int) = buildString {
        val spawner = Vars.spawner
        append(VarsX.bundle.waveContains(spawner.countGroundSpawns(), spawner.countFlyerSpawns()))

        for (group in Vars.state.rules.spawns) {
            val count = group.getSpawned(wave - 1)
            if (count == 0) continue
            append(group.type.emoji()).append("(")
            if (group.effect !== StatusEffects.none && group.effect != null) {
                append(group.effect.emoji()).append("|")
            }
            group.getShield(wave - 1).takeIf { it > 0 }?.let { shield ->
                append(format(shield)).append("|")
            }
            append(count).append(")")
        }
    }

    @JvmStatic
    fun shareWaveInfo(wave: Int) {
        if (!Vars.state.rules.waves) return

        val msg = buildString {
            append(VarsX.bundle.waveTitle(wave))

            if (wave >= Vars.state.wave) {
                val timer = (Vars.state.wavetime + (wave - Vars.state.wave) * Vars.state.rules.waveSpacing).toInt()
                append(VarsX.bundle.waveEta(wave - Vars.state.wave, duration(timer.toFloat() / 60)))
            }

            append("：").append(waveInfo(wave))
        }
        send(Iconc.waves, msg)
    }

    /** 把左上角当前显示的目标/状态发到聊天栏 */
    @JvmStatic
    fun shareObjective() {
        val text = objectiveText()
        if (text.isNotEmpty()) send(Iconc.fileText, text)
    }

    /** 复刻左上角状态面板的文本，逻辑见 HudFragment.makeStatusTable；换行换成 " | " 便于聊天显示 */
    private fun objectiveText(): String {
        val state = Vars.state

        //mission 覆盖一切
        val mission = state.rules.mission
        if (!mission.isNullOrEmpty()) return mission

        val builder = StringBuilder()

        //其次是进行中的 objectives
        for (obj in state.rules.objectives) {
            if (!obj.qualified() || obj.hidden) continue
            val text = obj.text() ?: continue
            if (text.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append(" | ")
            builder.append(UI.formatIcons(text))
        }
        if (builder.isNotEmpty()) return builder.toString()

        val player = Vars.player
        if (!player.team().activateUnitFactories()) {
            val delay = Math.max(state.rules.unitActivationDelay(player.team()) - state.tick, 0.0)
            builder.append("[lightgray]").append(Core.bundle.format("rules.unitfactoryactivation.objective", "[accent]" + UI.formatTime(delay.toFloat()))).append("[white]")
        }

        if (!state.rules.waves && state.rules.attackMode) {
            var cores = 0
            for (data in state.teams.present) {
                if (data.team != player.team()) cores += data.cores.size
            }
            cores = Math.max(cores, 1)
            builder.append(Core.bundle.format(if (cores > 1) "wave.enemycores" else "wave.enemycore", cores))
            return builder.toString()
        }

        //游戏结束后不再显示状态
        if (state.afterGameOver && state.isCampaign()) return builder.toString()

        if (!state.rules.waves) {
            if (state.isCampaign()) builder.append("[lightgray]").append(Core.bundle.get("sector.curcapture"))
            return builder.toString()
        }

        if (builder.isNotEmpty()) builder.append(" | ")
        if (state.rules.winWave > 1 && state.rules.winWave >= state.wave) {
            builder.append(Core.bundle.format("wave.cap", state.wave, state.rules.winWave))
        } else {
            builder.append(Core.bundle.format("wave", state.wave))
        }

        if (state.enemies > 0) {
            builder.append(" | ").append(Core.bundle.format(if (state.enemies == 1) "wave.enemy" else "wave.enemies", state.enemies))
        }

        if (state.rules.waveTimer) {
            builder.append(" | ").append(if (Vars.logic.isWaitingWave()) Core.bundle.get("wave.waveInProgress") else Core.bundle.format("wave.waiting", waveTime((state.wavetime / 60).toInt())))
        } else if (state.enemies == 0) {
            builder.append(" | ").append(Core.bundle.get("waiting"))
        }

        return builder.toString()
    }

    /** wave.waiting 的时间串，见 HudFragment 里的 waitingf */
    private fun waveTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        val sec = if (s < 10) "0$s" else s.toString()
        return if (m > 0) "$m:$sec" else "$s"
    }

    data class PowerInfo(val balance: Float, val stored: Float, val capacity: Float, val produced: Float, val need: Float, val satisfaction: Float)
    data class TeamItemInfo(val amount: Int, val delta: Int)

    @JvmStatic
    fun shareTeamPower() {
        val info = UIExt.coreItems.powerInfo()
        //电力: +xxx K/s 电力储存: xxx M/ xxx M
        val msg = buildString {
            append(info.balance.let { Core.bundle.format("bar.powerbalance", (if (it >= 0) "[accent]+" else "[red]") + format(it.toLong()) + "[]") })
            if (info.satisfaction < 1) append(" [gray]").append((info.satisfaction * 100).toInt()).append("%[]")
            append("  ")
            append(Core.bundle.format("bar.powerstored", format(info.stored.toLong()), format(info.capacity.toLong())))
        }
        send(Iconc.power, msg)
    }

    @JvmStatic
    fun openShareItemDialog() {
        showFloatSettingsPanel { table ->
            val grid = GridTable().apply {
                defaults().size(Vars.iconMed).pad(4f)
                for (item in Vars.content.items()) {
                    if (!UIExt.coreItems.usedItems.contains(item)) continue
                    button(TextureRegionDrawable(item.uiIcon), Styles.clearNonei, Vars.iconMed) { shareTeamItem(item) }
                }
            }
            table.add(grid).growX().maxWidth(320f).row()
        }
    }

    fun shareTeamItem(item: Item) {
        if (Vars.player.dead() || Vars.player.team().core() == null) return
        val (amount, delta) = UIExt.coreItems.itemInfo(item)
        send(
            item.emoji().firstOrNull() ?: Iconc.itemCopper,
            VarsX.bundle.mdtxShareItem(
                item.localizedName,
                (if (amount > 100) format(amount.toLong()) else "[red]$amount[]"),
                (if (delta > 0) "[accent]+" else "[red]") + format(delta.toLong()) + "[]"
            )
        )
    }

    @JvmStatic
    fun openShareUnitDialog() {
        showFloatSettingsPanel { table ->
            val grid = GridTable().apply {
                defaults().size(Vars.iconMed).pad(4f)
                for (unit in Vars.content.units()) {
                    if (!UIExt.coreItems.usedUnits.contains(unit)) continue
                    button(TextureRegionDrawable(unit.uiIcon), Styles.clearNonei, Vars.iconMed) { shareTeamUnit(unit) }
                }
            }
            table.add(grid).growX().maxWidth(320f).row()
        }
    }

    fun shareTeamUnit(unit: UnitType) {
        if (Vars.player.dead() || Vars.player.team().core() == null) return
        val count = Vars.player.team().data().countType(unit)
        val limit = Units.getCap(Vars.player.team())
        val color = (if (count == limit) "orange" else if (count < 10) "red" else "accent")
        send(
            unit.emoji().firstOrNull() ?: Iconc.units,
            VarsX.bundle.mdtxShareUnit(unit.localizedName, "[$color]$count[]", limit)
        )
    }

    fun newShareTable() = Table(Styles.black3).apply {
        defaults().size(Vars.iconMed)
        val underlineToggleT = TextButton.TextButtonStyle().apply {
            font = Fonts.def
            up = Styles.none
            over = Tex.underline
            checked = Tex.underlineOver //Over是黄色的
        }
        button("T", underlineToggleT) { Vars.ui.chatfrag.nextMode() }
            .checked { _ -> Vars.ui.chatfrag.mode == ChatFragment.ChatMode.team }.tooltip(i("前缀添加/t"))
        button(Icon.zoomSmall, Styles.clearNonei) { MarkerType.lockOnLastMark() }
            .tooltip(i("锁定上个标记点"))

        add("♐>").padRight(18f)
        button(Icon.mapSmall, Styles.clearNonei, Vars.iconMed) { MarkerType.toggleMarkHitterUI() }.tooltip(i("标记地图位置"))
        button(Icon.wavesSmall, Styles.clearNonei, Vars.iconMed) { shareWaveInfo(Vars.state.wave) }.tooltip(i("分享波次信息"))
        button(Icon.fileTextSmall, Styles.clearNonei, Vars.iconMed) { shareObjective() }.tooltip(i("标记当前任务"))
        button(Icon.powerSmall, Styles.clearNonei, Vars.iconMed) { shareTeamPower() }.tooltip(i("分享电力情况"))
        button(TextureRegionDrawable(Items.copper.uiIcon), Styles.clearNonei, Vars.iconSmall) { openShareItemDialog() }.tooltip(i("分享库存情况"))
        button(Icon.unitsSmall, Styles.clearNonei, Vars.iconMed) { openShareUnitDialog() }.tooltip(i("分享单位数量"))
    }

    private fun resolveAt(message: String, sender: Player?): Boolean {
        var message = message
        if (!message.contains("<AT>") && !message.contains(tag('@'))) return false

        //Remove prefix
        message = message.substringAfter("<AT>").substringAfter(tag('@'))
        if (message.contains(Vars.player.name)) {
            if (sender != null) Vars.ui.announce(VarsX.bundle.atNoticeFrom(sender.name), 10f)
            else Vars.ui.announce(i("[orange]你被戳了一下，请注意查看信息框哦~"), 10f)
        }

        return true
    }

    private fun resolveSchematicShare(message: String, sender: Player?): Boolean {
        if (!ArcOld.schematicShare.get()) return false

        var code = if (message.contains(tag(Iconc.paste))) {
            message.substringAfter(tag(Iconc.paste))
        } else if (message.contains("<Schem>")) {
            message.substringAfter("<Schem>")
        } else return false

        code = code.substringAfterLast(' ')
        if (code.isEmpty()) return false

        Http.get("https://pastebin.com/raw/$code") { res ->
            val content = res.getResultAsString().replace(" ", "+")
            Core.app.post {
                runCatching { Vars.ui.schematics.readShare(content, sender) }
                    .onFailure { Log.err("Fail read schematic share: ", it) }
            }
        }
        return true
    }

    private val posPattern = Regex("\\((\\d+),(\\d+)\\)")
    private fun resolvePositionShare(message: String): Vec2? {
        val match = posPattern.find(message) ?: return null
        return Vec2(match.groupValues[1].toFloat(), match.groupValues[2].toFloat())
    }

    @JvmStatic
    fun resolve(message: String, sender: Player?) {
        when {
            resolvePositionShare(message)?.also {
                ArcMessageDialog.Msg(ArcMessageDialog.Type.markLoc, message, sender?.name, it).add()
                MarkerType.newMarkFromChat(message, it)
            } != null -> Unit

            resolveAt(message, sender) -> {
                ArcMessageDialog.Msg(ArcMessageDialog.Type.markPlayer, message, sender).add()
            }

            resolveSchematicShare(message, sender) -> {}
            else -> {
                if (sender == null) {
                    ArcMessageDialog.Msg(ArcMessageDialog.Type.serverMsg, message).add()
                } else {
                    ArcMessageDialog.Msg(ArcMessageDialog.Type.chat, message, sender).add()
                }
            }
        }
    }
}
