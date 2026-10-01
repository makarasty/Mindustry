package mindustryX.features;

import arc.*;
import arc.files.*;
import arc.util.*;
import kotlin.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustryX.*;
import mindustryX.features.SettingsV2.*;
import mindustryX.features.ui.*;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

import static mindustry.Vars.*;
import static mindustryX.features.UIExt.i;

/**
 * 回放录制
 * 原作者 cong0707, 原文件路径 mindustry.arcModule.ReplayController
 * WayZer修改优化
 */
public class ReplayController{
    public static final String extension = "mrep";
    private static final CheckPref enable = new CheckPref("replayRecord");

    public static boolean replaying;
    public static float replayTime;
    private static final ArrayBlockingQueue<Pair<Float, Packet>> packets = new ArrayBlockingQueue<>(256);
    private static Thread replayThread;

    private static ReplayData.Writer writer;
    private static ReplayData.Reader reader;

    public static void init(){
        Events.run(EventType.Trigger.update, () -> {
            if(replaying && state.isMenu() && !netClient.isConnecting()){
                stopPlay();
            }
            updateReplay();
        });
        Events.on(ClientServerConnectEvent.class, (e) -> stopPlay());
    }

    public static void onConnect(String ip){
        if(!enable.get() || LogicExt.contentsCompatibleMode) return;
        if(replaying) return;
        var format = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
        var file = saveDirectory.child(format.format(new Date()) + "." + extension);
        ReplayData.Writer writer;
        try{
            writer = new ReplayData.Writer(file.write(false, 8192));
        }catch(Exception e){
            Log.err(i("创建回放出错!"), e);
            return;
        }
        boolean anonymous = Core.settings.getBool("anonymous", false);
        ReplayData header = new ReplayData(Version.build, new Date(), anonymous ? "anonymous" : ip, anonymous ? "anonymous" : Vars.player.name.trim());
        writer.writeHeader(header);
        Log.info(VarsX.bundle.recording(file.absolutePath()));
        ReplayController.writer = writer;
    }

    public static void onClientPacket(Packet p){
        //回放期间不能再录
        if(writer == null || replaying) return;
        if(p instanceof Disconnect){
            writer.close();
            writer = null;
            Log.info(i("录制结束"));
            return;
        }
        try{
            //TODO 不应该在主线程写数据包IO
            writer.writePacket(p);
        }catch(Exception e){
            net.disconnect();
            Log.err(e);
            Core.app.post(() -> ui.showException(i("录制出错!"), e));
        }
    }

    //replay

    public static void startPlay(Fi input){
        try{
            reader = new ReplayData.Reader(input);
            Log.infoTag("Replay", reader.getMeta().toString());
        }catch(Exception e){
            Core.app.post(() -> {
                ReplayWindow.showManagerDialog();
                ui.showException(i("读取回放失败!"), e);
            });
            return;
        }

        ReplayWindow.replayMeta = reader.getMeta();
        ReplayWindow.presetPosition();
        //旧 arc 格式的 version 不是游戏版本号，固定按 146 协议解析
        LogicExt.mockProtocol = reader.getArcOldFormat() ? 146 : reader.getMeta().getVersion();

        replaying = true;
        ui.loadfrag.show("@connecting");
        ui.loadfrag.setButton(ReplayController::stopPlay);

        logic.reset();
        net.reset();
        netClient.beginConnecting();
        Reflect.set(net, "active", true);
        packets.clear();
        startReplayReader(reader);
    }

    private static void startReplayReader(ReplayData.Reader reader){
        if(replayThread!=null){
            replayThread.interrupt();
        }
        replayThread = Threads.daemon("Replay Reader", () -> {
            try{
                while(replaying){
                    var info = reader.nextPacket();//EOF
                    Packet packet = reader.readPacket(info);
                    packets.put(new Pair<>(info.getOffset(), packet));
                }
            }catch(InterruptedException e){
                //ignore
            }catch(EOFException e){
                //TODO 游戏暂停，保活
            }catch(Exception e){
                replaying = false;
                stopPlay();
                Core.app.post(() -> {
                    ui.showException("Replay Error", e);
                });
            }finally{
                reader.close();
            }
        });
    }

    private static void updateReplay(){
        if(!replaying){
            replayTime = 0f;
            return;
        }
        replayTime += Time.delta;
        for(int budget = 256; budget > 0 && replaying; budget--){
            var p = packets.peek();
            if(p == null || replayTime < p.getFirst()) return;//yield
            p = packets.poll();
            if(p == null) return;
            try{
                var packet = p.getSecond();
                net.handleClientReceived(packet);
                if(packet instanceof WorldStream) enterSpectator();
            }catch(Exception e){
                stopPlay();
                net.handleException(e);
            }
        }
    }

    /** 世界加载完后 Vars.player 是录制者，改成观察者，录制者交给后续 snapshot 重建。 */
    private static void enterSpectator(){
        int oldId = player.id;
        player.remove();
        player.id = Integer.MAX_VALUE - 1;
        player.team(Team.derelict);
        player.add();
        netClient.clearRemovedEntity(oldId);
    }

    public static void seekTo(float time){
        if(time < replayTime){
            var reader2 = reader.reopen();
            if(reader2 == null) return;
            reader = reader2;

            if(replayThread!=null){
                replayThread.interrupt();
            }
            NetClient.worldDataBegin();
            packets.clear();
            startReplayReader(reader);
        }
        replayTime = time;
    }

    public static void stopPlay(){
        boolean wasActive = replaying;
        if(wasActive) Log.infoTag("Replay", "stop");
        replaying = false;
        LogicExt.mockProtocol = Version.build;
        ReplayWindow.replayMeta = null;
        if(replayThread!=null){
            replayThread.interrupt();
            replayThread = null;
        }
        if(!wasActive) return;

        net.disconnect();
        ui.loadfrag.hide();
        Core.app.post(() -> {
            logic.reset();
            ReplayWindow.showManagerDialog();
        });
    }


    /** 读取整条回放的包信息；没有回放或读失败返回 null。供 UI 统计使用，不向 UI 暴露文件。 */
    public static List<ReplayData.PacketInfo> allPacketsInfo(){
        Fi source = reader == null ? null : reader.getSource();
        if(source == null) return null;
        try(ReplayData.Reader r = new ReplayData.Reader(source)){
            return r.allPacket();
        }catch(Exception e){
            Log.err(e);
            return null;
        }
    }
}
