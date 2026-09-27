package voxelfrontier.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketTest {

    private static Packet roundTrip(Packet p) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            Packet.write(out, p);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            Packet read = Packet.read(in);
            assertEquals(-1, in.read(), "読み残しがあります");
            return read;
        }
    }

    @Test
    void everyPacketSurvivesRoundTrip() throws IOException {
        Packet.PlayerInfo info = new Packet.PlayerInfo(3, "ともだち", 1.5, 70, -8.25, 0.5f, -0.25f);
        List<Packet> packets = List.of(
                new Packet.Hello(Protocol.MAGIC, Protocol.VERSION, "テスト"),
                new Packet.Welcome(2, -123456789L, 42.5, 1, 2, 3, 0.75f, List.of(info),
                        List.of(new Packet.BlockChange(-5, 255, 9, (byte) 13))),
                new Packet.PlayerJoined(info),
                new Packet.PlayerLeft(7),
                new Packet.PlayerMove(1, 1e6, 64, -1e6, 3.1f, 1.5f),
                new Packet.BlockChange(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, (byte) 1),
                new Packet.TimeSync(599.99),
                new Packet.Disconnect("さようなら"));
        for (Packet p : packets) {
            assertEquals(p, roundTrip(p));
        }
    }

    @Test
    void unknownTypeIsRejected() {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(new byte[] {(byte) 200}));
        assertThrows(IOException.class, () -> Packet.read(in));
    }
}
