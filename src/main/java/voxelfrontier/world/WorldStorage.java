package voxelfrontier.world;

import voxelfrontier.entity.Player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * ワールドの保存と読み込み。
 * 地形はシードから再生成できるので、保存するのはシード・時刻・プレイヤー位置・変更ブロックだけ。
 *
 *   saves/&lt;ワールド名&gt;/level.properties  シード・時刻・プレイヤー位置 (テキスト)
 *   saves/&lt;ワールド名&gt;/edits.dat.gz      変更されたブロックの一覧 (バイナリ)
 */
public final class WorldStorage {

    private static final String LEVEL_FILE = "level.properties";
    private static final String EDITS_FILE = "edits.dat.gz";
    private static final int EDITS_MAGIC = 0x56464544; // "VFED"
    private static final int EDITS_VERSION = 1;

    /** 保存されていたプレイヤーの状態。 */
    public record PlayerState(double x, double y, double z, float yaw, float pitch, boolean flying) {
    }

    public record Level(long seed, double time, PlayerState player) {
    }

    private WorldStorage() {
    }

    public static Optional<Level> loadLevel(Path dir) throws IOException {
        Path file = dir.resolve(LEVEL_FILE);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        long seed = Long.parseLong(p.getProperty("seed"));
        double time = Double.parseDouble(p.getProperty("time", "0"));
        PlayerState player = null;
        if (p.containsKey("player.x")) {
            player = new PlayerState(
                    Double.parseDouble(p.getProperty("player.x")),
                    Double.parseDouble(p.getProperty("player.y")),
                    Double.parseDouble(p.getProperty("player.z")),
                    Float.parseFloat(p.getProperty("player.yaw", "0")),
                    Float.parseFloat(p.getProperty("player.pitch", "0")),
                    Boolean.parseBoolean(p.getProperty("player.flying", "false")));
        }
        return Optional.of(new Level(seed, time, player));
    }

    public static void loadEdits(Path dir, World world) throws IOException {
        Path file = dir.resolve(EDITS_FILE);
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            if (in.readInt() != EDITS_MAGIC) {
                throw new IOException("edits ファイルの形式が違います: " + file);
            }
            int version = in.readInt();
            if (version != EDITS_VERSION) {
                throw new IOException("未対応の edits バージョンです: " + version);
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int x = in.readInt();
                int y = in.readUnsignedByte();
                int z = in.readInt();
                int id = in.readUnsignedByte();
                world.putEdit(x, y, z, Block.byId(id));
            }
        }
    }

    public static void save(Path dir, World world, Player player) throws IOException {
        Files.createDirectories(dir);

        Properties p = new Properties();
        p.setProperty("seed", Long.toString(world.seed()));
        p.setProperty("time", Double.toString(world.time()));
        if (player != null) {
            p.setProperty("player.x", Double.toString(player.x));
            p.setProperty("player.y", Double.toString(player.y));
            p.setProperty("player.z", Double.toString(player.z));
            p.setProperty("player.yaw", Float.toString(player.yaw));
            p.setProperty("player.pitch", Float.toString(player.pitch));
            p.setProperty("player.flying", Boolean.toString(player.flying));
        }
        Path levelTmp = dir.resolve(LEVEL_FILE + ".tmp");
        try (Writer w = Files.newBufferedWriter(levelTmp, StandardCharsets.UTF_8)) {
            p.store(w, "Voxel Frontier world");
        }

        Path editsTmp = dir.resolve(EDITS_FILE + ".tmp");
        try (OutputStream raw = Files.newOutputStream(editsTmp);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw)))) {
            out.writeInt(EDITS_MAGIC);
            out.writeInt(EDITS_VERSION);
            out.writeInt(world.editCount());
            IOException[] error = new IOException[1];
            world.forEachEdit((x, y, z, block) -> {
                if (error[0] != null) {
                    return;
                }
                try {
                    out.writeInt(x);
                    out.writeByte(y);
                    out.writeInt(z);
                    out.writeByte(block.id());
                } catch (IOException e) {
                    error[0] = e;
                }
            });
            if (error[0] != null) {
                throw error[0];
            }
        }

        // 書き込み途中で落ちても壊れないよう、一時ファイルに書いてから置き換える
        Files.move(editsTmp, dir.resolve(EDITS_FILE), StandardCopyOption.REPLACE_EXISTING);
        Files.move(levelTmp, dir.resolve(LEVEL_FILE), StandardCopyOption.REPLACE_EXISTING);
    }
}
