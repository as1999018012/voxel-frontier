package voxelfrontier.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 1本のTCP接続。受信と送信はそれぞれ専用スレッドで行い、ゲームのメインスレッドは
 * send() でキューに積む / poll() で取り出すだけにして、通信待ちで画面が止まらないようにする。
 */
public final class Connection implements AutoCloseable {

    private static final Object CLOSE = new Object();

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ConcurrentLinkedQueue<Packet> inbox = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<Object> outbox = new LinkedBlockingQueue<>();
    private final SocketAddress remoteAddress;
    private volatile boolean closed;
    private volatile String closeReason = "";

    public Connection(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.remoteAddress = socket.getRemoteSocketAddress();
    }

    public void start() {
        Thread reader = new Thread(this::readLoop, "net-read-" + remoteAddress);
        reader.setDaemon(true);
        reader.start();
        Thread writer = new Thread(this::writeLoop, "net-write-" + remoteAddress);
        writer.setDaemon(true);
        writer.start();
    }

    public void send(Packet packet) {
        if (!closed) {
            outbox.add(packet);
        }
    }

    /** 最後のパケットを送り切ってから切断する。 */
    public void sendAndClose(Packet packet) {
        if (!closed) {
            outbox.add(packet);
            outbox.add(CLOSE);
        }
    }

    /** 受信済みのパケットを1つ取り出す。無ければ null。 */
    public Packet poll() {
        return inbox.poll();
    }

    public boolean isClosed() {
        return closed;
    }

    public String closeReason() {
        return closeReason;
    }

    public SocketAddress remoteAddress() {
        return remoteAddress;
    }

    private void readLoop() {
        try {
            while (!closed) {
                inbox.add(Packet.read(in));
            }
        } catch (EOFException e) {
            markClosed("相手が切断しました");
        } catch (IOException e) {
            markClosed(closed ? closeReason : "通信エラー: " + e.getMessage());
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                Object item = outbox.take();
                if (item == CLOSE) {
                    out.flush();
                    close();
                    return;
                }
                Packet.write(out, (Packet) item);
                if (outbox.isEmpty()) {
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            markClosed("送信エラー: " + e.getMessage());
        }
    }

    private void markClosed(String reason) {
        if (!closed) {
            closeReason = reason;
            closed = true;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // すでに閉じている
        }
        outbox.clear();
        outbox.add(CLOSE); // 送信スレッドを終わらせる
    }

    @Override
    public void close() {
        markClosed("切断しました");
    }
}
