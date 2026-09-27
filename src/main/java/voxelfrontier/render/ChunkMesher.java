package voxelfrontier.render;

import voxelfrontier.world.Block;
import voxelfrontier.world.BlockTexture;
import voxelfrontier.world.Chunk;

/**
 * チャンクのブロック配列から描画用の頂点データを作る。OpenGLには触らないので別スレッドで実行できる。
 *
 * - 隣が不透明ブロックの面は見えないので作らない (隠れ面の除去)
 * - 各頂点の周囲のブロックを調べ、角やくぼみを暗くする (アンビエントオクルージョン)
 * - 水は半透明で別に描くため、別の頂点データに分ける
 *
 * 頂点の形式: 位置(3) + UV(2) + テクスチャレイヤー(1) + 明るさ(1) = 7 float
 */
public final class ChunkMesher {

    public static final int FLOATS_PER_VERTEX = 7;

    /** AO の段階 (0=最も暗い〜3=遮るもの無し) ごとの明るさ。 */
    private static final float[] AO_LIGHT = {0.5f, 0.68f, 0.84f, 1.0f};
    /** 水面は周りより少し低くする。 */
    private static final float WATER_SURFACE = 0.875f;

    private static final boolean[] OPAQUE = new boolean[256];
    private static final int[][] LAYERS = new int[256][6];

    static {
        for (Block b : Block.values()) {
            int id = b.ordinal();
            OPAQUE[id] = b.isOpaque();
            if (b.topTexture() != null) {
                for (int face = 0; face < 6; face++) {
                    BlockTexture t = face == Faces.TOP ? b.topTexture()
                            : face == Faces.BOTTOM ? b.bottomTexture() : b.sideTexture();
                    LAYERS[id][face] = t.layer();
                }
            }
        }
    }

    public record MeshData(float[] opaque, int opaqueFloats, float[] water, int waterFloats) {
    }

    private ChunkMesher() {
    }

