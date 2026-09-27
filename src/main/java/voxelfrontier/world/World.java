package voxelfrontier.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * ワールド全体の状態。メインスレッドからのみ操作すること
 * (地形生成だけは generateChunk() を別スレッドから呼んでよい)。
 *
 * 地形はシードから再生成できるので、保存・通信するのは「生成後に変更されたブロック (edits)」だけ。
 */
public final class World {

    /** 1日 (昼夜1周) の長さ [秒]。 */
    public static final double DAY_LENGTH = 600.0;

    public interface Listener {
        default void chunkAdded(Chunk chunk, boolean hadEdits) {
        }

        default void chunkRemoved(Chunk chunk) {
        }

        default void blockChanged(int x, int y, int z, Block block) {
        }
    }

    @FunctionalInterface
    public interface EditConsumer {
        void accept(int x, int y, int z, Block block);
    }

    private static final int PEEK_CACHE_SIZE = 8;

    private final TerrainGenerator generator;
    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final Map<Long, Map<Integer, Byte>> edits = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<Long, byte[]> peekCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > PEEK_CACHE_SIZE;
        }
    };
    private double time = DAY_LENGTH * 0.08; // 明るくなった朝から始める

    public World(long seed) {
        this.generator = new TerrainGenerator(seed);
    }

    public long seed() {
        return generator.seed();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    // ---- 時間 ----

    public double time() {
        return time;
    }

    public void setTime(double time) {
        this.time = time;
    }

    public void advanceTime(double dt) {
        time += dt;
    }

    // ---- チャンク ----

    /** 地形を生成する (ワールドには追加しない)。スレッドセーフ。 */
    public Chunk generateChunk(int cx, int cz) {
        return new Chunk(cx, cz, generator.generate(cx, cz));
    }

    public void addChunk(Chunk chunk) {
        long key = chunk.key();
        Map<Integer, Byte> chunkEdits = edits.get(key);
        boolean hadEdits = chunkEdits != null && !chunkEdits.isEmpty();
        if (hadEdits) {
            byte[] raw = chunk.rawBlocks();
            chunkEdits.forEach((index, id) -> raw[index] = id);
        }
        chunks.put(key, chunk);
        for (Listener l : listeners) {
            l.chunkAdded(chunk, hadEdits);
        }
    }

    /** 同期的に生成して追加する (スポーン地点やテスト用)。 */
    public Chunk loadChunkNow(int cx, int cz) {
        Chunk existing = getChunk(cx, cz);
        if (existing != null) {
            return existing;
        }
        Chunk chunk = generateChunk(cx, cz);
        addChunk(chunk);
        return chunk;
    }

    public void removeChunksIf(Predicate<Chunk> predicate) {
        List<Chunk> removed = new ArrayList<>();
        chunks.values().removeIf(c -> {
            if (predicate.test(c)) {
                removed.add(c);
                return true;
            }
            return false;
        });
        for (Chunk c : removed) {
            for (Listener l : listeners) {
                l.chunkRemoved(c);
            }
        }
    }

    public Chunk getChunk(int cx, int cz) {
        return chunks.get(Chunk.key(cx, cz));
    }

    public boolean isChunkLoaded(int cx, int cz) {
        return chunks.containsKey(Chunk.key(cx, cz));
    }

    public boolean isLoadedAt(int x, int z) {
        return isChunkLoaded(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE));
    }

    public Collection<Chunk> loadedChunks() {
        return chunks.values();
    }

    // ---- ブロック ----

    /** 読み込み済みチャンクのブロック。未読み込みなら AIR。 */
    public Block getBlock(int x, int y, int z) {
        if (y < 0) {
            return Block.BEDROCK;
        }
        if (y >= Chunk.HEIGHT) {
            return Block.AIR;
        }
        Chunk c = getChunk(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(z, Chunk.SIZE));
        if (c == null) {
            return Block.AIR;
        }
        return c.get(Math.floorMod(x, Chunk.SIZE), y, Math.floorMod(z, Chunk.SIZE));
    }

    /** チャンクが未読み込みでも本当のブロックを返す (必要なら一時的に生成する)。 */
    public Block peekBlock(int x, int y, int z) {
        if (y < 0) {
            return Block.BEDROCK;
        }
        if (y >= Chunk.HEIGHT) {
            return Block.AIR;
        }
        int cx = Math.floorDiv(x, Chunk.SIZE);
        int cz = Math.floorDiv(z, Chunk.SIZE);
        int lx = Math.floorMod(x, Chunk.SIZE);
        int lz = Math.floorMod(z, Chunk.SIZE);
        Chunk loaded = getChunk(cx, cz);
        if (loaded != null) {
            return loaded.get(lx, y, lz);
        }
        long key = Chunk.key(cx, cz);
        int index = Chunk.index(lx, y, lz);
        Map<Integer, Byte> chunkEdits = edits.get(key);
        if (chunkEdits != null && chunkEdits.containsKey(index)) {
            return Block.byId(chunkEdits.get(index));
        }
        byte[] raw = peekCache.computeIfAbsent(key, k -> generator.generate(cx, cz));
        return Block.byId(raw[index]);
    }

    /**
     * ブロックを変更する。チャンクが未読み込みでも変更は記録され、読み込み時に反映される。
     * @return 実際に変化したら true
     */
    public boolean setBlock(int x, int y, int z, Block block) {
        if (y < 0 || y >= Chunk.HEIGHT) {
            return false;
        }
        if (peekBlock(x, y, z) == block) {
            return false;
        }
        int cx = Math.floorDiv(x, Chunk.SIZE);
        int cz = Math.floorDiv(z, Chunk.SIZE);
        int lx = Math.floorMod(x, Chunk.SIZE);
        int lz = Math.floorMod(z, Chunk.SIZE);
        long key = Chunk.key(cx, cz);
        edits.computeIfAbsent(key, k -> new HashMap<>()).put(Chunk.index(lx, y, lz), block.id());

        Chunk c = chunks.get(key);
        if (c != null) {
            c.set(lx, y, lz, block);
        }
        for (Listener l : listeners) {
            l.blockChanged(x, y, z, block);
        }
        return true;
    }

    /**
     * セーブデータや参加時の同期で受け取った変更を記録する。
     * チャンク読み込み前にまとめて呼ぶ想定で、地形との比較やリスナー通知はしない。
     */
    public void putEdit(int x, int y, int z, Block block) {
        if (y < 0 || y >= Chunk.HEIGHT) {
            return;
        }
        int cx = Math.floorDiv(x, Chunk.SIZE);
        int cz = Math.floorDiv(z, Chunk.SIZE);
        int lx = Math.floorMod(x, Chunk.SIZE);
        int lz = Math.floorMod(z, Chunk.SIZE);
        long key = Chunk.key(cx, cz);
        edits.computeIfAbsent(key, k -> new HashMap<>()).put(Chunk.index(lx, y, lz), block.id());
        Chunk c = chunks.get(key);
        if (c != null) {
            c.set(lx, y, lz, block);
        }
    }

    public void forEachEdit(EditConsumer consumer) {
        edits.forEach((key, chunkEdits) -> {
            int baseX = Chunk.keyX(key) * Chunk.SIZE;
            int baseZ = Chunk.keyZ(key) * Chunk.SIZE;
            chunkEdits.forEach((index, id) -> consumer.accept(
                    baseX + (index & 15),
                    index >> 8,
                    baseZ + ((index >> 4) & 15),
                    Block.byId(id)));
        });
    }

    public int editCount() {
        int n = 0;
        for (Map<Integer, Byte> e : edits.values()) {
            n += e.size();
        }
        return n;
    }

    /** 列 (x, z) の一番上の地面/水の、1つ上のy座標。スポーン位置決めに使う。 */
    public int findStandingY(int x, int z) {
        for (int y = Chunk.HEIGHT - 1; y > 0; y--) {
            Block b = peekBlock(x, y, z);
            if (b.isSolid() || b.isLiquid()) {
                return y + 1;
            }
        }
        return Chunk.HEIGHT;
    }

    /** 陸地 (水の上でない場所) を原点付近から渦巻き状に探す。 */
    public int[] findSpawn() {
        for (int r = 0; r < 512; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) {
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    if (generator.surfaceHeight(dx, dz) > TerrainGenerator.SEA_LEVEL + 1) {
                        return new int[] {dx, findStandingY(dx, dz), dz};
                    }
                }
            }
        }
        return new int[] {0, findStandingY(0, 0), 0};
    }
}
