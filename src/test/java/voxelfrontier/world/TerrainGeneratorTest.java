package voxelfrontier.world;

import org.junit.jupiter.api.Test;

import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class TerrainGeneratorTest {

    /**
     * 地形はホストとクライアントがそれぞれシードから生成するため、機種が違っても完全に一致する必要がある。
     * この値は Windows (x64) で生成した結果。PGX (aarch64) でこのテストが通れば、両機種で同じ地形になる。
     */
    private static final long EXPECTED_CHECKSUM = 4120772945L;

    @Test
    void sameSeedGivesSameChunk() {
        byte[] a = new TerrainGenerator(12345).generate(3, -7);
        byte[] b = new TerrainGenerator(12345).generate(3, -7);
        assertArrayEquals(a, b);
    }

    @Test
    void differentSeedGivesDifferentChunk() {
        byte[] a = new TerrainGenerator(1).generate(0, 0);
        byte[] b = new TerrainGenerator(2).generate(0, 0);
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void checksumMatchesAcrossPlatforms() {
        long checksum = checksum(new TerrainGenerator(20260927L));
        System.out.println("地形チェックサム: " + checksum);
        assertEquals(EXPECTED_CHECKSUM, checksum,
                "地形の生成結果が基準と違います。機種によって地形が変わるとマルチプレイで表示がずれます");
    }

    @Test
    void bottomIsBedrockAndSurfaceIsNotAir() {
        TerrainGenerator gen = new TerrainGenerator(42);
        byte[] blocks = gen.generate(0, 0);
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int x = 0; x < Chunk.SIZE; x++) {
                assertEquals(Block.BEDROCK.id(), blocks[Chunk.index(x, 0, z)]);
            }
        }
    }

    @Test
    void containsVarietyOfBlocks() {
        TerrainGenerator gen = new TerrainGenerator(7);
        boolean[] seen = new boolean[Block.values().length];
        for (int cz = -4; cz < 4; cz++) {
            for (int cx = -4; cx < 4; cx++) {
                for (byte id : gen.generate(cx, cz)) {
                    seen[id] = true;
                }
            }
        }
        for (Block b : new Block[] {Block.STONE, Block.DIRT, Block.GRASS, Block.BEDROCK, Block.COAL_ORE}) {
            assertTrue(seen[b.ordinal()], b + " が生成されていません");
        }
    }

    static long checksum(TerrainGenerator gen) {
        CRC32 crc = new CRC32();
        for (int cz = -2; cz <= 2; cz++) {
            for (int cx = -2; cx <= 2; cx++) {
                crc.update(gen.generate(cx * 37, cz * 53));
            }
        }
        return crc.getValue();
    }
}
