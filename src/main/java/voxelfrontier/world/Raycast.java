package voxelfrontier.world;

/**
 * 視線の先のブロックを探す (Amanatides & Woo のボクセル走査)。
 * 1マスずつ境界をまたいで進むので、薄い角をすり抜けない。
 */
public final class Raycast {

    /** 当たったブロックと、当たった面の法線 (設置位置 = ブロック + 法線)。 */
    public record Hit(int x, int y, int z, int nx, int ny, int nz, Block block) {
    }

    private Raycast() {
    }

    public static Hit cast(World world, double ox, double oy, double oz,
                           double dx, double dy, double dz, double maxDistance) {
        int x = PerlinNoise.fastFloor(ox);
        int y = PerlinNoise.fastFloor(oy);
        int z = PerlinNoise.fastFloor(oz);

        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        double tMaxX = stepX > 0 ? (x + 1 - ox) * tDeltaX : stepX < 0 ? (ox - x) * tDeltaX : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0 ? (y + 1 - oy) * tDeltaY : stepY < 0 ? (oy - y) * tDeltaY : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0 ? (z + 1 - oz) * tDeltaZ : stepZ < 0 ? (oz - z) * tDeltaZ : Double.POSITIVE_INFINITY;

        int nx = 0;
        int ny = 0;
        int nz = 0;
        double t = 0;
        while (t <= maxDistance) {
            Block block = world.getBlock(x, y, z);
            if (block.isTargetable()) {
                return new Hit(x, y, z, nx, ny, nz, block);
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                nx = -stepX;
                ny = 0;
                nz = 0;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                nx = 0;
                ny = -stepY;
                nz = 0;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                nx = 0;
                ny = 0;
                nz = -stepZ;
            }
        }
        return null;
    }
}
