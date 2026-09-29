package mindustry.ai;

import arc.files.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import static mindustry.Vars.*;

/**
 * Builds a synthetic "late game" save out of real player schematics: ore floors, one core,
 * schematics shelf-packed until the building target is reached. With unitsPerTeam, sharded units
 * spawn around the core and crux attackers in a corner, so the save also covers combat.
 * args: gen <out.msav> <schemDir> <seed> <mapSize> <targetBuilds> [unitsPerTeam]
 */
public class StressGen{
    public static void generate(String[] args){
        Fi out = new Fi(args[1]);
        Fi dir = new Fi(args[2]);
        long seed = Long.parseLong(args[3]);
        int size = Integer.parseInt(args[4]);
        int target = Integer.parseInt(args[5]);
        int unitsPerTeam = args.length > 6 ? Integer.parseInt(args[6]) : 0;
        Rand rand = new Rand(seed);

        Seq<Schematic> schems = new Seq<>();
        Seq<Fi> files = dir.findAll(f -> f.extEquals("msch"));
        files.sort((a, b) -> a.name().compareTo(b.name()));
        for(Fi f : files){
            try{
                Schematic s = Schematics.read(f);
                if(s.tiles.isEmpty() || s.width > 64 || s.height > 64) continue;
                if(s.tiles.contains(t -> t.block == null || t.block instanceof CoreBlock || !t.block.isVisible() && !t.block.privileged)) continue;
                schems.add(s);
            }catch(Throwable ignored){
            }
        }
        Log.info("usable schematics: @/@", schems.size, files.size);
        if(schems.isEmpty()) throw new IllegalArgumentException("no usable schematics in " + dir.absolutePath());

        Block[] ores = {Blocks.oreCopper, Blocks.oreLead, Blocks.oreTitanium, Blocks.oreCoal, Blocks.oreScrap, Blocks.air, Blocks.oreThorium};
        world.loadGenerator(size, size, tiles -> {
            for(int x = 0; x < size; x++){
                for(int y = 0; y < size; y++){
                    int region = (x / 37) * 31 + (y / 29) * 17;
                    Block floor = region % 5 == 0 ? Blocks.darksand : region % 11 == 0 ? Blocks.sand : Blocks.stone;
                    Block ore = ores[Math.floorMod(region * 7 + (x / 9 + y / 13) % 3, ores.length)];
                    tiles.set(x, y, new Tile(x, y, floor, ore, Blocks.air));
                }
            }
        });

        state.rules = new Rules();
        state.rules.waves = false;
        state.rules.waveTimer = false;
        state.rules.defaultTeam = Team.sharded;
        state.rules.waveTeam = Team.crux;
        state.rules.unitCap = 400;
        state.map = new mindustry.maps.Map(StringMap.of("name", "stress-" + seed));

        int c = size / 2;
        world.tile(c, c).setBlock(Blocks.coreNucleus, Team.sharded);
        CoreBuild core = (CoreBuild)world.tile(c, c).build;
        state.teams.registerCore(core);
        for(Item item : content.items()) core.items.set(item, 50000);

        //shelf packing of random schematics
        int x = 2, y = 2, rowH = 0, placed = 0;
        while(Groups.build.size() < target){
            Schematic s = schems.random(rand);
            if(x + s.width + 2 >= size){
                x = 2;
                y += rowH + 2;
                rowH = 0;
            }
            if(y + s.height + 2 >= size) break;
            //keep the core area free
            if(Math.abs(x + s.width / 2 - c) < s.width / 2 + 6 && Math.abs(y + s.height / 2 - c) < s.height / 2 + 6){
                x += s.width + 2;
                continue;
            }
            Schematics.place(s, x + s.width / 2, y + s.height / 2, Team.sharded, true);
            placed++;
            x += s.width + 2;
            rowH = Math.max(rowH, s.height);
        }

        if(unitsPerTeam > 0){
            UnitType[] types = {UnitTypes.dagger, UnitTypes.mace, UnitTypes.flare, UnitTypes.horizon, UnitTypes.poly, UnitTypes.mono, UnitTypes.nova, UnitTypes.atrax};
            for(int i = 0; i < unitsPerTeam; i++){
                UnitType type = types[i % types.length];
                type.spawn(Team.sharded, c * 8 + rand.range(300f), c * 8 + rand.range(300f));
                //attackers from a corner, they path to the sharded core through the base
                type.spawn(Team.crux, 40 * 8 + rand.range(200f), 40 * 8 + rand.range(200f));
            }
        }

        Log.info("placed @ schematics, builds=@, units=@, graphs=@", placed, Groups.build.size(), Groups.unit.size(), Groups.powerGraph.size());
        SaveIO.save(out);
        Log.info("saved @", out.absolutePath());
    }
}
