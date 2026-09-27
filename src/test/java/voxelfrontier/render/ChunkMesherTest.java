package voxelfrontier.render;

import org.junit.jupiter.api.Test;
import voxelfrontier.world.Block;
import voxelfrontier.world.Chunk;

import static org.junit.jupiter.api.Assertions.*;

class ChunkMesherTest {

    private static final int VERTS_PER_FACE = 6;

    private static Chunk[] emptyNeighborhood() {
        Chunk[] n = new Chunk[9];
        for (int i = 0; i < 9; i++) {
            n[i] = new Chunk(i % 3 - 1, i / 3 - 1, new byte[Chunk.VOLUME]);
        }
        return n;
    }

    private static int faces(ChunkMesher.MeshData data) {
        return data.opaqueFloats() / ChunkMesher.FLOATS_PER_VERTEX / VERTS_PER_FACE;
    }

    @Test
    void singleBlockHasSixFaces() {
        Chunk[] n = emptyNeighborhood();
        n[4].set(8, 100, 8, Block.STONE);
        assertEquals(6, faces(ChunkMesher.build(n)));
    }

    @Test
    void touchingBlocksHideSharedFaces() {
        Chunk[] n = emptyNeighborhood();
        n[4].set(8, 100, 8, Block.STONE);
        n[4].set(9, 100, 8, Block.STONE);
        assertEquals(10, faces(ChunkMesher.build(n)));
    }

    @Test
    void faceAgainstNeighborChunkIsHidden() {
        Chunk[] n = emptyNeighborhood();
        n[4].set(15, 100, 8, Block.STONE);
        n[5].set(0, 100, 8, Block.STONE); // +X 側の隣チャンク
        assertEquals(5, faces(ChunkMesher.build(n)));
    }

    @Test
    void waterGoesToSeparateMesh() {
        Chunk[] n = emptyNeighborhood();
        n[4].set(8, 100, 8, Block.WATER);
        ChunkMesher.MeshData data = ChunkMesher.build(n);
        assertEquals(0, data.opaqueFloats());
        assertEquals(6 * VERTS_PER_FACE * ChunkMesher.FLOATS_PER_VERTEX, data.waterFloats());
    }

    @Test
    void cornerIsDarkenedByAmbientOcclusion() {
        Chunk[] n = emptyNeighborhood();
        n[4].set(8, 100, 8, Block.STONE);
        n[4].set(8, 101, 7, Block.STONE); // 上面の -Z 側の辺に接する壁
        ChunkMesher.MeshData data = ChunkMesher.build(n);
        float[] v = data.opaque();
        float min = Float.MAX_VALUE;
        float max = 0;
        for (int i = 0; i < data.opaqueFloats(); i += ChunkMesher.FLOATS_PER_VERTEX) {
            boolean topFaceOfLowerBlock = v[i + 1] == 101 && v[i] >= 8 && v[i] <= 9 && v[i + 2] >= 8 && v[i + 2] <= 9;
            if (topFaceOfLowerBlock) {
                min = Math.min(min, v[i + 6]);
                max = Math.max(max, v[i + 6]);
            }
        }
        assertTrue(min < max, "壁に接する頂点が暗くなっていません");
    }
}
