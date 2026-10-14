package mindustryX.features;

import arc.*;
import arc.struct.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.game.Teams.*;
import mindustry.net.Administration.*;
import mindustry.type.*;

/**
 * Optional load limits for dedicated servers, set with the {@code config} console command. Both are off by default,
 * and while off the game runs exactly as without them. They change gameplay when on, so they are meant for sandbox
 * servers that players try to lag out. Clients are not told about them: with a limit on, factory progress bars and
 * processor displays on clients can run ahead of the server until the next block sync.
 */
public class ServerLimits{
    public static final Config
    maxTeamUnits = new Config("maxTeamUnits", "MindustryX: max units per team that factories, reconstructors, assemblers, spawn abilities and logic may create, on top of the per-type cap. Player units and missiles are not counted. Teams without a unit cap (waves outside PvP) are not limited. 0 = off.", 0, ServerLimits::load),
    logicBudget = new Config("logicBudget", "MindustryX: instructions per server frame for all player processors together, world processors excluded. Over budget, every running processor slows down by the same factor; waiting processors do not count. 0 = off.", 0, ServerLimits::load);

    /** Read on every unit creation and processor update, so kept out of the settings map. */
    public static int teamUnits, logicIpt;
    /** Instructions requested by busy player processors since the last game tick, before scaling. */
    public static float logicDemand;
    /** Factor applied to the instruction accumulator of every player processor in this tick. */
    public static float logicScale = 1f;

    private static Seq<UnitType> uncapped;

    public static void init(){
        load();
        Events.run(Trigger.beforeGameUpdate, () -> {
            logicScale = logicIpt > 0 && logicDemand > logicIpt ? logicIpt / logicDemand : 1f;
            logicDemand = 0f;
        });
    }

    static void load(){
        teamUnits = Math.max(maxTeamUnits.num(), 0);
        logicIpt = Math.max(logicBudget.num(), 0);
        if(logicIpt == 0) logicScale = 1f;
    }

    public static boolean unitsAllowed(Team team){
        if(teamUnits <= 0) return true;
        if(uncapped == null) uncapped = Vars.content.units().select(t -> !t.useUnitCap);
        //unitCount is updated on every add and remove, unlike units, which is rebuilt once per tick
        TeamData data = team.data();
        int count = data.unitCount - data.players.count(p -> !p.dead());
        for(UnitType type : uncapped) count -= data.countType(type);
        return count < teamUnits;
    }
}
