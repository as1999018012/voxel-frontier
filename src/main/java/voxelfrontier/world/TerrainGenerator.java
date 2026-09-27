package voxelfrontier.world;

/**
 * シードから地形を生成する。同じシード・同じチャンク座標なら、どの機種でも必ず同じ結果になる
 * (マルチプレイではホストはシードだけを送り、各クライアントが地形を再生成する)。
 * 決定性を保つため、浮動小数点は四則演算と PerlinNoise のみを使うこと (Math.sin 等は禁止)。
 *
 * インスタンスは不変なので、複数スレッドから同時に generate() を呼んでよい。
 */
public final class TerrainGenerator {

    public static final int SEA_LEVEL = 62;

    private static final int TREE_CELL = 6;
    private static final long TREE_SALT = 0x7472656573L;
    private static final long ORE_SALT = 0x6f7265L;

    private final long seed;
    private final PerlinNoise continental;
    private final PerlinNoise detail;
    private final PerlinNoise mountain;
    private final PerlinNoise tunnelA;
    private final PerlinNoise tunnelB;
    private final PerlinNoise cavern;
    private final PerlinNoise forest;

    public TerrainGenerator(long seed) {
        this.seed = seed;
        this.continental = new PerlinNoise(seed);
        this.detail = new PerlinNoise(seed + 1);
        this.mountain = new PerlinNoise(seed + 2);
        this.tunnelA = new PerlinNoise(seed + 3);
        this.tunnelB = new PerlinNoise(seed + 4);
        this.cavern = new PerlinNoise(seed + 5);
        this.forest = new PerlinNoise(seed + 6);
    }

    public long seed() {
        return seed;
    }

    /** 地表 (一番上の地面ブロック) の高さ。洞窟や木は考慮しない。 */
    public int surfaceHeight(int x, int z) {
        double c = continental.fbm2(x / 320.0, z / 320.0, 4);
        double d = detail.fbm2(x / 48.0, z / 48.0, 3);
        double m = mountain.fbm2(x / 200.0, z / 200.0, 3);
        double mountains = m > 0.05 ? (m - 0.05) * 150.0 : 0.0;
        double h = 64.0 + c * 30.0 + d * 5.0 + mountains;
        int height = PerlinNoise.fastFloor(h);
        return Math.max(4, Math.min(Chunk.HEIGHT - 24, height));
    }

