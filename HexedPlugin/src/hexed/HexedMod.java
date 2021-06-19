package hexed;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import hexed.HexData.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.core.NetServer.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.maps.*;
import mindustry.maps.Map;
import mindustry.mod.*;
import mindustry.net.Packets.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

import static arc.util.Log.*;
import static mindustry.Vars.*;

public class HexedMod extends Plugin{

    public static final float spawnDelay = 60 * 4;

    public static final float healthRequirement = 3500;

    public static final int itemRequirement = 210;

    public static final int messageTime = 1;

    private final static int roundTime = 60 * 60 * 180;

    private final static int leaderboardTime = 60 * 60 * 2;

    private final static int updateTime = 60 * 2;

    private final static int winCondition = 10;

    private final static int timerBoard = 0, timerUpdate = 1, timerWinCheck = 2;

    private Interval interval = new Interval(5);

    private HexData data;
    private boolean restarting = false, registered = false;

    private Schematic start;
    private double counter = 0f;
    private int lastMin;

    @Override
    public void init(){
        start = Schematics.readBase64("bXNjaAF4nE2RUW6DMBBE1zZgbKJWOQj36F/PQIlVRSIYGZI2t++Opx+JFB7s7jxjLIO8W2nW6ZbEfaRfGS5pn8t1O655FRnkPOdtS2X8mZZlXKbynaSf8/pIz1xkeGnKsGftj9u0pkXafVoeWU4lbdNVi/m6HnKac0njep+XdN/l/DL/b+5uab2kIv19XfKEO/81HUcqTxH51L8YqT/Hu5boCE/0ROBkFAucxNpatkZRc4Y5w5xhziCHyTcAUctn5xSOaMQZRbVYWiwtlhYLS6OIWNPq6gay6nRwtlCJwUVlnao8e3WkwYjXolZ6fdKRoJfY4gOgXn0Nh1G0UaFTQESvZa9DLygaTKmrY7tj23N7HttrFHV7HiN4nwGv6rmWZ6DnB+zxakBHeKIngliniNherxaDXI0Hrhd4ioGnGCgLlAXKAmUBMkxGop5igMxIZC4yF5mLzEXk/gABDzs6");

        Events.run(Trigger.update, () -> {
            if(active()){
                data.updateStats();

                for(Player player : Groups.player){
                    if(player.team() != Team.derelict && player.team().cores().isEmpty()){
                        player.clearUnit();
                        killTeam(player.team());
                        Call.sendMessage("[#75ddfb]" + player.name + "[#ffffff] has been eliminated!");
                        Call.infoMessage(player.con, "Your cores have been destroyed. You are defeated.");
                        player.team(Team.derelict);
                    }

                    if(player.team() == Team.derelict){
                        player.clearUnit();
                    }else if(data.getControlled(player).size == data.hexes().size){
                        endGame();
                        break;
                    }
                }

                int minsToGo = (int)(roundTime - counter) / 60 / 60;
                if(minsToGo != lastMin){
                    lastMin = minsToGo;
                }

                if(interval.get(timerBoard, leaderboardTime)){
                    Call.infoToast(getLeaderboard(), 15f);
                }

                if(interval.get(timerUpdate, updateTime)){
                    data.updateControl();
                }

                if(interval.get(timerWinCheck, 60 * 2)){
                    Seq<Player> players = data.getLeaderboard();
                    if(!players.isEmpty() && data.getControlled(players.first()).size >= winCondition && players.size > 1 && data.getControlled(players.get(1)).size <= 1){
                        endGame();
                    }
                }

                counter += Time.delta;

                if(counter > roundTime && !restarting){
                    endGame();
                }
            }else{
                counter = 0;
            }
        });

        Events.on(BlockDestroyEvent.class, event -> {
            if(event.tile.block() instanceof CoreBlock){
                Hex hex = data.getHex(event.tile.pos());

                if(hex != null){
                    hex.spawnTime.reset();
                    hex.updateController();
                }
            }
        });

        Events.on(PlayerLeave.class, event -> {
        	for(Player player : Groups.player){
        		player.sendMessage("[#ed6d6d]- [#75ddfb]" + event.player.name + " [#ffffff]has disconnected!");
            }
        	Log.info(event.player.name + " (" + event.player.uuid() + ") has disconnected!");
            if(active() && event.player.team() != Team.derelict){
            	killTeam(event.player.team());
            }
        });

        Events.on(PlayerJoin.class, event -> {
        	for(Player player : Groups.player){
        		player.sendMessage("[#6ced7d]+ [#75ddfb]" + event.player.name + " [#ffffff]has connected!");
            }
        	Log.info(event.player.name + " (" + event.player.uuid() + ") has connected!");
        	Call.infoMessage(event.player.con, "[#ffffff]Welcome to [#75ddfb]HexPvP [#ffffff]server!\n\n[#75ddfb]- [#ffffff]Our Discord server: [#75ddfb]https://discord.gg/UGHHaynvt9\n[#75ddfb]- [#ffffff]Follow the server rules, enjoy the game!");
        	
            if(!active() || event.player.team() == Team.derelict) return;

            Seq<Hex> copy = data.hexes().copy();
            copy.shuffle();
            Hex hex = copy.find(h -> h.controller == null && h.spawnTime.get());

            if(hex != null){
                loadout(event.player, hex.x, hex.y);
                Core.app.post(() -> data.data(event.player).chosen = false);
                hex.findController();
                if (event.player.con.mobile) {
                    Call.infoMessage(event.player.con, "Your core is located at: " + Math.round(event.player.team().core().x / 8) + ", " + Math.round(event.player.team().core().y / 8) + ".");
                }
            }else{
                Call.infoMessage(event.player.con, "There are currently no empty hex spaces available.\nAssigning into spectator mode.");
                event.player.unit().kill();
                event.player.team(Team.derelict);
            }

            data.data(event.player).lastMessage.reset();
        });

        Events.on(ProgressIncreaseEvent.class, event -> updateText(event.player));

        Events.on(HexCaptureEvent.class, event -> updateText(event.player));

        Events.on(HexMoveEvent.class, event -> updateText(event.player));

        TeamAssigner prev = netServer.assigner;
        netServer.assigner = (player, players) -> {
            Seq<Player> arr = Seq.with(players);

            if(active()){
                for(Team team : Team.all){
                    if(team.id > 5 && !team.active() && !arr.contains(p -> p.team() == team) && !data.data(team).dying && !data.data(team).chosen){
                        data.data(team).chosen = true;
                        return team;
                    }
                }
                Call.infoMessage(player.con, "There are currently no empty hex spaces available.\nAssigning into spectator mode.");
                return Team.derelict;
            }else{
                return prev.assign(player, players);
            }
        };
    }

