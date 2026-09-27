package voxelfrontier.net;

import voxelfrontier.entity.Player;
import voxelfrontier.world.Block;
import voxelfrontier.world.Chunk;
import voxelfrontier.world.World;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ホスト側のサーバー。ホストのゲームプロセス内で動き、ホストのワールドを「正」として
 * 参加者の入力を検証・反映し、結果を全員に配る (リスンサーバー方式)。
 *
 * 接続の受け付けだけは専用スレッドで行い、パケットの処理は tick() でメインスレッドから行う。
 * そのため World に触るのは常にメインスレッドだけになる。
 */
public final class GameServer implements AutoCloseable {

    public static final int HOST_ID = 0;
    private static final double HANDSHAKE_TIMEOUT = 10.0;

    private static final class Client {
        final Connection connection;
        final double deadline;
        int id = -1;
        RemotePlayer player;

        Client(Connection connection, double deadline) {
            this.connection = connection;
            this.deadline = deadline;
        }

        boolean joined() {
            return player != null;
        }
    }

    private final World world;
    private final String hostName;
    private final int requestedPort;
    private final ConcurrentLinkedQueue<Connection> incoming = new ConcurrentLinkedQueue<>();
    private final List<Client> clients = new ArrayList<>();
    private final Map<Integer, RemotePlayer> remotePlayers = new LinkedHashMap<>();
    private final List<String> log = new ArrayList<>();

    private ServerSocket serverSocket;
    private volatile boolean closed;
    private int nextId = HOST_ID + 1;
    private double elapsed;
    private double moveTimer;
    private double timeSyncTimer;

    public GameServer(World world, String hostName, int port) {
        this.world = world;
        this.hostName = Protocol.sanitizeName(hostName);
        this.requestedPort = port;
    }

