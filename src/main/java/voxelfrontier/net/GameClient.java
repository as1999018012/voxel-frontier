package voxelfrontier.net;

import voxelfrontier.entity.Player;
import voxelfrontier.world.Block;
import voxelfrontier.world.World;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ホストに参加する側。接続時にシードと変更ブロックを受け取り、地形は自分で再生成する。
 */
public final class GameClient implements AutoCloseable {

    private final Connection connection;
    private final Packet.Welcome welcome;
    private final Map<Integer, RemotePlayer> remotePlayers = new LinkedHashMap<>();
    private double moveTimer;
    private String disconnectReason;

    private GameClient(Connection connection, Packet.Welcome welcome) {
        this.connection = connection;
        this.welcome = welcome;
        for (Packet.PlayerInfo info : welcome.players()) {
            if (info.id() != welcome.yourId()) {
                remotePlayers.put(info.id(), fromInfo(info));
            }
        }
    }

    /**
     * ホストへ接続し、Welcome を受け取るまで待つ。
     * @throws IOException 接続できない・拒否された・時間切れ
     */
    public static GameClient connect(String host, int port, String name, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        } catch (IOException e) {
            socket.close();
            throw new IOException(host + ":" + port + " に接続できませんでした (" + e.getMessage() + ")", e);
        }
        Connection connection = new Connection(socket);
        connection.start();
        connection.send(new Packet.Hello(Protocol.MAGIC, Protocol.VERSION, Protocol.sanitizeName(name)));

        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Packet p = connection.poll();
            if (p instanceof Packet.Welcome welcome) {
                return new GameClient(connection, welcome);
            }
            if (p instanceof Packet.Disconnect d) {
                connection.close();
                throw new IOException("ホストに拒否されました: " + d.reason());
            }
            if (p == null) {
                if (connection.isClosed()) {
                    throw new IOException("ホストとの接続が切れました: " + connection.closeReason());
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        connection.close();
        throw new IOException("ホストからの応答がありませんでした");
    }

    public Packet.Welcome welcome() {
        return welcome;
    }

    public int playerId() {
        return welcome.yourId();
    }

    /** Welcome で受け取った変更ブロックと時刻をワールドに反映する (チャンク読み込み前に呼ぶ)。 */
    public void applyWelcome(World world) {
        world.setTime(welcome.worldTime());
        for (Packet.BlockChange c : welcome.edits()) {
            world.putEdit(c.x(), c.y(), c.z(), Block.byId(c.blockId()));
        }
    }

    public void tick(double dt, Player local, World world) {
        Packet p;
        while ((p = connection.poll()) != null) {
            switch (p) {
                case Packet.PlayerJoined joined -> {
                    remotePlayers.put(joined.info().id(), fromInfo(joined.info()));
                    System.out.println("[参加] " + joined.info().name() + " が参加しました");
                }
                case Packet.PlayerLeft left -> {
                    RemotePlayer rp = remotePlayers.remove(left.id());
                    if (rp != null) {
                        System.out.println("[参加] " + rp.name + " が退出しました");
                    }
                }
                case Packet.PlayerMove move -> {
                    RemotePlayer rp = remotePlayers.get(move.id());
                    if (rp != null) {
                        rp.setTarget(move.x(), move.y(), move.z(), move.yaw(), move.pitch());
                    }
                }
                case Packet.BlockChange change -> world.setBlock(change.x(), change.y(), change.z(), Block.byId(change.blockId()));
                case Packet.TimeSync sync -> world.setTime(sync.time());
                case Packet.Disconnect d -> {
                    disconnectReason = d.reason();
                    connection.close();
                }
                default -> {
                    // クライアントが受け取るはずのないパケットは無視する
                }
            }
        }
        if (disconnectReason == null && connection.isClosed()) {
            disconnectReason = "ホストとの接続が切れました (" + connection.closeReason() + ")";
        }

        moveTimer += dt;
        if (moveTimer >= Protocol.MOVE_INTERVAL) {
            moveTimer = 0;
            connection.send(new Packet.PlayerMove(welcome.yourId(), local.x, local.y, local.z, local.yaw, local.pitch));
        }
    }

    /** ブロック変更をホストに要求する。画面にはすぐ反映しておき、拒否されたらホストが正しい状態を送り返してくる。 */
    public void requestBlockChange(int x, int y, int z, Block block) {
        connection.send(new Packet.BlockChange(x, y, z, block.id()));
    }

    public Collection<RemotePlayer> remotePlayers() {
        return Collections.unmodifiableCollection(remotePlayers.values());
    }

    /** 切断されていれば理由、接続中なら null。 */
    public String disconnectReason() {
        return disconnectReason;
    }

    private static RemotePlayer fromInfo(Packet.PlayerInfo info) {
        return new RemotePlayer(info.id(), info.name(), info.x(), info.y(), info.z(), info.yaw(), info.pitch());
    }

    @Override
    public void close() {
        connection.sendAndClose(new Packet.Disconnect("退出しました"));
    }
}
