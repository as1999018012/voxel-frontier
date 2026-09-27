package voxelfrontier.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * LAN内のホスト自動検出。
 * ホストは一定間隔でUDPブロードキャストに「ここでゲームを開いています」と流し、
 * 参加者はそれを受信してホストのアドレスとポートを知る。
 */
public final class LanDiscovery {

    private static final String PREFIX = "VOXELFRONTIER";
    private static final long INTERVAL_MILLIS = 1500;

    public record FoundHost(InetAddress address, int port, String hostName) {
    }

    private LanDiscovery() {
    }

    /** ホスト側: 自分の存在を知らせ続ける。 */
    public static final class Broadcaster implements AutoCloseable {
        private final DatagramSocket socket;
        private final byte[] message;
        private volatile boolean closed;

        public Broadcaster(int gamePort, String hostName) throws IOException {
            this.socket = new DatagramSocket();
            socket.setBroadcast(true);
            String text = PREFIX + ";" + Protocol.VERSION + ";" + gamePort + ";" + Protocol.sanitizeName(hostName);
            this.message = text.getBytes(StandardCharsets.UTF_8);
            Thread thread = new Thread(this::loop, "lan-broadcast");
            thread.setDaemon(true);
            thread.start();
        }

        private void loop() {
            while (!closed) {
                for (InetAddress target : broadcastTargets()) {
                    try {
                        socket.send(new DatagramPacket(message, message.length, target, Protocol.DISCOVERY_PORT));
                    } catch (IOException ignored) {
                        // 使えないネットワークアダプタもあるので、1つ失敗しても続ける
                    }
                }
                try {
                    Thread.sleep(INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            socket.close();
        }
    }

    /** 参加側: timeoutMillis の間ホストを探す。 */
    public static Optional<FoundHost> find(long timeoutMillis) throws IOException {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(Protocol.DISCOVERY_PORT));
            byte[] buffer = new byte[512];
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return Optional.empty();
                }
                socket.setSoTimeout((int) remaining);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    return Optional.empty();
                }
                Optional<FoundHost> host = parse(packet);
                if (host.isPresent()) {
                    return host;
                }
            }
        }
    }

    static Optional<FoundHost> parse(DatagramPacket packet) {
        String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        String[] parts = text.split(";", 4);
        if (parts.length != 4 || !parts[0].equals(PREFIX)) {
            return Optional.empty();
        }
        try {
            if (Integer.parseInt(parts[1]) != Protocol.VERSION) {
                return Optional.empty();
            }
            int port = Integer.parseInt(parts[2]);
            return Optional.of(new FoundHost(packet.getAddress(), port, parts[3]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 全ネットワークアダプタのブロードキャストアドレス + 255.255.255.255 + 自分自身 (同じPCでの動作確認用)。 */
    private static List<InetAddress> broadcastTargets() {
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) {
                        targets.add(ia.getBroadcast());
                    }
                }
            }
        } catch (IOException ignored) {
            // 取得できなければ下の既定の宛先だけ使う
        }
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
            targets.add(InetAddress.getLoopbackAddress());
        } catch (IOException ignored) {
            // 起こらない
        }
        return new ArrayList<>(targets);
    }
}