    /** 待ち受けを開始する。ポートが使用中なら IOException。 */
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(requestedPort));
        Thread acceptor = new Thread(this::acceptLoop, "net-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                Connection connection = new Connection(socket);
                connection.start();
                incoming.add(connection);
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("接続の受け付けに失敗しました: " + e.getMessage());
                }
            }
        }
    }

    /** メインスレッドから毎フレーム呼ぶ。host はホスト自身のプレイヤー。 */
    public void tick(double dt, Player host) {
        elapsed += dt;

        Connection c;
        while ((c = incoming.poll()) != null) {
            clients.add(new Client(c, elapsed + HANDSHAKE_TIMEOUT));
        }

        for (Iterator<Client> it = clients.iterator(); it.hasNext(); ) {
            Client client = it.next();
            Packet p;
            while ((p = client.connection.poll()) != null) {
                handle(client, p, host);
            }
            if (!client.joined() && elapsed > client.deadline) {
                client.connection.sendAndClose(new Packet.Disconnect("接続の手続きが時間内に完了しませんでした"));
            }
            if (client.connection.isClosed()) {
                it.remove();
                if (client.joined()) {
                    remotePlayers.remove(client.id);
                    broadcast(new Packet.PlayerLeft(client.id), null);
                    info(client.player.name + " が退出しました (" + client.connection.closeReason() + ")");
                }
            }
        }

        moveTimer += dt;
        if (moveTimer >= Protocol.MOVE_INTERVAL) {
            moveTimer = 0;
            broadcast(new Packet.PlayerMove(HOST_ID, host.x, host.y, host.z, host.yaw, host.pitch), null);
        }
        timeSyncTimer += dt;
        if (timeSyncTimer >= Protocol.TIME_SYNC_INTERVAL) {
            timeSyncTimer = 0;
            broadcast(new Packet.TimeSync(world.time()), null);
        }
    }

    private void handle(Client client, Packet packet, Player host) {
        switch (packet) {
            case Packet.Hello hello when !client.joined() -> handleHello(client, hello, host);
            case Packet.PlayerMove move when client.joined() -> {
                if (isFinite(move.x(), move.y(), move.z()) && Float.isFinite(move.yaw()) && Float.isFinite(move.pitch())) {
                    client.player.setTarget(move.x(), move.y(), move.z(), move.yaw(), move.pitch());
                    broadcast(new Packet.PlayerMove(client.id, move.x(), move.y(), move.z(), move.yaw(), move.pitch()), client);
                }
            }
            case Packet.BlockChange change when client.joined() -> handleBlockChange(client, change);
            case Packet.Disconnect d -> client.connection.close();
            default -> {
                // 手続き前のパケットや、クライアントから送られるはずのないパケットは無視する
            }
        }
    }

    private void handleHello(Client client, Packet.Hello hello, Player host) {
        if (hello.magic() != Protocol.MAGIC) {
            client.connection.sendAndClose(new Packet.Disconnect("Voxel Frontier のクライアントではありません"));
            return;
        }
        if (hello.version() != Protocol.VERSION) {
            client.connection.sendAndClose(new Packet.Disconnect(
                    "バージョンが違います (ホスト: " + Protocol.VERSION + ", あなた: " + hello.version() + ")"));
            return;
        }
        if (remotePlayers.size() + 1 >= Protocol.MAX_PLAYERS) {
            client.connection.sendAndClose(new Packet.Disconnect("満員です (最大 " + Protocol.MAX_PLAYERS + " 人)"));
            return;
        }

        client.id = nextId++;
        String name = uniqueName(Protocol.sanitizeName(hello.name()));
        // ホストの近くの空いている場所に、ホストの方を向いて出現させる
        double[] spawn = findSpawnNear(host);
        double sx = spawn[0];
        double sy = spawn[1];
        double sz = spawn[2];
        float syaw = (float) Math.atan2(host.x - sx, -(host.z - sz)); // yaw の定義: 前方 = (sin, 0, -cos)
        client.player = new RemotePlayer(client.id, name, sx, sy, sz, syaw, 0);

        List<Packet.PlayerInfo> players = new ArrayList<>();
        players.add(new Packet.PlayerInfo(HOST_ID, hostName, host.x, host.y, host.z, host.yaw, host.pitch));
        for (RemotePlayer rp : remotePlayers.values()) {
            players.add(rp.toInfo());
        }
        List<Packet.BlockChange> edits = new ArrayList<>(world.editCount());
        world.forEachEdit((x, y, z, block) -> edits.add(new Packet.BlockChange(x, y, z, block.id())));

        client.connection.send(new Packet.Welcome(client.id, world.seed(), world.time(), sx, sy, sz, syaw, players, edits));
        broadcast(new Packet.PlayerJoined(client.player.toInfo()), client);
        remotePlayers.put(client.id, client.player);
        info(name + " が参加しました (" + client.connection.remoteAddress() + ")");
    }

    private void handleBlockChange(Client client, Packet.BlockChange change) {
        int x = change.x();
        int y = change.y();
        int z = change.z();
        Block requested = Block.byId(change.blockId());
        boolean valid = Block.isValidId(change.blockId())
                && y >= 1 && y < Chunk.HEIGHT
                && requested != Block.BEDROCK
                && withinReach(client.player, x, y, z);
        if (valid) {
            Block current = world.peekBlock(x, y, z);
            valid = requested == Block.AIR ? current.isBreakable() : current.isReplaceable();
        }

        if (valid) {
            world.setBlock(x, y, z, requested);
            broadcast(new Packet.BlockChange(x, y, z, requested.id()), null);
        } else {
            // 拒否: 送り主の画面を正しい状態に戻す
            client.connection.send(new Packet.BlockChange(x, y, z, world.peekBlock(x, y, z).id()));
        }
    }

    /**
     * ホストから3ブロックほど離れた、立てる場所を探す。まずホストの背後、次に左右・前を試す。
     * 見つからなければホストと同じ位置 (プレイヤー同士は重なっても問題ない)。
     */
    private double[] findSpawnNear(Player host) {
        double baseAngle = host.yaw + Math.PI; // 背後
        int[] order = {0, 1, -1, 2, -2, 3, -3, 4};
        int hx = (int) Math.floor(host.x);
        int hy = (int) Math.floor(host.y);
        int hz = (int) Math.floor(host.z);
        for (int step : order) {
            double angle = baseAngle + step * Math.PI / 4;
            int bx = (int) Math.floor(host.x + Math.sin(angle) * 3);
            int bz = (int) Math.floor(host.z - Math.cos(angle) * 3);
            if (bx == hx && bz == hz) {
                continue;
            }
            for (int dy = 0; dy <= 2; dy++) {
                int by = hy + dy;
                if (canStandAt(bx, by, bz)) {
                    return new double[] {bx + 0.5, by, bz + 0.5};
                }
            }
        }
        return new double[] {host.x, host.y, host.z};
    }

    private boolean canStandAt(int x, int y, int z) {
        Block ground = world.peekBlock(x, y - 1, z);
        return (ground.isSolid() || ground.isLiquid())
                && !world.peekBlock(x, y, z).isSolid()
                && !world.peekBlock(x, y + 1, z).isSolid();
    }

    private static boolean withinReach(RemotePlayer p, int x, int y, int z) {
        double dx = x + 0.5 - p.targetX();
        double dy = y + 0.5 - (p.targetY() + Player.EYE_HEIGHT);
        double dz = z + 0.5 - p.targetZ();
        double reach = Protocol.MAX_REACH + 1.5; // 通信の遅れによる位置のずれを見込んで少し甘めにする
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /** ホスト自身がブロックを変更したときに呼ぶ (ワールドへの反映は呼び出し側で済ませておく)。 */
    public void broadcastBlockChange(int x, int y, int z, Block block) {
        broadcast(new Packet.BlockChange(x, y, z, block.id()), null);
    }

    private void broadcast(Packet packet, Client except) {
        for (Client client : clients) {
            if (client != except && client.joined()) {
                client.connection.send(packet);
            }
        }
    }

    private String uniqueName(String name) {
        String candidate = name;
        int n = 2;
        while (isNameTaken(candidate)) {
            String suffix = "-" + n++;
            candidate = name.substring(0, Math.min(name.length(), Protocol.MAX_NAME_LENGTH - suffix.length())) + suffix;
        }
        return candidate;
    }

    private boolean isNameTaken(String name) {
        if (name.equals(hostName)) {
            return true;
        }
        for (RemotePlayer rp : remotePlayers.values()) {
            if (rp.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFinite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private void info(String message) {
        System.out.println("[ホスト] " + message);
        log.add(message);
    }

    public Collection<RemotePlayer> remotePlayers() {
        return Collections.unmodifiableCollection(remotePlayers.values());
    }

    public List<String> log() {
        return log;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Client client : clients) {
            client.connection.sendAndClose(new Packet.Disconnect("ホストがゲームを終了しました"));
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // 終了処理中なので無視
        }
    }
}
