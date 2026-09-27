package voxelfrontier.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import voxelfrontier.entity.Player;
import voxelfrontier.world.Block;
import voxelfrontier.world.World;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 実際に localhost のTCPでホストとクライアントをつなぎ、手続き・ブロック同期・退出を確かめる。
 * ゲームのメインループの代わりに、テストが tick() を回す。
 */
class MultiplayerTest {

    private World hostWorld;
    private Player host;
    private GameServer server;

    @BeforeEach
    void startServer() throws IOException {
        hostWorld = new World(777);
        hostWorld.setBlock(10, 80, 10, Block.BRICKS); // 参加前の変更
        host = new Player(0.5, 90, 0.5);
        server = new GameServer(hostWorld, "ホスト", 0); // 0 = 空いているポートを自動で選ぶ
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    /** ホスト側の tick を回しながら条件を待つ。 */
    private void pumpUntil(BooleanSupplier condition, GameClient client, Player local, World clientWorld) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "時間内に条件を満たしませんでした");
            server.tick(0.01, host);
            if (client != null) {
                client.tick(0.01, local, clientWorld);
            }
            sleep();
        }
    }

    /** 接続は応答待ちで止まるので、その間ホストの tick を回し続ける。 */
    private GameClient connect(String name) throws Exception {
        CompletableFuture<GameClient> future = CompletableFuture.supplyAsync(() -> {
            try {
                return GameClient.connect("127.0.0.1", server.port(), name, 5000);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            server.tick(0.01, host);
            sleep();
        }
        return future.get(1, TimeUnit.SECONDS);
    }

    @Test
    void clientReceivesSeedAndEditsOnJoin() throws Exception {
        GameClient client = connect("参加者");
        assertEquals(777, client.welcome().seed());
        assertEquals(1, client.playerId());
        assertEquals(1, client.remotePlayers().size(), "ホストが見えていません");

        World clientWorld = new World(client.welcome().seed());
        client.applyWelcome(clientWorld);
        assertEquals(Block.BRICKS, clientWorld.peekBlock(10, 80, 10));
        // 地形はシードから再生成するので、変更していない場所もホストと一致する
        assertEquals(hostWorld.peekBlock(3, 60, -4), clientWorld.peekBlock(3, 60, -4));
        client.close();
    }

    @Test
    void blockChangesAreSyncedBothWays() throws Exception {
        GameClient client = connect("参加者");
        World clientWorld = new World(client.welcome().seed());
        client.applyWelcome(clientWorld);
        Player local = new Player(client.welcome().spawnX(), client.welcome().spawnY(), client.welcome().spawnZ());

        // クライアント → ホスト (手の届く範囲)
        int x = (int) Math.floor(local.x) + 2;
        int y = (int) Math.floor(local.y) + 1;
        int z = (int) Math.floor(local.z);
        Block before = hostWorld.peekBlock(x, y, z);
        Block placed = before == Block.AIR ? Block.PLANKS : Block.AIR;
        clientWorld.setBlock(x, y, z, placed);
        client.requestBlockChange(x, y, z, placed);
        pumpUntil(() -> hostWorld.peekBlock(x, y, z) == placed, client, local, clientWorld);

        // ホスト → クライアント
        hostWorld.setBlock(1, 95, 1, Block.STONE);
        server.broadcastBlockChange(1, 95, 1, Block.STONE);
        pumpUntil(() -> clientWorld.peekBlock(1, 95, 1) == Block.STONE, client, local, clientWorld);
        client.close();
    }

    @Test
    void hostRejectsOutOfReachEdits() throws Exception {
        GameClient client = connect("参加者");
        World clientWorld = new World(client.welcome().seed());
        client.applyWelcome(clientWorld);
        Player local = new Player(client.welcome().spawnX(), client.welcome().spawnY(), client.welcome().spawnZ());

        // 遠く離れた場所を勝手に変えようとする → ホストは拒否して正しいブロックを送り返す
        int x = 500;
        int y = 100;
        int z = 500;
        Block truth = hostWorld.peekBlock(x, y, z);
        clientWorld.setBlock(x, y, z, Block.BRICKS);
        client.requestBlockChange(x, y, z, Block.BRICKS);
        pumpUntil(() -> clientWorld.peekBlock(x, y, z) == truth, client, local, clientWorld);
        assertEquals(truth, hostWorld.peekBlock(x, y, z));
        client.close();
    }

    @Test
    void playersSeeEachOtherMoveAndLeave() throws Exception {
        GameClient a = connect("Alice");
        World worldA = new World(a.welcome().seed());
        Player playerA = new Player(a.welcome().spawnX(), a.welcome().spawnY(), a.welcome().spawnZ());
        GameClient b = connect("Bob");
        World worldB = new World(b.welcome().seed());
        Player playerB = new Player(b.welcome().spawnX(), b.welcome().spawnY(), b.welcome().spawnZ());

        assertEquals(2, b.remotePlayers().size(), "Bob からホストと Alice が見えていません");

        playerA.x += 3;
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            server.tick(0.01, host);
            a.tick(0.06, playerA, worldA);
            b.tick(0.01, playerB, worldB);
            RemotePlayer seen = b.remotePlayers().stream().filter(p -> p.name.equals("Alice")).findFirst().orElseThrow();
            if (Math.abs(seen.targetX() - playerA.x) < 1e-9) {
                break;
            }
            assertTrue(System.currentTimeMillis() < deadline, "Alice の移動が Bob に届きません");
            sleep();
        }

        a.close();
        pumpUntil(() -> b.remotePlayers().size() == 1, b, playerB, worldB);
        assertEquals(1, server.remotePlayers().size());
        b.close();
    }

    @Test
    void duplicateNamesAreMadeUnique() throws Exception {
        GameClient a = connect("Same");
        GameClient b = connect("Same");
        assertTrue(server.remotePlayers().stream().map(p -> p.name).distinct().count() == 2);
        a.close();
        b.close();
    }

    @Test
    void wrongVersionIsRejected() throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", server.port())) {
            Connection c = new Connection(socket);
            c.start();
            c.send(new Packet.Hello(Protocol.MAGIC, Protocol.VERSION + 1, "old"));
            Packet reply = null;
            long deadline = System.currentTimeMillis() + 5000;
            while (reply == null && System.currentTimeMillis() < deadline) {
                server.tick(0.01, host);
                reply = c.poll();
                sleep();
            }
            assertInstanceOf(Packet.Disconnect.class, reply);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