    public byte[] generate(int cx, int cz) {
        byte[] blocks = new byte[Chunk.VOLUME];
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;

        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int x = 0; x < Chunk.SIZE; x++) {
                generateColumn(blocks, x, z, baseX + x, baseZ + z);
            }
        }
        placeTrees(blocks, baseX, baseZ);
        return blocks;
    }

    private void generateColumn(byte[] blocks, int x, int z, int wx, int wz) {
        int h = surfaceHeight(wx, wz);
        boolean beach = h <= SEA_LEVEL + 1;
        // 海や砂浜の下は、水が洞窟に流れ込んだように見えないよう地表近くを掘らない
        int caveTop = beach ? h - 6 : h;
        int top = Math.max(h, SEA_LEVEL);

        for (int y = 0; y <= top; y++) {
            Block block;
            if (y == 0) {
                block = Block.BEDROCK;
            } else if (y > h) {
                block = Block.WATER;
            } else if (y < h - 3) {
                block = oreOrStone(wx, y, wz);
            } else if (y < h) {
                block = beach ? Block.SAND : Block.DIRT;
            } else {
                block = beach ? Block.SAND : Block.GRASS;
            }

            if (block != Block.BEDROCK && block != Block.WATER && y <= caveTop && isCave(wx, y, wz)) {
                block = Block.AIR;
            }
            blocks[Chunk.index(x, y, z)] = block.id();
        }
    }

    private Block oreOrStone(int x, int y, int z) {
        double r = unitHash(seed ^ ORE_SALT, x, y, z);
        if (y < 120 && r < 0.012) {
            return Block.COAL_ORE;
        }
        if (y < 64 && r < 0.019) {
            return Block.IRON_ORE;
        }
        return Block.STONE;
    }

    /** ミミズ状のトンネルと、深部の大きな空洞。 */
    boolean isCave(int x, int y, int z) {
        if (y < 4) {
            return false;
        }
        double a = tunnelA.noise3(x / 40.0, y / 28.0, z / 40.0);
        double b = tunnelB.noise3(x / 40.0, y / 28.0, z / 40.0);
        if (a * a + b * b < 0.0028) {
            return true;
        }
        return y < 40 && cavern.noise3(x / 64.0, y / 32.0, z / 64.0) > 0.42;
    }

    /**
     * 木は 6x6 のセルごとに1本の候補位置を決める。チャンク境界をまたぐ木も描けるよう、
     * 周囲のセルも調べて、このチャンクに入る部分だけを書き込む。
     */
    private void placeTrees(byte[] blocks, int baseX, int baseZ) {
        int minCellX = Math.floorDiv(baseX - 3, TREE_CELL);
        int maxCellX = Math.floorDiv(baseX + Chunk.SIZE + 2, TREE_CELL);
        int minCellZ = Math.floorDiv(baseZ - 3, TREE_CELL);
        int maxCellZ = Math.floorDiv(baseZ + Chunk.SIZE + 2, TREE_CELL);

        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                long h = hash(seed ^ TREE_SALT, cellX, 0, cellZ);
                // 森 (セルの約60%に木) と草原 (ほぼ無し) をノイズで分ける
                double density = 0.05 + (forest.fbm2(cellX / 10.0, cellZ / 10.0, 2) + 0.1) * 1.2;
                if ((h & 0xff) >= Math.max(0.02, Math.min(0.6, density)) * 256) {
                    continue;
                }
                int tx = cellX * TREE_CELL + 1 + (int) ((h >>> 8) & 3);
                int tz = cellZ * TREE_CELL + 1 + (int) ((h >>> 12) & 3);
                int ground = surfaceHeight(tx, tz);
                if (ground <= SEA_LEVEL + 1 || ground > 120 || isCave(tx, ground, tz)) {
                    continue;
                }
                int trunkHeight = 4 + (int) ((h >>> 16) % 3);
                buildTree(blocks, baseX, baseZ, tx, ground, tz, trunkHeight, h);
            }
        }
    }

    private static void buildTree(byte[] blocks, int baseX, int baseZ, int tx, int ground, int tz,
                                  int trunkHeight, long h) {
        int top = ground + trunkHeight;
        for (int y = top - 2; y <= top + 1; y++) {
            int radius = y <= top - 1 ? 2 : 1;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;
                    // 角を少し欠けさせて丸みを出す
                    if (corner && (radius == 1 || ((h >>> (20 + ((dx + 2) + (dz + 2) * 5 + y) % 40)) & 1) == 0)) {
                        continue;
                    }
                    setIfInside(blocks, baseX, baseZ, tx + dx, y, tz + dz, Block.LEAVES, true);
                }
            }
        }
        for (int y = ground + 1; y <= top; y++) {
            setIfInside(blocks, baseX, baseZ, tx, y, tz, Block.LOG, false);
        }
        setIfInside(blocks, baseX, baseZ, tx, ground, tz, Block.DIRT, false);
    }

    private static void setIfInside(byte[] blocks, int baseX, int baseZ, int wx, int y, int wz,
                                    Block block, boolean onlyIntoAir) {
        int x = wx - baseX;
        int z = wz - baseZ;
        if (x < 0 || x >= Chunk.SIZE || z < 0 || z >= Chunk.SIZE || y < 0 || y >= Chunk.HEIGHT) {
            return;
        }
        int i = Chunk.index(x, y, z);
        if (onlyIntoAir && blocks[i] != Block.AIR.id()) {
            return;
        }
        blocks[i] = block.id();
    }

    /** 座標から決まる 0以上1未満 の擬似乱数。 */
    static double unitHash(long seed, int x, int y, int z) {
        return (hash(seed, x, y, z) >>> 11) * 0x1.0p-53;
    }

    static long hash(long seed, int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = mix(h);
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h = mix(h);
        h ^= z * 0x165667B19E3779F9L;
        return mix(h);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
