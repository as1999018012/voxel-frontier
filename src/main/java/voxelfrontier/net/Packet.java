package voxelfrontier.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ホストとクライアントの間でやり取りするメッセージ。
 * TCPストリーム上に [種類1バイト][本体] の形で順に並べて送る。
 *
 * 通信の流れ:
 *   クライアント → Hello
 *   ホスト       → Welcome (シード・時刻・変更ブロック・参加中のプレイヤー)
 *   以降、双方   → PlayerMove / BlockChange、ホストからは PlayerJoined / PlayerLeft / TimeSync
 * ブロックの変更はクライアントが要求し、ホストが検証して全員に配る (ホスト権威)。
 */
public sealed interface Packet {

    record Hello(int magic, int version, String name) implements Packet {
    }

    record PlayerInfo(int id, String name, double x, double y, double z, float yaw, float pitch) {
    }

    record Welcome(int yourId, long seed, double worldTime, double spawnX, double spawnY, double spawnZ, float spawnYaw,
                   List<PlayerInfo> players, List<BlockChange> edits) implements Packet {
    }

    record PlayerJoined(PlayerInfo info) implements Packet {
    }

    record PlayerLeft(int id) implements Packet {
    }

    /** クライアントから送るときの id は無視され、ホストが送り主のIDで上書きする。 */
    record PlayerMove(int id, double x, double y, double z, float yaw, float pitch) implements Packet {
    }

    record BlockChange(int x, int y, int z, byte blockId) implements Packet {
    }

    record TimeSync(double time) implements Packet {
    }

    record Disconnect(String reason) implements Packet {
    }

    int MAX_LIST_SIZE = 50_000_000;

    static void write(DataOutputStream out, Packet packet) throws IOException {
        switch (packet) {
            case Hello p -> {
                out.writeByte(1);
                out.writeInt(p.magic());
                out.writeInt(p.version());
                out.writeUTF(p.name());
            }
            case Welcome p -> {
                out.writeByte(2);
                out.writeInt(p.yourId());
                out.writeLong(p.seed());
                out.writeDouble(p.worldTime());
                out.writeDouble(p.spawnX());
                out.writeDouble(p.spawnY());
                out.writeDouble(p.spawnZ());
                out.writeFloat(p.spawnYaw());
                out.writeInt(p.players().size());
                for (PlayerInfo info : p.players()) {
                    writeInfo(out, info);
                }
                out.writeInt(p.edits().size());
                for (BlockChange c : p.edits()) {
                    writeBlockChangeBody(out, c);
                }
            }
            case PlayerJoined p -> {
                out.writeByte(3);
                writeInfo(out, p.info());
            }
            case PlayerLeft p -> {
                out.writeByte(4);
                out.writeInt(p.id());
            }
            case PlayerMove p -> {
                out.writeByte(5);
                out.writeInt(p.id());
                out.writeDouble(p.x());
                out.writeDouble(p.y());
                out.writeDouble(p.z());
                out.writeFloat(p.yaw());
                out.writeFloat(p.pitch());
            }
            case BlockChange p -> {
                out.writeByte(6);
                writeBlockChangeBody(out, p);
            }
            case TimeSync p -> {
                out.writeByte(7);
                out.writeDouble(p.time());
            }
            case Disconnect p -> {
                out.writeByte(8);
                out.writeUTF(p.reason());
            }
        }
    }

    static Packet read(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        return switch (type) {
            case 1 -> new Hello(in.readInt(), in.readInt(), in.readUTF());
            case 2 -> {
                int yourId = in.readInt();
                long seed = in.readLong();
                double time = in.readDouble();
                double sx = in.readDouble();
                double sy = in.readDouble();
                double sz = in.readDouble();
                float syaw = in.readFloat();
                int playerCount = readCount(in);
                List<PlayerInfo> players = new ArrayList<>(playerCount);
                for (int i = 0; i < playerCount; i++) {
                    players.add(readInfo(in));
                }
                int editCount = readCount(in);
                List<BlockChange> edits = new ArrayList<>(Math.min(editCount, 1 << 16));
                for (int i = 0; i < editCount; i++) {
                    edits.add(readBlockChangeBody(in));
                }
                yield new Welcome(yourId, seed, time, sx, sy, sz, syaw, players, edits);
            }
            case 3 -> new PlayerJoined(readInfo(in));
            case 4 -> new PlayerLeft(in.readInt());
            case 5 -> new PlayerMove(in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readFloat(), in.readFloat());
            case 6 -> readBlockChangeBody(in);
            case 7 -> new TimeSync(in.readDouble());
            case 8 -> new Disconnect(in.readUTF());
            default -> throw new IOException("不明なパケット種別です: " + type);
        };
    }

    private static int readCount(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_LIST_SIZE) {
            throw new IOException("リストの長さが不正です: " + n);
        }
        return n;
    }

    private static void writeInfo(DataOutputStream out, PlayerInfo info) throws IOException {
        out.writeInt(info.id());
        out.writeUTF(info.name());
        out.writeDouble(info.x());
        out.writeDouble(info.y());
        out.writeDouble(info.z());
        out.writeFloat(info.yaw());
        out.writeFloat(info.pitch());
    }

    private static PlayerInfo readInfo(DataInputStream in) throws IOException {
        return new PlayerInfo(in.readInt(), in.readUTF(), in.readDouble(), in.readDouble(), in.readDouble(),
                in.readFloat(), in.readFloat());
    }

    private static void writeBlockChangeBody(DataOutputStream out, BlockChange c) throws IOException {
        out.writeInt(c.x());
        out.writeShort(c.y());
        out.writeInt(c.z());
        out.writeByte(c.blockId());
    }

    private static BlockChange readBlockChangeBody(DataInputStream in) throws IOException {
        return new BlockChange(in.readInt(), in.readShort(), in.readInt(), in.readByte());
    }
}