    /**
     * @param neighborhood 3x3 のチャンク。添字 (dx+1) + (dz+1)*3、中央 [4] が対象チャンク。
     *                     面の判定とAOのため、周囲8チャンクも読み込み済みであること。
     */
    public static MeshData build(Chunk[] neighborhood) {
        byte[] center = neighborhood[4].rawBlocks();
        int maxY = highestNonAir(center);

        FloatList opaque = new FloatList(16 * 1024);
        FloatList water = new FloatList(1024);
        int waterId = Block.WATER.ordinal();

        for (int y = 0; y <= maxY; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    int id = center[Chunk.index(x, y, z)] & 0xff;
                    if (id == 0) {
                        continue;
                    }
                    if (id == waterId) {
                        addWater(neighborhood, water, x, y, z, id);
                    } else {
                        addSolid(neighborhood, opaque, x, y, z, id);
                    }
                }
            }
        }
        return new MeshData(opaque.array(), opaque.size(), water.array(), water.size());
    }

    private static void addSolid(Chunk[] n, FloatList out, int x, int y, int z, int id) {
        for (int face = 0; face < 6; face++) {
            int[] nd = Faces.NORMAL[face];
            int lx = x + nd[0];
            int ly = y + nd[1];
            int lz = z + nd[2];
            if (OPAQUE[blockAt(n, lx, ly, lz)]) {
                continue;
            }

            int[][] corners = Faces.CORNERS[face];
            int[] ao = new int[4];
            for (int i = 0; i < 4; i++) {
                ao[i] = ambientOcclusion(n, face, corners[i], lx, ly, lz);
            }
            // AOの明暗が不自然に見えないよう、明るい対角線で四角形を分割する
            boolean flip = ao[0] + ao[2] < ao[1] + ao[3];
            int[] order = flip ? new int[] {1, 2, 3, 1, 3, 0} : new int[] {0, 1, 2, 0, 2, 3};
            float layer = LAYERS[id][face];
            float shade = Faces.SHADE[face];
            for (int k : order) {
                int[] c = corners[k];
                out.add(x + c[0]);
                out.add(y + c[1]);
                out.add(z + c[2]);
                out.add(Faces.u(face, c));
                out.add(Faces.v(face, c));
                out.add(layer);
                out.add(shade * AO_LIGHT[ao[k]]);
            }
        }
    }

    /**
     * 頂点の AO。面の外側の層で、頂点に接する「辺の隣2つ」と「角の隣1つ」が埋まっているほど暗い。
     * (lx, ly, lz) は面の外側に隣接するマス。
     */
    private static int ambientOcclusion(Chunk[] n, int face, int[] corner, int lx, int ly, int lz) {
        int axis = Faces.NORMAL[face][0] != 0 ? 0 : Faces.NORMAL[face][1] != 0 ? 1 : 2;
        int t1 = axis == 0 ? 1 : 0;
        int t2 = axis == 2 ? 1 : 2;
        int[] d1 = new int[3];
        int[] d2 = new int[3];
        d1[t1] = corner[t1] == 1 ? 1 : -1;
        d2[t2] = corner[t2] == 1 ? 1 : -1;

        boolean side1 = OPAQUE[blockAt(n, lx + d1[0], ly + d1[1], lz + d1[2])];
        boolean side2 = OPAQUE[blockAt(n, lx + d2[0], ly + d2[1], lz + d2[2])];
        boolean cornerBlock = OPAQUE[blockAt(n, lx + d1[0] + d2[0], ly + d1[1] + d2[1], lz + d1[2] + d2[2])];
        if (side1 && side2) {
            return 0;
        }
        return 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (cornerBlock ? 1 : 0));
    }

    private static void addWater(Chunk[] n, FloatList out, int x, int y, int z, int waterId) {
        boolean waterAbove = blockAt(n, x, y + 1, z) == waterId;
        float top = waterAbove ? 1.0f : WATER_SURFACE;
        float layer = BlockTexture.WATER.layer();
        for (int face = 0; face < 6; face++) {
            int[] nd = Faces.NORMAL[face];
            int neighbor = blockAt(n, x + nd[0], y + nd[1], z + nd[2]);
            // 水同士・不透明ブロックとの間の面は描かない。水面は上が空気なら常に描く
            if (neighbor == waterId || OPAQUE[neighbor]) {
                continue;
            }
            int[][] corners = Faces.CORNERS[face];
            for (int k : new int[] {0, 1, 2, 0, 2, 3}) {
                int[] c = corners[k];
                out.add(x + c[0]);
                out.add(y + (c[1] == 1 ? top : 0));
                out.add(z + c[2]);
                out.add(Faces.u(face, c));
                out.add(Faces.v(face, c));
                out.add(layer);
                out.add(Faces.SHADE[face]);
            }
        }
    }

    /** 3x3 チャンクをまたいでブロックIDを読む。x, z は -1〜16。 */
    private static int blockAt(Chunk[] n, int x, int y, int z) {
        if (y < 0) {
            return Block.STONE.ordinal();
        }
        if (y >= Chunk.HEIGHT) {
            return 0;
        }
        int ci = 4;
        if (x < 0) {
            ci -= 1;
            x += Chunk.SIZE;
        } else if (x >= Chunk.SIZE) {
            ci += 1;
            x -= Chunk.SIZE;
        }
        if (z < 0) {
            ci -= 3;
            z += Chunk.SIZE;
        } else if (z >= Chunk.SIZE) {
            ci += 3;
            z -= Chunk.SIZE;
        }
        Chunk c = n[ci];
        return c == null ? 0 : c.rawBlocks()[Chunk.index(x, y, z)] & 0xff;
    }

    private static int highestNonAir(byte[] blocks) {
        int perLayer = Chunk.SIZE * Chunk.SIZE;
        for (int y = Chunk.HEIGHT - 1; y >= 0; y--) {
            int base = y * perLayer;
            for (int i = 0; i < perLayer; i++) {
                if (blocks[base + i] != 0) {
                    return y;
                }
            }
        }
        return -1;
    }
}
