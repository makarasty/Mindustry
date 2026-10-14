package mindustry.ai;

import arc.func.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.net.Net.*;

import java.lang.reflect.*;
import java.nio.*;

import static mindustry.Vars.*;

/**
 * A server with N connected players and no sockets, for timing NetServer.sync(). Packets are serialized like
 * ArcNetProvider does it: once per broadcast (then copied into every connection's buffer), once per direct send.
 */
public class FakeNet implements NetProvider{
    final ArcNetProvider.PacketSerializer serializer = new ArcNetProvider.PacketSerializer();
    final ByteBuffer bulk = ByteBuffer.allocate(1 << 16);
    final Seq<Con> cons = new Seq<>();
    public long bytes;

    public class Con extends NetConnection{
        final ByteBuffer out = ByteBuffer.allocate(1 << 16);

        Con(int i){
            super("10.0.0." + i);
        }

        @Override
        public void send(Object object, boolean reliable){
            out.clear();
            serializer.write(out, object);
            bytes += out.position();
        }

        @Override
        public void close(){
        }

        void copy(ByteBuffer src){
            out.clear();
            out.put(src.array(), 0, src.position());
            bytes += src.position();
        }
    }

    /** Puts n players on the given team and makes net.server() true. */
    public static FakeNet install(int n, Team team){
        FakeNet fake = new FakeNet();
        net = new Net(fake);
        try{
            for(String name : new String[]{"server", "active"}){
                Field f = Net.class.getDeclaredField(name);
                f.setAccessible(true);
                f.setBoolean(net, true);
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }
        for(int i = 0; i < n; i++){
            Con con = fake.new Con(i);
            Player player = Player.create();
            player.name = "bench" + i;
            player.team(team);
            player.con = con;
            con.player = player;
            con.hasConnected = con.hasBegunConnecting = true;
            player.add();
            fake.cons.add(con);
        }
        return fake;
    }

    @Override
    public void sendAllServer(Object object, boolean reliable){
        bulk.clear();
        serializer.write(bulk, object);
        for(Con con : cons) con.copy(bulk);
    }

    @Override
    public void sendAllServer(Object object, Iterable<NetConnection> connections, boolean reliable){
        bulk.clear();
        serializer.write(bulk, object);
        for(NetConnection con : connections) ((Con)con).copy(bulk);
    }

    @Override
    public void sendExceptServer(NetConnection except, Object object, boolean reliable){
        bulk.clear();
        serializer.write(bulk, object);
        for(Con con : cons) if(con != except) con.copy(bulk);
    }

    @Override
    public Iterable<? extends NetConnection> getConnections(){
        return cons;
    }

    @Override
    public void connectClient(String ip, int port, Runnable success){
    }

    @Override
    public void sendClient(Object object, boolean reliable){
    }

    @Override
    public void disconnectClient(){
    }

    @Override
    public void discoverServers(Cons<Host> callback, Runnable done){
    }

    @Override
    public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed){
    }

    @Override
    public void hostServer(int port){
    }

    @Override
    public void closeServer(){
    }
}
