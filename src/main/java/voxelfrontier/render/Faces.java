package voxelfrontier.render;

/**
 * 立方体の6面の定義。各面の4隅は外側から見て反時計回り (CCW) に並べてあり、
 * 背面カリングで裏側が描かれないようになっている。
 */
final class Faces {

    static final int TOP = 0;
    static final int BOTTOM = 1;
    static final int EAST = 2;  // +X
    static final int WEST = 3;  // -X
    static final int SOUTH = 4; // +Z
    static final int NORTH = 5; // -Z

    /** 面の法線方向。 */
    static final int[][] NORMAL = {
            {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
    };

    /** 面の4隅 (0か1)。 */
    static final int[][][] CORNERS = {
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
    };

    /** Minecraft風の簡易ライティング: 面の向きごとの明るさ。 */
    static final float[] SHADE = {1.0f, 0.5f, 0.6f, 0.6f, 0.8f, 0.8f};

    private Faces() {
    }

    /** 隅のテクスチャ座標 u。側面は水平方向、上下面は x。 */
    static float u(int face, int[] c) {
        return switch (face) {
            case EAST -> 1 - c[2];
            case WEST -> c[2];
            case SOUTH -> c[0];
            case NORTH -> 1 - c[0];
            default -> c[0];
        };
    }

    /** 隅のテクスチャ座標 v。側面はテクスチャの上端 (v=0) がブロックの上になる。 */
    static float v(int face, int[] c) {
        return face == TOP || face == BOTTOM ? c[2] : 1 - c[1];
    }
}
