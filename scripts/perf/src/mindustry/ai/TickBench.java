package mindustry.ai;

import arc.*;
import arc.backend.headless.*;
import arc.files.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.async.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.net.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.storage.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/**
 * Headless tick bench and determinism harness. Lives in mindustry.ai to reach package-private pathfinder state.
 * <pre>
 * run:  TickBench &lt;save.msav&gt; &lt;ticks&gt; [hashOut|-] [warmup]
 * gen:  TickBench gen &lt;out.msav&gt; &lt;schematicsDir&gt; &lt;seed&gt; &lt;mapSize&gt; &lt;targetBuilds&gt; [unitsPerTeam]
 * </pre>
 * Timing mode (hashOut = -) runs the server tick as is and prints per-phase mean/p50/p99.
 * Hash mode writes one world hash per tick and makes the run reproducible: fixed delta, seeded RNGs,
 * pathfinder threads parked and stepped to completion on the main thread.
 * Run hash mode with -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 so identity-hashed sets
 * (Building proximity among others) iterate in a fixed order instead of a per-process random one.
 * <p>
 * System properties:
 * bench.chaos=N          mutate the world every N ticks (deterministic, identical in A and B runs)
 * bench.chaosActions=a,b restrict mutations: 0 kill, 1 place schematic, 2 team swap, 3 bridge relink,
 *                        4 unloader/sorter config, 5 remove, 6 copy processor code, 7 toggle processor link,
 *                        8 link or recode a processor then replace the linked building next tick,
 *                        9 unlink+relink a processor link in one tick then replace it, 10 silent Tile.setTeam
 * bench.mixedBridges=1   phase conveyors get linkSameType=false, like a map data patch
 * bench.schems=dir       schematics for action 1
 * bench.dumpTick=N       dump per-building hashes of tick N to dump_&lt;pid&gt;.txt (bench.dumpBytes=1 adds raw bytes)
 * bench.typeHash=1       per-block-type hash per tick to &lt;hashOut&gt;.types
 * bench.traceTypes=a,b   per-tick field dump of these block types to &lt;hashOut&gt;.trace
 * bench.census=1, bench.diag=1  block census and link/graph histograms in the report
 */
public class TickBench implements ApplicationListener{
    static final PerfCounter[] counters = {PerfCounter.entityMisc, PerfCounter.unitUpdate, PerfCounter.powerUpdate, PerfCounter.buildingUpdate, PerfCounter.bulletUpdate};

    static String savePath, hashOut;
    static int ticks, warmup;
    static String[] genArgs;

    int tick = -1;
    long[][] samples;
    long[] total, asyncWait, stateNs;
    PrintWriter hashWriter;
    Field gfxDelta;
    Field pfQueue, pfThreadList;
    Method pfUpdateTargets, pfUpdateFrontier;
    Method cpComplete, cpInner, cpFields, cpRecalc;

    public static void main(String[] args){
        if(args[0].equals("gen")){
            genArgs = args;
        }else{
            savePath = args[0];
            ticks = Integer.parseInt(args[1]);
            if(ticks <= 0) throw new IllegalArgumentException("ticks must be > 0");
            hashOut = args.length > 2 ? args[2] : "-";
            warmup = args.length > 3 ? Integer.parseInt(args[3]) : 300;
        }
        new HeadlessApplication(new TickBench(), 0f, t -> {
            t.printStackTrace();
            System.exit(1);
        });
    }