    void updateText(Player player){
        HexTeam team = data.data(player);

        StringBuilder message = new StringBuilder("[white]Hex #" + team.location.id + "\n");

        if(!team.lastMessage.get()) return;

        if(team.location.controller == null){
            if(team.progressPercent > 0){
                message.append("[lightgray]Capture progress: [accent]").append((int)(team.progressPercent)).append("%");
            }else{
                message.append("[lightgray](Empty)");
            }
        }else if(team.location.controller == player.team()){
            message.append("[yellow](Captured)");
        }else if(team.location != null && team.location.controller != null && data.getPlayer(team.location.controller) != null){
            message.append("[#").append(team.location.controller.color).append("]Captured by ").append(data.getPlayer(team.location.controller).name);
        }else{
            message.append("(Unknown)");
        }

        Call.setHudText(player.con, message.toString());
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("hexed", "Begin hosting with the Hexed gamemode.", args -> {
            if(!state.is(State.menu)){
                Log.err("Stop the server first.");
                return;
            }

            runGame();
        });

        handler.register("countdown", "Get the hexed restart countdown.", args -> {
            Log.info("Time until round ends: &lc@ minutes", (int)(roundTime - counter) / 60 / 60);
        });

        handler.register("end", "End the game.", args -> {
        	endGame();
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        if(registered) return;
        registered = true;

        handler.<Player>register("spectate", "Enter spectator mode. This destroys your base!", (args, player) -> {
             if(player.team() == Team.derelict){
                 player.sendMessage("[scarlet]You're already spectating.");
             }else{
            	 killTeam(player.team());
                 player.unit().kill();
                 player.team(Team.derelict);
             }
        });

        handler.<Player>register("captured", "Dispay the number of hexes you have captured.", (args, player) -> {
            if(player.team() == Team.derelict){
                player.sendMessage("[scarlet]You're spectating.");
            }else{
                player.sendMessage("[lightgray]You've captured[accent] " + data.getControlled(player).size + "[] hexes.");
            }
        });

        handler.<Player>register("leaderboard", "Display the leaderboard", (args, player) -> {
            player.sendMessage(getLeaderboard());
        });

        handler.<Player>register("hexstatus", "Get hex status at your position.", (args, player) -> {
            Hex hex = data.data(player).location;
            if(hex != null){
                hex.updateController();
                StringBuilder builder = new StringBuilder();
                builder.append("| [lightgray]Hex #").append(hex.id).append("[]\n");
                builder.append("| [lightgray]Owner:[] ").append(hex.controller != null && data.getPlayer(hex.controller) != null ? data.getPlayer(hex.controller).name : "(none)");
                for(TeamData data : state.teams.getActive()){
                    if(hex.getProgressPercent(data.team) > 0){
                        builder.append("\n| [accent]").append(this.data.getPlayer(data.team).name).append("[lightgray]: ").append((int)hex.getProgressPercent(data.team)).append("% captured");
                    }
                }
                player.sendMessage(builder.toString());
            }else{
                player.sendMessage("[scarlet]No hex found.");
            }
        });
    }

    void endGame(){
        if(restarting) return;

        restarting = true;
        Seq<Player> players = data.getLeaderboard();
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < players.size && i < 3; i++){
            if(data.getControlled(players.get(i)).size > 1){
                builder.append("[yellow]").append(i + 1).append(".[accent] ").append(players.get(i).name)
                .append("[lightgray] (x").append(data.getControlled(players.get(i)).size).append(")[]\n");
            }
        }

        if(!players.isEmpty()){
            boolean dominated = data.getControlled(players.first()).size == data.hexes().size;

            for(Player player : Groups.player){
                Call.infoMessage(player.con, "[accent]--ROUND OVER--\n\n[lightgray]"
                + (player == players.first() ? "[accent]You[] were" : "[yellow]" + players.first().name + "[lightgray] was") +
                " victorious, with [accent]" + data.getControlled(players.first()).size + "[lightgray] hexes conquered." + (dominated ? "" : "\n\nFinal scores:\n" + builder));
            }
        }

        Log.info("Server restarting.");
        Time.runTask(60f * 10f, () -> {
            netServer.kickAll(KickReason.serverRestarting);
            net.closeServer();
            state.set(State.menu);
            runGame();
            counter = 0;
            restarting = false;
        });
    }
    
    void runGame(){
    	data = new HexData();

        logic.reset();
        Log.info("Loading a map...");
        Map result;
        Gamemode preset = Gamemode.survival;
        result = maps.getShuffleMode().next(preset, state.map);
        SaveIO.load(result.file, world.filterContext(result));
        data.initHexes(data.getHex(Hex.size, Hex.size));
        info("Map loaded.");
        
        state.rules.tags.put("hexed", "true");
        state.rules.canGameOver = false;
        state.rules.pvp = true;

        logic.play();
        netServer.openServer();
    }

    String getLeaderboard(){
        StringBuilder builder = new StringBuilder();
        builder.append("[accent]Leaderboard\n[scarlet]").append(lastMin).append("[lightgray] mins. remaining\n\n");
        int count = 0;
        for(Player player : data.getLeaderboard()){
            builder.append("[yellow]").append(++count).append(".[white] ")
            .append(player.name).append("[orange] (").append(data.getControlled(player).size).append(" hexes)\n[white]");

            if(count > 4) break;
        }
        return builder.toString();
    }

    void killTeam(Team team){
        data.data(team).dying = true;
        Time.runTask(8f, () -> data.data(team).dying = false);
        for(Unit unit : Groups.unit){
        	if(unit.team() == team) unit.kill();
        };
        for(int x = 0; x < world.width(); x++){
            for(int y = 0; y < world.height(); y++){
                Tile tile = world.tile(x, y);
                if(tile.build != null && tile.team() == team){
                    Time.run(Mathf.random(60f * 6), tile.build::kill);
                }
            }
        }
    }

    void loadout(Player player, int x, int y){
        Stile coreTile = start.tiles.find(s -> s.block instanceof CoreBlock);
        if(coreTile == null) throw new IllegalArgumentException("Schematic has no core tile. Exiting.");
        int ox = x - coreTile.x, oy = y - coreTile.y;
        start.tiles.each(st -> {
            Tile tile = world.tile(st.x + ox, st.y + oy);
            if(tile == null) return;

            if(tile.block() != Blocks.air){
                tile.removeNet();
            }

            tile.setNet(st.block, player.team(), st.rotation);

            if(st.config != null){
                tile.build.configureAny(st.config);
            }
            if(tile.block() instanceof CoreBlock){
                for(ItemStack stack : state.rules.loadout){
                    Call.setItem(tile.build, stack.item, stack.amount);
                }
            }
        });
    }

    public boolean active(){
        return state.rules.tags.getBool("hexed") && !state.is(State.menu);
    }
}
