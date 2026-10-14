package mindustry.ai;

import arc.files.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import static mindustry.Vars.*;

/**
 * Sandbox-style stress saves without schematics: the loads players use to take a server down.
 * args: scen &lt;out.msav&gt; &lt;kind&gt; &lt;n&gt; [seed]
 * <ul>
 * <li>stack: n sharded units spawned in a pile of ~3 tiles, like factory or /spawn spam, with a crux core
 * far away so ground units stream towards it as one blob; physics and tree queries degenerate.</li>
 * <li>stack1: like stack, but one unit type (daggers), so call sites are not megamorphic.</li>
 * <li>war: n units per team spread over the middle of the map, both teams have cores, so they fight. Wall lines
 * in between get destroyed and rebuilt from ghost plans, so builders and their followers (BuilderAI) are busy.</li>
 * <li>logic: n hyper processors running unit control loops over 600 sharded units, plus n/10 world
 * processors at setrate 1000 iterating every unit; the "logic lag machine".</li>
 * <li>spawn: n world processors spawning daggers for sharded every few ticks; checks the maxTeamUnits limit.</li>
 * </ul>
 */
public class ScenarioGen{
    static final UnitType[] mix = {UnitTypes.dagger, UnitTypes.mace, UnitTypes.fortress, UnitTypes.crawler, UnitTypes.nova, UnitTypes.quasar,
        UnitTypes.atrax, UnitTypes.spiroct, UnitTypes.flare, UnitTypes.horizon, UnitTypes.zenith, UnitTypes.poly, UnitTypes.mega, UnitTypes.mono};

    //unit control loop as seen on sandbox servers: bind, locate, sense, move, mine, repeat
    static final String unitLoop = """
        ubind @poly
        jump 0 strictEqual @unit null
        sensor ux @unit @x
        sensor uy @unit @y
        ulocate building core false @copper cx cy found core
        uradar enemy any any distance 0 1 target
        sensor h @unit @health
        op add tx @thisx 12
        op add ty @thisy 12
        ucontrol move tx ty 0 0 0
        ucontrol within cx cy 6 w 0
        ucontrol itemTake core @copper 10 0 0
        ubind @flare
        jump 0 strictEqual @unit null
        ucontrol approach @thisx @thisy 8 0 0
        ucontrol targetp target 1 0 0 0
        """;

    //world processor sweep over all sharded units, typical of map scripts and "admin" lag machines
    static final String worldLoop = """
        setrate 1000
        fetch unitCount n @sharded 0 @flare
        set i 0
        fetch unit u @sharded i @flare
        sensor h u @health
        sensor x u @x
        op add i i 1
        jump 3 lessThan i n
        fetch unitCount m @sharded 0 @poly
        set i 0
        fetch unit u @sharded i @poly
        sensor f u @flag
        op add f f 1
        op add i i 1
        jump 10 lessThan i m
        """;

