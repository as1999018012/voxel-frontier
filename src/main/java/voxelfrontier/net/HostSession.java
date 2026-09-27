package voxelfrontier.net;

import voxelfrontier.entity.Player;
import voxelfrontier.world.Block;
import voxelfrontier.world.World;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * 1人プレイ、またはホストとしてのプレイ。ワールドの正はこのプロセスが持つ。
 * openToLan() でサーバーを起動すると、そのままホストになる。
 */
public final class HostSession implements Session {

    private final World world;
    private final String playerName;
    private final int port;
    private final boolean useUpnp;

    private GameServer server;
    private LanDiscovery.Broadcaster broadcaster;
    private Upnp upnp;
    private String status = "1人プレイ";

    public HostSession(World world, String playerName, int port, boolean useUpnp) {
        this.world = world;
        this.playerName = playerName;
        this.port = port;
        this.useUpnp = useUpnp;
    }

    @Override
    public void openToLan() {
        if (server != null) {
            return;
        }
        GameServer s = new GameServer(world, playerName, port);
        try {
            s.start();
        } catch (IOException e) {
            System.err.println("[ホスト] ポート " + port + " で待ち受けできませんでした: " + e.getMessage());
            status = "ホスト開始に失敗 (ポート " + port + " 使用中?)";
            return;
        }
        server = s;
        status = "ホスト中 port " + server.port();
        System.out.println("[ホスト] ポート " + server.port() + " で参加を受け付けています");
        for (String address : localAddresses()) {
            System.out.println("[ホスト]   LAN内の参加者はこのアドレスへ: " + address + ":" + server.port());
        }

        try {
            broadcaster = new LanDiscovery.Broadcaster(server.port(), playerName);
        } catch (IOException e) {
            System.err.println("[ホスト] LAN通知を開始できませんでした (IP直接指定なら参加できます): " + e.getMessage());
        }

        if (useUpnp) {
            // ルーターとのやり取りに数秒かかることがあるので、別スレッドで行う
            Thread t = new Thread(this::setupUpnp, "upnp");
            t.setDaemon(true);
            t.start();
        }
    }

    private void setupUpnp() {
        System.out.println("[UPnP] ルーターを探しています...");
        Upnp found = Upnp.discover(3000).orElse(null);
        if (found == null) {
            System.out.println("[UPnP] UPnP対応ルーターが見つかりませんでした。インターネット越しに遊ぶには、"
                    + "ルーターで TCP " + server.port() + " を手動でポート開放してください。");
            return;
        }
        if (!found.addPortMapping(server.port(), "Voxel Frontier")) {
            System.out.println("[UPnP] ポート開放を断られました。ルーターのUPnP設定を確認するか、手動でポート開放してください。");
            return;
        }
        upnp = found;
        String external = found.externalIpAddress().orElse("(取得できませんでした)");
        System.out.println("[UPnP] ポート " + server.port() + " を開放しました。友達にはこのアドレスを伝えてください: "
                + external + ":" + server.port());
    }

    @Override
    public void tick(double dt, Player local) {
        if (server != null) {
            server.tick(dt, local);
            status = "ホスト中 port " + server.port() + " (" + (server.remotePlayers().size() + 1) + "人)";
        }
    }

    @Override
    public void changeBlock(int x, int y, int z, Block block) {
        if (world.setBlock(x, y, z, block) && server != null) {
            server.broadcastBlockChange(x, y, z, block);
        }
    }

    @Override
    public Collection<RemotePlayer> remotePlayers() {
        return server == null ? List.of() : server.remotePlayers();
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public String disconnectReason() {
        return null;
    }

    @Override
    public boolean ownsWorld() {
        return true;
    }

    private static List<String> localAddresses() {
        try {
            return java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).stream()
                    .filter(ni -> {
                        try {
                            return ni.isUp() && !ni.isLoopback() && !ni.isVirtual();
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .flatMap(ni -> ni.getInterfaceAddresses().stream())
                    .filter(ia -> ia.getBroadcast() != null) // IPv4 のみ
                    .map(ia -> ia.getAddress().getHostAddress())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public void close() {
        if (upnp != null) {
            upnp.deletePortMapping(server.port());
        }
        if (broadcaster != null) {
            broadcaster.close();
        }
        if (server != null) {
            server.close();
        }
    }
}
