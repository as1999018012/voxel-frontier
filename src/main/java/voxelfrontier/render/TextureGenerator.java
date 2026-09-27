package voxelfrontier.render;

import voxelfrontier.world.BlockTexture;

import java.util.Random;

/**
 * 16x16 のドット絵テクスチャをプログラムで生成する (画像ファイルを用意せずに済むように)。
 * 乱数のシードは固定なので、毎回同じ絵柄になる。ピクセルは 0xAARRGGBB。
 */
public final class TextureGenerator {

    public static final int SIZE = 16;

    private TextureGenerator() {
    }

    public static int[] generate(BlockTexture texture) {
        Random r = new Random(0x5EED0000L + texture.ordinal() * 7919L);
        return switch (texture) {
            case STONE -> stone(r);
            case DIRT -> noisy(r, 0x866043, 0.22f);
            case GRASS_TOP -> noisy(r, 0x6aa84f, 0.22f);
            case GRASS_SIDE -> grassSide(r);
            case COBBLESTONE -> cobblestone(r);
            case PLANKS -> planks(r);
            case LOG_SIDE -> logSide(r);
            case LOG_TOP -> logTop(r);
            case LEAVES -> leaves(r);
            case SAND -> noisy(r, 0xdbd3a0, 0.1f);
            case WATER -> noisy(r, 0x3a6fd8, 0.08f);
            case BEDROCK -> noisy(r, 0x3c3c3c, 0.6f);
            case COAL_ORE -> ore(r, 0x242424);
            case IRON_ORE -> ore(r, 0xd8a787);
            case BRICKS -> bricks(r);
            case PLAYER_SKIN -> noisy(r, 0xe8b894, 0.04f);
            case PLAYER_FACE -> face(r);
            case PLAYER_SHIRT -> noisy(r, 0xf0f0f0, 0.08f);
        };
    }

    // ---- 各テクスチャ ----

    private static int[] stone(Random r) {
        int[] px = noisy(r, 0x7f7f7f, 0.18f);
        // 暗い筋を少し入れる
        for (int i = 0; i < 6; i++) {
            int x = r.nextInt(SIZE);
            int y = r.nextInt(SIZE);
            int len = 2 + r.nextInt(3);
            for (int k = 0; k < len; k++) {
                set(px, x + k, y, scale(0x6a6a6a, 0.9f + r.nextFloat() * 0.1f));
            }
        }
        return px;
    }

    private static int[] grassSide(Random r) {
        int[] px = noisy(r, 0x866043, 0.22f);
        for (int x = 0; x < SIZE; x++) {
            int depth = 3 + r.nextInt(2) + (r.nextInt(4) == 0 ? 1 : 0);
            for (int y = 0; y < depth; y++) {
                set(px, x, y, vary(r, 0x6aa84f, 0.2f));
            }
        }
        return px;
    }