    @Override
    public void init(){
        //fresh settings every run: persisted settings change startup and with it the identity hash sequence
        Fi data = new Fi("benchdata/" + ProcessHandle.current().pid());
        data.deleteDirectory();
        Core.settings.setDataDirectory(data);
        headless = true;
        net = new Net(null);
        Vars.loadSettings();
        Vars.init();
        UI.loadColors();
        Fonts.loadContentIconsHeadless();
        content.createBaseContent();
        content.createModContent();
        content.init();
        bases.load();

        logic = new Logic();
        netServer = new NetServer();
        logic.init();
        Time.setDeltaProvider(() -> 1f);

        if(genArgs != null){
            StressGen.generate(genArgs);
            System.exit(0);
        }

        boolean deterministic = !hashOut.equals("-");
        try{
            gfxDelta = Class.forName("arc.mock.MockGraphics").getDeclaredField("deltaTime");
            pfQueue = Pathfinder.class.getDeclaredField("queue");
            pfThreadList = Pathfinder.class.getDeclaredField("threadList");
            pfUpdateTargets = Pathfinder.class.getDeclaredMethod("updateTargets", Pathfinder.Flowfield.class);
            pfUpdateFrontier = Pathfinder.class.getDeclaredMethod("updateFrontier", Pathfinder.Flowfield.class, long.class);
            cpComplete = ControlPathfinder.class.getDeclaredMethod("updateClustersComplete", int.class);
            cpInner = ControlPathfinder.class.getDeclaredMethod("updateClustersInner", int.class);
            cpFields = ControlPathfinder.class.getDeclaredMethod("updateFields", ControlPathfinder.FieldCache.class, long.class);
            cpRecalc = ControlPathfinder.class.getDeclaredMethod("recalculatePath", ControlPathfinder.PathRequest.class);
            for(AccessibleObject o : new AccessibleObject[]{gfxDelta, pfQueue, pfThreadList, pfUpdateTargets, pfUpdateFrontier, cpComplete, cpInner, cpFields, cpRecalc}){
                o.setAccessible(true);
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }

        //emulate a map data patch that mixes linkSameType between bridge types
        if(System.getProperty("bench.mixedBridges") != null) ((ItemBridge)mindustry.content.Blocks.phaseConveyor).linkSameType = false;

        //building field initializers (e.g. OverdriveBuild.charge) draw from the global RNG during load
        Mathf.rand.setSeed(41);
        SaveIO.load(new Fi(savePath));
        state.rules.sector = null;
        state.set(State.playing);

        if(deterministic){
            try{
                //park both pathfinder threads; they are stepped on the main thread instead
                for(Object pf : new Object[]{pathfinder, controlPath}){
                    Field f = pf.getClass().getDeclaredField("thread");
                    f.setAccessible(true);
                    Thread th = (Thread)f.get(pf);
                    if(th != null){
                        th.interrupt();
                        th.join();
                    }
                }
                //physics worlds each own an unseeded Rand
                Field pw = PhysicsProcess.class.getDeclaredField("physics");
                pw.setAccessible(true);
                Object[] worlds = (Object[])pw.get(unitPhysics);
                if(worlds != null){
                    for(int i = 0; i < worlds.length; i++){
                        Field rf = worlds[i].getClass().getDeclaredField("rand");
                        rf.setAccessible(true);
                        ((Rand)rf.get(worlds[i])).setSeed(1000 + i);
                    }
                }
                //static unseeded Rands that feed game state
                String[][] rands = {
                    {"mindustry.logic.GlobalVars", "rand"},
                    {"mindustry.ai.types.FlyingAI", "rand"},
                    {"mindustry.entities.Lightning", "random"},
                    {"mindustry.type.Weather", "rand"},
                    {"mindustry.entities.abilities.SuppressionFieldAbility", "rand"},
                    {"mindustry.world.blocks.units.RepairTurret", "rand"},
                    {"mindustry.type.Liquid", "rand"},
                    {"mindustry.content.Fx", "rand"},
                };
                for(String[] r : rands){
                    Field f = Class.forName(r[0]).getDeclaredField(r[1]);
                    f.setAccessible(true);
                    ((Rand)f.get(null)).setSeed(r[0].hashCode());
                }
                hashWriter = new PrintWriter(new FileWriter(hashOut));
            }catch(Exception e){
                throw new RuntimeException(e);
            }
        }
        Mathf.rand.setSeed(42);

        Log.info("Loaded @: @x@, builds=@, units=@, graphs=@", savePath, world.width(), world.height(), Groups.build.size(), Groups.unit.size(), Groups.powerGraph.size());

        samples = new long[counters.length][ticks];
        total = new long[ticks];
        asyncWait = new long[ticks];
        stateNs = new long[ticks];
    }

    @Override
    public void update(){
        try{
            gfxDelta.setFloat(Core.graphics, 1f / 60f);
        }catch(Exception e){
            throw new RuntimeException(e);
        }

        //first frame: let runnables posted during load settle
        if(tick < 0){
            tick = 0;
            return;
        }

        int t = tick - warmup;

        if(hashWriter != null){
            stepPathfinder();
            stepControlPath();
        }
        if(chaosEvery > 0 && tick % chaosEvery == 0) chaos();

        long s = Time.nanos();
        asyncCore.begin();
        long a = Time.nanos();
        logic.update();
        long b = Time.nanos();
        asyncCore.end();
        long e = Time.nanos();

        if(t >= 0){
            total[t] = e - s;
            asyncWait[t] = (e - b) + (a - s);
            stateNs[t] = (b - a) - PerfCounter.entityUpdate.latestValueNs();
            for(int i = 0; i < counters.length; i++){
                samples[i][t] = counters[i].latestValueNs();
            }
        }

        if(hashWriter != null){
            hashWriter.println(tick + " " + hashWorld());
        }

        tick++;
        if(t + 1 >= ticks){
            if(chaosEvery > 0) System.out.println("chaos actions with effect: " + chaosDone);
            report();
            if(hashWriter != null) hashWriter.close();
            if(traceOut != null) traceOut.close();
            if(typeOut != null) typeOut.close();
            System.exit(0);
        }
    }

    //region deterministic pathfinding

    @SuppressWarnings("unchecked")
    void stepPathfinder(){
        try{
            ((TaskQueue)pfQueue.get(pathfinder)).run();
            for(Pathfinder.Flowfield data : (Seq<Pathfinder.Flowfield>)pfThreadList.get(pathfinder)){
                if(data.dirty && data.frontier.size == 0){
                    pfUpdateTargets.invoke(pathfinder, data);
                    data.dirty = false;
                }
                //run to completion: deterministic, unlike the time-bounded thread
                pfUpdateFrontier.invoke(pathfinder, data, Long.MAX_VALUE / 4);
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    /** Mirrors ControlPathfinder.run(); the wall-clock invalidation check runs every 60 ticks instead. */
    void stepControlPath(){
        ControlPathfinder cp = controlPath;
        try{
            cp.queue.run();
            for(var it = cp.clustersToUpdate.iterator(); it.hasNext; ){
                int cluster = it.next();
                cpComplete.invoke(cp, cluster);
                cp.clustersToInnerUpdate.remove(cluster);
            }
            for(var it = cp.clustersToInnerUpdate.iterator(); it.hasNext; ){
                cpInner.invoke(cp, it.next());
            }
            cp.clustersToInnerUpdate.clear();
            cp.clustersToUpdate.clear();

            if(tick % 60 == 0){
                var it = cp.invalidRequests.iterator();
                while(it.hasNext()){
                    var request = it.next();
                    if(request.invalidated){
                        it.remove();
                        continue;
                    }
                    var field = cp.fields.get(FieldIndex.get(request.destination, request.costId, request.team));
                    if(field != null){
                        if(field.frontier.isEmpty()){
                            cp.fields.remove(field.mapKey);
                            Core.app.post(() -> cp.fieldList.remove(field));
                            for(var otherRequest : cp.threadPathRequests){
                                if(otherRequest.destination == request.destination){
                                    otherRequest.oldCache = field;
                                    if(otherRequest != request){
                                        cp.queue.post(() -> invoke(cpRecalc, cp, otherRequest));
                                    }
                                }
                            }
                            cp.queue.post(() -> invoke(cpRecalc, cp, request));
                            it.remove();
                        }
                    }else{
                        cp.queue.post(() -> invoke(cpRecalc, cp, request));
                        it.remove();
                    }
                }
            }

            for(var cache : cp.fields.values()){
                if(cache != null) cpFields.invoke(cp, cache, Long.MAX_VALUE / 4);
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    static void invoke(Method m, Object target, Object arg){
        try{
            m.invoke(target, arg);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    //endregion
    //region report

    void report(){
        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        if(System.getProperty("bench.diag") != null){
            IntIntMap links = new IntIntMap(), incoming = new IntIntMap(), graphs = new IntIntMap();
            int nullUnloaders = 0, unloaders = 0;
            for(Building b : Groups.build){
                if(b instanceof LogicBlock.LogicBuild l) links.increment(Math.min(l.links.size, 64) / 8 * 8);
                if(b instanceof ItemBridge.ItemBridgeBuild ib) incoming.increment(ib.incoming.size);
                if(b instanceof Unloader.UnloaderBuild u){
                    unloaders++;
                    if(u.sortItem == null) nullUnloaders++;
                }
            }
            for(var g : Groups.powerGraph) graphs.increment(Math.min(((PowerGraphUpdater)g).graph.all.size, 1024) / 16 * 16);
            sb.append("logic links hist(bucket8): ").append(links).append(nl)
                .append("bridge incoming hist: ").append(incoming).append(nl)
                .append("unloaders: ").append(unloaders).append(" null: ").append(nullUnloaders).append(nl)
                .append("power graph size hist(bucket16): ").append(graphs).append(nl);
        }
        if(System.getProperty("bench.census") != null){
            ObjectIntMap<String> census = new ObjectIntMap<>();
            for(Tile tile : world.tiles){
                if(tile.build != null && tile.build.tile == tile){
                    Class<?> c = tile.block().getClass();
                    while(c.isAnonymousClass()) c = c.getSuperclass();
                    census.increment(c.getSimpleName());
                }
            }
            Seq<String> keys = census.keys().toSeq();
            keys.sort(k -> -census.get(k));
            for(String k : keys.list().subList(0, Math.min(30, keys.size))) sb.append(k).append(' ').append(census.get(k)).append(nl);
        }
        sb.append(nl).append(Strings.format("=== @ ticks (after @ warmup) | builds=@ units=@ bullets=@ graphs=@", ticks, warmup, Groups.build.size(), Groups.unit.size(), Groups.bullet.size(), Groups.powerGraph.size())).append(nl);
        sb.append(String.format("%-16s %9s %9s %9s %9s", "phase", "mean ms", "p50 ms", "p99 ms", "share")).append(nl);
        double totalMean = mean(total);
        row(sb, "TOTAL", total, totalMean);
        row(sb, "stateUpdate(etc)", stateNs, totalMean);
        row(sb, "asyncWait", asyncWait, totalMean);
        for(int i = 0; i < counters.length; i++){
            row(sb, counters[i].name(), samples[i], totalMean);
        }
        System.out.println(sb);
    }

    static double mean(long[] arr){
        double s = 0;
        for(long l : arr) s += l;
        return s / arr.length;
    }

    static void row(StringBuilder sb, String name, long[] arr, double totalMean){
        long[] c = arr.clone();
        Arrays.sort(c);
        double m = mean(arr);
        sb.append(String.format("%-16s %9.3f %9.3f %9.3f %8.1f%%", name, m / 1e6, c[c.length / 2] / 1e6, c[(int)(c.length * 0.99)] / 1e6, m / totalMean * 100)).append(System.lineSeparator());
    }

    //endregion
    //region chaos: deterministic world mutations, identical in A and B runs

    final int chaosEvery = Integer.getInteger("bench.chaos", 0);
    final Rand chaosRand = new Rand(1234);
    final int[] chaosActions = System.getProperty("bench.chaosActions", "").isEmpty() ? new int[0] :
        Arrays.stream(System.getProperty("bench.chaosActions").split(",")).mapToInt(Integer::parseInt).toArray();
    static final int maxChaosAction = 10;
    int chaosDone, pendingReplace = -1;
    Seq<Schematic> chaosSchems;

    Building randomBuild(){
        for(int i = 0; i < 200; i++){
            Tile t = world.tiles.geti(chaosRand.random(world.tiles.width * world.tiles.height - 1));
            if(t.build != null && !(t.build instanceof CoreBlock.CoreBuild)) return t.build;
        }
        return null;
    }

    Building randomBuild(Class<?> type){
        for(int i = 0; i < 400; i++){
            Building b = randomBuild();
            if(type.isInstance(b)) return b;
        }
        return null;
    }

    void chaos(){
        if(pendingReplace != -1){
            Tile t = world.tile(pendingReplace);
            pendingReplace = -1;
            if(t != null && t.build != null) t.setBlock(t.block(), t.team(), t.build.rotation);
            return;
        }
        if(chaosSchems == null){
            chaosSchems = new Seq<>();
            String dir = System.getProperty("bench.schems");
            if(dir != null){
                for(Fi f : new Fi(dir).findAll(f -> f.extEquals("msch")).sort((a, b) -> a.name().compareTo(b.name()))){
                    try{
                        Schematic s = Schematics.read(f);
                        if(s.width <= 24 && s.height <= 24 && !s.tiles.contains(st -> st.block == null || st.block instanceof CoreBlock)) chaosSchems.add(s);
                    }catch(Throwable ignored){
                    }
                }
            }
        }
        int action = chaosActions.length == 0 ? chaosRand.random(maxChaosAction) : chaosActions[chaosRand.random(chaosActions.length - 1)];
        Building b = randomBuild();
        if(b == null) return;
        switch(action){
            case 0 -> {
                b.kill();
                chaosDone++;
            }
            case 1 -> {
                if(chaosSchems.any()){
                    Schematics.place(chaosSchems.random(chaosRand), b.tileX(), b.tileY(), b.team, true);
                    chaosDone++;
                }
            }
            case 2 -> {
                b.changeTeam(b.team == Team.sharded ? Team.derelict : Team.sharded);
                chaosDone++;
            }
            case 3 -> {
                //relink a random bridge to another bridge of the same type in range, or clear it
                if(randomBuild(ItemBridge.ItemBridgeBuild.class) instanceof ItemBridge.ItemBridgeBuild ib){
                    var block = (ItemBridge)ib.block;
                    Seq<Building> cands = new Seq<>();
                    boolean mixed = System.getProperty("bench.mixedBridges") != null;
                    for(Point2 d : Geometry.d4){
                        for(int r = 1; r <= (mixed ? 12 : block.range); r++){
                            Building c = world.build(ib.tileX() + d.x * r, ib.tileY() + d.y * r);
                            if(c != null && c != ib && (mixed ? c.block instanceof ItemBridge : c.block == block)) cands.add(c);
                        }
                    }
                    //mixed mode targets the other bridge type, the case where both ends disagree on validity
                    if(mixed && cands.contains(c -> c.block != block)) cands.retainAll(c -> c.block != block);
                    ib.configure(cands.isEmpty() || chaosRand.chance(0.15) ? Integer.valueOf(-1) : Integer.valueOf(cands.random(chaosRand).pos()));
                    chaosDone++;
                }
            }
            case 4 -> {
                for(int i = 0; i < 50; i++){
                    Building o = randomBuild();
                    if(o instanceof Unloader.UnloaderBuild || o instanceof Sorter.SorterBuild){
                        o.configure(chaosRand.chance(0.3) ? null : content.item(chaosRand.random(content.items().size - 1)));
                        chaosDone++;
                        break;
                    }
                }
            }
            case 6 -> {
                //copy another processor's code and links onto a random processor of the same type
                Building src = randomBuild(LogicBlock.LogicBuild.class), dst = randomBuild(LogicBlock.LogicBuild.class);
                if(src != null && dst != null && src != dst && dst.block == src.block){
                    dst.configure(src.config());
                    chaosDone++;
                }
            }
            case 7 -> {
                //toggle a processor link like a player tapping a building
                if(randomBuild(LogicBlock.LogicBuild.class) instanceof LogicBlock.LogicBuild lb){
                    Building target = world.build(lb.tileX() + chaosRand.range(8), lb.tileY() + chaosRand.range(8));
                    if(target != null && target != lb){
                        lb.configure(target.pos());
                        chaosDone++;
                    }
                }
            }
            case 8 -> {
                //link a nearby building or recode the processor, then replace a linked building on the next tick
                if(randomBuild(LogicBlock.LogicBuild.class) instanceof LogicBlock.LogicBuild lb){
                    Building target = null;
                    if(chaosRand.chance(0.5)){
                        target = world.build(lb.tileX() + chaosRand.range(5), lb.tileY() + chaosRand.range(5));
                        if(target != null && target != lb) lb.configure(target.pos());
                    }else if(lb.links.contains(l -> l.valid)){
                        var link = lb.links.select(l -> l.valid).random(chaosRand);
                        target = world.build(link.x, link.y);
                        lb.configure(lb.config());
                    }
                    if(target != null && target != lb && target.block.size == 1){
                        pendingReplace = target.pos();
                        chaosDone++;
                    }
                }
            }
            case 9 -> {
                //unlink and relink the same building in one tick (links.size unchanged), replace it next tick
                if(randomBuild(LogicBlock.LogicBuild.class) instanceof LogicBlock.LogicBuild lb && lb.links.contains(l -> l.valid)){
                    var link = lb.links.select(l -> l.valid).random(chaosRand);
                    Building target = world.build(link.x, link.y);
                    if(target != null && target.block.size == 1){
                        lb.configure(target.pos());
                        lb.configure(target.pos());
                        pendingReplace = target.pos();
                        chaosDone++;
                    }
                }
            }
            case 10 -> {
                //plugin-style silent team change, no changeTeam()
                b.tile.setTeam(b.team == Team.sharded ? Team.derelict : Team.sharded);
                chaosDone++;
            }
            case 5 -> {
                //remove a block without killing it, like deconstruction
                b.tile.remove();
                chaosDone++;
            }
            default -> throw new IllegalArgumentException("unknown chaos action " + action);
        }
    }

    //endregion
    //region hashing

    static class CrcStream extends OutputStream{
        final CRC32C crc = new CRC32C();
        ByteArrayOutputStream tee;

        @Override
        public void write(int b){
            crc.update(b);
            if(tee != null) tee.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len){
            crc.update(b, off, len);
            if(tee != null) tee.write(b, off, len);
        }
    }

    final CrcStream sink = new CrcStream();
    final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(sink, 1 << 16));
    final Writes writes = new Writes(out);
    final int dumpTick = Integer.getInteger("bench.dumpTick", -1);
    final ObjectMap<String, Long> typeHashes = System.getProperty("bench.typeHash") != null ? new ObjectMap<>() : null;
    final ObjectSet<String> traceTypes = System.getProperty("bench.traceTypes") == null ? null : ObjectSet.with(System.getProperty("bench.traceTypes").split(","));
    PrintWriter typeOut, traceOut;
    String lastBytes = "";

    long part(){
        try{
            out.flush();
        }catch(IOException e){
            throw new RuntimeException(e);
        }
        if(sink.tee != null){
            lastBytes = HexFormat.of().formatHex(sink.tee.toByteArray());
            sink.tee.reset();
        }
        long v = sink.crc.getValue();
        sink.crc.reset();
        return v;
    }

    static String fieldDump(Object o){
        StringBuilder sb = new StringBuilder();
        for(Field f : o.getClass().getDeclaredFields()){
            if(Modifier.isStatic(f.getModifiers()) || !f.getType().isPrimitive()) continue;
            try{
                f.setAccessible(true);
                sb.append(f.getName()).append('=').append(f.get(o)).append(' ');
            }catch(Exception ignored){
            }
        }
        return sb.toString();
    }

    /** Hashes tiles in fixed order and units by id, so group order does not leak into the hash. */
    String hashWorld(){
        try{
            if(traceTypes != null && traceOut == null){
                traceOut = new PrintWriter(new BufferedWriter(new FileWriter(hashOut + ".trace")));
                sink.tee = new ByteArrayOutputStream();
            }
            if(typeHashes != null && typeOut == null){
                typeOut = new PrintWriter(new BufferedWriter(new FileWriter(hashOut + ".types")));
            }
            PrintWriter dump = null;
            if(tick == dumpTick){
                dump = new PrintWriter(new FileWriter("dump_" + ProcessHandle.current().pid() + ".txt"));
                if(System.getProperty("bench.dumpBytes") != null) sink.tee = new ByteArrayOutputStream();
            }
            if(typeHashes != null) typeHashes.clear();

            long builds = 0;
            for(Tile tile : world.tiles){
                Building build = tile.build;
                if(build == null || build.tile != tile) continue;

                writes.i(tile.pos());
                writes.s(tile.blockID());
                if(build instanceof LogicBlock.LogicBuild lb){
                    //writeAll deflates the code on every call; hash the same state without compression
                    lb.writeBase(writes);
                    writes.i(lb.code.hashCode());
                    for(var link : lb.links){
                        writes.i(link.x);
                        writes.i(link.y);
                        writes.b(link.valid ? 1 : 0);
                    }
                    for(var v : lb.executor.vars){
                        writes.b(v.isobj ? 1 : 0);
                        if(v.isobj){
                            Object o = v.objval;
                            TypeIO.writeObject(writes, o instanceof String || o instanceof Building || o instanceof mindustry.ctype.Content || o instanceof Unit ? o : null);
                            //a stale reference to a replaced building has the same position; tell them apart
                            if(o instanceof Building ref){
                                writes.i(ref.id);
                                writes.b(ref.isValid() ? 1 : 0);
                            }
                        }else{
                            writes.d(v.numval);
                        }
                    }
                    writes.f(lb.accumulator);
                    writes.i(lb.ipt);
                    Object bound = lb.executor.unit == null ? null : lb.executor.unit.objval;
                    writes.i(bound instanceof Unit bu ? bu.id : bound == null ? -1 : -2);
                }else{
                    build.writeAll(writes);
                }
                //not saved, but processors can sense it
                if(build instanceof LogicDisplay.LogicDisplayBuild display) writes.l(display.operations);
                writes.f(build.efficiency);
                writes.f(build.potentialEfficiency);
                writes.f(build.timeScale());
                writes.b(build.enabled ? 1 : 0);

                long h = part();
                builds = builds * 31 + h;
                if(typeHashes != null) typeHashes.put(build.block.name, typeHashes.get(build.block.name, 0L) * 31 + h);
                if(traceTypes != null && traceTypes.contains(build.block.name)){
                    traceOut.println(tick + " " + build + " " + Long.toHexString(h) + " eff=" + build.efficiency + " " + fieldDump(build) + lastBytes);
                }
                if(dump != null){
                    StringBuilder extra = new StringBuilder();
                    if(build.liquids != null) build.liquids.each((liq, amount) -> extra.append(' ').append(liq.name).append('=').append(amount));
                    if(build.items != null) extra.append(" items=").append(build.items.total());
                    dump.println(build + " " + Long.toHexString(h) + extra + " " + lastBytes);
                }
            }

            Seq<Unit> units = Groups.unit.copy(new Seq<>());
            units.sort(u -> u.id);
            long unitHash = 0;
            for(Unit u : units){
                writes.i(u.id);
                u.write(writes);
                long h = part();
                unitHash = unitHash * 31 + h;
                if(dump != null) dump.println(u + " " + Long.toHexString(h));
            }

            Seq<Bullet> bullets = Groups.bullet.copy(new Seq<>());
            bullets.sort(bl -> bl.id);
            for(Bullet bl : bullets){
                writes.i(bl.id);
                writes.s(bl.type.id);
                writes.f(bl.x);
                writes.f(bl.y);
                writes.f(bl.damage);
                writes.f(bl.time);
            }
            Seq<Entityc> hazards = new Seq<>();
            Groups.all.each(en -> en instanceof Firec || en instanceof Puddlec, hazards::add);
            hazards.sort(en -> en.id());
            for(Entityc en : hazards){
                writes.i(en.id());
                if(en instanceof Fire f){
                    writes.i(f.tile.pos());
                    writes.f(f.time);
                    writes.f(f.lifetime);
                }else if(en instanceof Puddle p){
                    writes.i(p.tile.pos());
                    writes.s(p.liquid.id);
                    writes.f(p.amount);
                }
            }
            //graph-level values are not saved, but processors sense them; graph order is creation order in both runs
            for(var g : Groups.powerGraph){
                var graph = ((PowerGraphUpdater)g).graph;
                writes.f(graph.getLastPowerProduced());
                writes.f(graph.getLastPowerNeeded());
                writes.f(graph.getLastPowerStored());
                writes.f(graph.getLastCapacity());
                writes.f(graph.getLastScaledPowerIn());
                writes.f(graph.getLastScaledPowerOut());
                writes.f(graph.getPowerBalance());
            }
            writes.l(Mathf.rand.seed0);
            writes.l(Mathf.rand.seed1);
            writes.f(state.wavetime);
            writes.i(state.wave);
            long misc = part();

            if(typeHashes != null){
                for(String k : typeHashes.keys().toSeq().sort()) typeOut.println(tick + " " + k + " " + Long.toHexString(typeHashes.get(k)));
            }
            if(dump != null){
                dump.close();
                if(traceTypes == null) sink.tee = null;
            }
            return Long.toHexString(builds) + " " + Long.toHexString(unitHash) + " " + Long.toHexString(misc);
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    //endregion
}
