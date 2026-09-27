package voxelfrontier.world;

import java.util.Random;

/**
 * Ken Perlin の Improved Noise。
 *
 * マルチプレイでは各クライアントがシードから同じ地形を再生成するため、
 * Windows (x64) と PGX (aarch64) で結果がビット単位で一致しなければならない。
 * Java の四則演算は全プラットフォームで IEEE 754 準拠なので、ここでは
 * Math.sin / exp / pow のような環境差が出うる関数は使わず、四則演算だけで計算する。
 */
final class PerlinNoise {

    private final int[] perm = new int[512];

    PerlinNoise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        Random random = new Random(seed); // java.util.Random のアルゴリズムは仕様で固定されている
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
        }
    }

    /** オクターブを重ねたノイズ。おおよそ -1〜1。 */
    double fbm2(double x, double z, int octaves) {
        double sum = 0;
        double amplitude = 1;
        double frequency = 1;
        double norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += noise3(x * frequency, 0.0, z * frequency) * amplitude;
            norm += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return sum / norm;
    }

    double noise3(double x, double y, double z) {
        int xi = fastFloor(x);
        int yi = fastFloor(y);
        int zi = fastFloor(z);
        x -= xi;
        y -= yi;
        z -= zi;
        xi &= 255;
        yi &= 255;
        zi &= 255;

        double u = fade(x);
        double v = fade(y);
        double w = fade(z);

        int[] p = perm;
        int a = p[xi] + yi;
        int aa = p[a] + zi;
        int ab = p[a + 1] + zi;
        int b = p[xi + 1] + yi;
        int ba = p[b] + zi;
        int bb = p[b + 1] + zi;

        return lerp(w,
                lerp(v,
                        lerp(u, grad(p[aa], x, y, z), grad(p[ba], x - 1, y, z)),
                        lerp(u, grad(p[ab], x, y - 1, z), grad(p[bb], x - 1, y - 1, z))),
                lerp(v,
                        lerp(u, grad(p[aa + 1], x, y, z - 1), grad(p[ba + 1], x - 1, y, z - 1)),
                        lerp(u, grad(p[ab + 1], x, y - 1, z - 1), grad(p[bb + 1], x - 1, y - 1, z - 1))));
    }

    static int fastFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14) ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