    private static int[] cobblestone(Random r) {
        // ボロノイ分割で石の粒を作り、境界を暗くする
        int n = 9;
        int[][] seeds = new int[n][2];
        float[] tone = new float[n];
        for (int i = 0; i < n; i++) {
            seeds[i][0] = r.nextInt(SIZE);
            seeds[i][1] = r.nextInt(SIZE);
            tone[i] = 0.8f + r.nextFloat() * 0.35f;
        }
        int[] px = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int best = 0;
                int bestD = Integer.MAX_VALUE;
                int second = Integer.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    int dx = wrapDist(x, seeds[i][0]);
                    int dy = wrapDist(y, seeds[i][1]);
                    int d = dx * dx + dy * dy;
                    if (d < bestD) {
                        second = bestD;
                        bestD = d;
                        best = i;
                    } else if (d < second) {
                        second = d;
                    }
                }
                boolean edge = second - bestD <= 3;
                int base = edge ? 0x505050 : scale(0x8a8a8a, tone[best]);
                set(px, x, y, vary(r, base, 0.08f));
            }
        }
        return px;
    }

    private static int[] planks(Random r) {
        int[] px = new int[SIZE * SIZE];
        for (int board = 0; board < 4; board++) {
            float tone = 0.9f + r.nextFloat() * 0.15f;
            int seam = r.nextInt(SIZE);
            for (int y = board * 4; y < board * 4 + 4; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int c = scale(0xb08c55, tone);
                    if (y % 4 == 3 || x == seam) {
                        c = scale(c, 0.7f);
                    }
                    set(px, x, y, vary(r, c, 0.06f));
                }
            }
        }
        return px;
    }

    private static int[] logSide(Random r) {
        int[] px = new int[SIZE * SIZE];
        float[] column = new float[SIZE];
        for (int x = 0; x < SIZE; x++) {
            column[x] = 0.8f + r.nextFloat() * 0.3f;
        }
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                set(px, x, y, vary(r, scale(0x6b5132, column[x]), 0.08f));
            }
        }
        return px;
    }

    private static int[] logTop(Random r) {
        int[] px = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int ring = Math.max(Math.abs(2 * x - 15), Math.abs(2 * y - 15)) / 2;
                int c;
                if (ring >= 7) {
                    c = 0x6b5132; // 樹皮
                } else {
                    c = ring % 2 == 0 ? 0xb8945f : 0x9c7a48;
                }
                set(px, x, y, vary(r, c, 0.06f));
            }
        }
        return px;
    }

    private static int[] leaves(Random r) {
        int[] px = new int[SIZE * SIZE];
        for (int i = 0; i < px.length; i++) {
            float t = r.nextFloat();
            int c = t < 0.2f ? 0x24561b : t < 0.6f ? 0x3b7d2a : 0x4f9a37;
            px[i] = 0xff000000 | vary(r, c, 0.1f);
        }
        return px;
    }

    private static int[] ore(Random r, int oreColor) {
        int[] px = stone(r);
        for (int i = 0; i < 4; i++) {
            int cx = 2 + r.nextInt(SIZE - 4);
            int cy = 2 + r.nextInt(SIZE - 4);
            for (int k = 0; k < 4; k++) {
                set(px, cx + r.nextInt(3) - 1, cy + r.nextInt(3) - 1, vary(r, oreColor, 0.15f));
            }
        }
        return px;
    }

    private static int[] bricks(Random r) {
        int[] px = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            int row = y / 4;
            int offset = row % 2 == 0 ? 0 : 4;
            for (int x = 0; x < SIZE; x++) {
                boolean mortar = y % 4 == 3 || (x + offset) % 8 == 7;
                int c = mortar ? 0xb5aca0 : 0x9a4b3a;
                set(px, x, y, vary(r, c, mortar ? 0.05f : 0.12f));
            }
        }
        return px;
    }

    private static int[] face(Random r) {
        int[] px = noisy(r, 0xe8b894, 0.04f);
        // 目 (白目 + 黒目)
        for (int[] eye : new int[][] {{3, 7}, {10, 7}}) {
            set(px, eye[0], eye[1], 0xffffff);
            set(px, eye[0] + 1, eye[1], 0xffffff);
            set(px, eye[0] + 2, eye[1], 0x3b2a8a);
            set(px, eye[0], eye[1] + 1, 0xffffff);
            set(px, eye[0] + 1, eye[1] + 1, 0xffffff);
            set(px, eye[0] + 2, eye[1] + 1, 0x3b2a8a);
        }
        // 口
        for (int x = 6; x <= 9; x++) {
            set(px, x, 11, 0x8a4f3a);
        }
        // 髪
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (y < 3 || x < 2 || x > 13) {
                    set(px, x, y, vary(r, 0x4a3020, 0.1f));
                }
            }
        }
        return px;
    }

    // ---- 補助 ----

    private static int[] noisy(Random r, int base, float variation) {
        int[] px = new int[SIZE * SIZE];
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xff000000 | vary(r, base, variation);
        }
        return px;
    }

    private static int vary(Random r, int rgb, float variation) {
        return scale(rgb, 1.0f + (r.nextFloat() - 0.5f) * variation * 2);
    }

    private static int scale(int rgb, float f) {
        int red = Math.min(255, Math.round(((rgb >> 16) & 0xff) * f));
        int green = Math.min(255, Math.round(((rgb >> 8) & 0xff) * f));
        int blue = Math.min(255, Math.round((rgb & 0xff) * f));
        return (red << 16) | (green << 8) | blue;
    }

    private static void set(int[] px, int x, int y, int rgb) {
        px[Math.floorMod(y, SIZE) * SIZE + Math.floorMod(x, SIZE)] = 0xff000000 | rgb;
    }

    private static int wrapDist(int a, int b) {
        int d = Math.abs(a - b);
        return Math.min(d, SIZE - d);
    }
}