    public static void generate(String[] args){
        Fi out = new Fi(args[1]);
        String kind = args[2];
        int n = Integer.parseInt(args[3]);
        Rand rand = new Rand(args.length > 4 ? Long.parseLong(args[4]) : 7);
        int size = kind.equals("war") ? 400 : 300;

        world.loadGenerator(size, size, tiles -> {
            for(int x = 0; x < size; x++){
                for(int y = 0; y < size; y++){
                    tiles.set(x, y, new Tile(x, y, (x / 23 + y / 19) % 4 == 0 ? Blocks.darksand : Blocks.stone, (x / 7 + y / 5) % 3 == 0 ? Blocks.oreCopper : Blocks.air, Blocks.air));
                }
            }
        });

        state.rules = new Rules();
        state.rules.infiniteResources = true;
        state.rules.waves = false;
        state.rules.waveTimer = false;
        state.rules.defaultTeam = Team.sharded;
        state.rules.waveTeam = Team.crux;
        state.rules.unitCap = 10000;
        state.map = new mindustry.maps.Map(StringMap.of("name", "scen-" + kind));
        //sandbox players power processors with power sources; cheat gives the same full efficiency without power graphs
        state.rules.teams.get(Team.sharded).cheat = true;

        int c = size / 2;
        core(c, c, Team.sharded);

        switch(kind){
            case "stack", "stack1" -> {
                core(size - 20, size - 20, Team.crux);
                for(int i = 0; i < n; i++){
                    (kind.equals("stack1") ? UnitTypes.dagger : mix[i % mix.length]).spawn(Team.sharded, (c - 30) * 8 + rand.range(12f), (c - 30) * 8 + rand.range(12f));
                }
            }
            case "war" -> {
                core(20, 20, Team.crux);
                for(int i = 0; i < 3; i++){
                    for(int j = 0; j < 160; j++){
                        world.tile(c - 12 + i * 3, c - 80 + j).setBlock(Blocks.titaniumWall, Team.sharded);
                        world.tile(c - 30 - i * 3, c - 80 + j).setBlock(Blocks.titaniumWall, Team.crux);
                    }
                }
                for(int i = 0; i < n; i++){
                    UnitType type = mix[i % mix.length];
                    type.spawn(Team.sharded, (c + 20) * 8 + rand.range(400f), (c + 20) * 8 + rand.range(400f));
                    type.spawn(Team.crux, (c - 40) * 8 + rand.range(400f), (c - 40) * 8 + rand.range(400f));
                }
            }
            case "logic" -> {
                core(size - 20, size - 20, Team.crux);
                for(int i = 0; i < 600; i++){
                    (i % 2 == 0 ? UnitTypes.poly : UnitTypes.flare).spawn(Team.sharded, c * 8 + rand.range(300f), c * 8 + rand.range(300f));
                }
                //hyper processors (3x3) in rows, each fed by a power source on its left
                int placed = 0;
                for(int y = 10; y < size - 10 && placed < n; y += 4){
                    for(int x = 10; x < size - 10 && placed < n; x += 5){
                        if(Math.abs(x - c) < 12 && Math.abs(y - c) < 12) continue;
                        world.tile(x - 2, y).setBlock(Blocks.powerSource, Team.sharded);
                        world.tile(x, y).setBlock(Blocks.hyperProcessor, Team.sharded);
                        ((LogicBuild)world.tile(x, y).build).updateCode(unitLoop);
                        placed++;
                    }
                }
                for(int i = 0; i < n / 10; i++){
                    Tile t = world.tile(c - 10 + i % 20, c + 8 + i / 20);
                    t.setBlock(Blocks.worldProcessor, Team.sharded);
                    ((LogicBuild)t.build).updateCode(worldLoop);
                }
            }
            case "spawn" -> {
                core(size - 20, size - 20, Team.crux);
                for(int i = 0; i < n; i++){
                    Tile t = world.tile(c - 10 + i % 20, c + 8 + i / 20);
                    t.setBlock(Blocks.worldProcessor, Team.sharded);
                    ((LogicBuild)t.build).updateCode("op add sx @thisx 30\nspawn @dagger sx @thisy 0 @sharded u\nwait 0.1");
                }
            }
            default -> throw new IllegalArgumentException("unknown scenario " + kind);
        }

        //pending rebuild plans: builder units take them and others follow active builders (BuilderAI), from tick one
        if(kind.startsWith("stack") || kind.equals("war")){
            for(int i = 0; i < 400; i++){
                state.teams.get(Team.sharded).plans.add(new Teams.BlockPlan(c - 40 + i % 40 * 2, c + 20 + i / 40 * 2, (short)0, Blocks.titaniumWall, null));
            }
        }

        Log.info("scenario @: units=@ builds=@", kind, mindustry.gen.Groups.unit.size(), mindustry.gen.Groups.build.size());
        SaveIO.save(out);
        Log.info("saved @", out.absolutePath());
    }

    static void core(int x, int y, Team team){
        world.tile(x, y).setBlock(Blocks.coreNucleus, team);
        CoreBuild core = (CoreBuild)world.tile(x, y).build;
        state.teams.registerCore(core);
        for(Item item : content.items()) core.items.set(item, 50000);
    }
}
