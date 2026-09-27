package voxelfrontier.entity;

import org.joml.Matrix4f;
import voxelfrontier.world.Block;
import voxelfrontier.world.Chunk;
import voxelfrontier.world.World;

/**
 * 操作しているプレイヤー。位置は足元の中心。
 * 当たり判定は幅0.6 x 高さ1.8 の箱で、X/Y/Z 軸ごとに動かしてブロックとの衝突を解決する。
 */
public final class Player {

    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.62;

    private static final double HALF_WIDTH = WIDTH / 2;
    private static final double EPSILON = 1e-4;
    private static final double MAX_STEP = 0.4; // 1回の移動量。これより大きいと分割してすり抜けを防ぐ

    private static final double GRAVITY = 32.0;
    private static final double TERMINAL_VELOCITY = 60.0;
    private static final double JUMP_VELOCITY = 9.0; // 約1.27ブロックの高さまで跳べる
    private static final double WALK_SPEED = 4.3;
    private static final double SPRINT_SPEED = 5.6;
    private static final double SWIM_SPEED = 2.2;
    private static final double FLY_SPEED = 10.9;
    private static final float MAX_PITCH = (float) Math.toRadians(89.0);

    /** 1フレーム分の操作入力。forward/right は -1〜1。 */
    public record Input(float forward, float right, boolean jump, boolean descend, boolean sprint) {
        public static final Input NONE = new Input(0, 0, false, false, false);
    }

    public double x;
    public double y;
    public double z;
    public double vx;
    public double vy;
    public double vz;
    /** 左右の向き。0 で -Z 方向、正で右回り。 */
    public float yaw;
    /** 上下の向き。正で下を向く。 */
    public float pitch;
    public boolean onGround;
    public boolean flying;

    public Player(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void rotate(float deltaYaw, float deltaPitch) {
        yaw = (float) ((yaw + deltaYaw) % (Math.PI * 2));
        pitch = Math.clamp(pitch + deltaPitch, -MAX_PITCH, MAX_PITCH);
    }

    public double eyeY() {
        return y + EYE_HEIGHT;
    }

    /** 視線方向の単位ベクトル {x, y, z}。 */
    public double[] lookDirection() {
        double cp = Math.cos(pitch);
        return new double[] {cp * Math.sin(yaw), -Math.sin(pitch), -cp * Math.cos(yaw)};
    }

    public Matrix4f viewMatrix(Matrix4f dest) {
        return dest.identity()
                .rotateX(pitch)
                .rotateY(yaw)
                .translate((float) -x, (float) -eyeY(), (float) -z);
    }

    public boolean intersectsBlock(int bx, int by, int bz) {
        return x + HALF_WIDTH > bx && x - HALF_WIDTH < bx + 1
                && y + HEIGHT > by && y < by + 1
                && z + HALF_WIDTH > bz && z - HALF_WIDTH < bz + 1;
    }

    public boolean isInWater(World world) {
        return world.getBlock(floor(x), floor(y + 0.4), floor(z)).isLiquid();
    }

    public void update(double dt, Input in, World world) {
        // 足元のチャンクがまだ無いときは、落下しないようその場で待つ
        if (!world.isLoadedAt(floor(x), floor(z))) {
            vx = vy = vz = 0;
            return;
        }

        // 入力 → 水平方向の移動ベクトル (前後移動は yaw だけを使うので、上を向いても水平に歩ける)
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double wishX = in.forward() * sin + in.right() * cos;
        double wishZ = -in.forward() * cos + in.right() * sin;
        double len = Math.sqrt(wishX * wishX + wishZ * wishZ);
        if (len > 1) {
            wishX /= len;
            wishZ /= len;
        }

        boolean inWater = isInWater(world);
        double speed;
        if (flying) {
            speed = FLY_SPEED * (in.sprint() ? 2 : 1);
            vy = ((in.jump() ? 1 : 0) - (in.descend() ? 1 : 0)) * speed;
        } else if (inWater) {
            speed = SWIM_SPEED;
            vy = Math.max(vy - GRAVITY * 0.25 * dt, -3.0);
            if (in.jump()) {
                vy = 3.5;
            }
        } else {
            speed = in.sprint() ? SPRINT_SPEED : WALK_SPEED;
            vy = Math.max(vy - GRAVITY * dt, -TERMINAL_VELOCITY);
            if (in.jump() && onGround) {
                vy = JUMP_VELOCITY;
            }
        }
        vx = wishX * speed;
        vz = wishZ * speed;

        double dx = vx * dt;
        double dy = vy * dt;
        double dz = vz * dt;
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) / MAX_STEP));

        boolean hitY = false;
        for (int i = 0; i < steps; i++) {
            hitY |= moveY(world, dy / steps);
            moveX(world, dx / steps);
            moveZ(world, dz / steps);
        }
        onGround = hitY && dy < 0;
        if (hitY) {
            vy = 0;
        }
    }

    private boolean moveX(World world, double d) {
        if (d == 0) {
            return false;
        }
        x += d;
        double limit = x;
        boolean hit = false;
        for (int by = floor(y); by <= ceil(y + HEIGHT) - 1; by++) {
            for (int bz = floor(z - HALF_WIDTH); bz <= ceil(z + HALF_WIDTH) - 1; bz++) {
                for (int bx = floor(x - HALF_WIDTH); bx <= ceil(x + HALF_WIDTH) - 1; bx++) {
                    if (isSolid(world, bx, by, bz)) {
                        hit = true;
                        limit = d > 0 ? Math.min(limit, bx - HALF_WIDTH - EPSILON) : Math.max(limit, bx + 1 + HALF_WIDTH + EPSILON);
                    }
                }
            }
        }
        x = limit;
        return hit;
    }

    private boolean moveY(World world, double d) {
        if (d == 0) {
            return false;
        }
        y += d;
        double limit = y;
        boolean hit = false;
        for (int by = floor(y); by <= ceil(y + HEIGHT) - 1; by++) {
            for (int bz = floor(z - HALF_WIDTH); bz <= ceil(z + HALF_WIDTH) - 1; bz++) {
                for (int bx = floor(x - HALF_WIDTH); bx <= ceil(x + HALF_WIDTH) - 1; bx++) {
                    if (isSolid(world, bx, by, bz)) {
                        hit = true;
                        limit = d > 0 ? Math.min(limit, by - HEIGHT - EPSILON) : Math.max(limit, by + 1 + EPSILON);
                    }
                }
            }
        }
        y = limit;
        return hit;
    }

    private boolean moveZ(World world, double d) {
        if (d == 0) {
            return false;
        }
        z += d;
        double limit = z;
        boolean hit = false;
        for (int by = floor(y); by <= ceil(y + HEIGHT) - 1; by++) {
            for (int bz = floor(z - HALF_WIDTH); bz <= ceil(z + HALF_WIDTH) - 1; bz++) {
                for (int bx = floor(x - HALF_WIDTH); bx <= ceil(x + HALF_WIDTH) - 1; bx++) {
                    if (isSolid(world, bx, by, bz)) {
                        hit = true;
                        limit = d > 0 ? Math.min(limit, bz - HALF_WIDTH - EPSILON) : Math.max(limit, bz + 1 + HALF_WIDTH + EPSILON);
                    }
                }
            }
        }
        z = limit;
        return hit;
    }

    /** 未読み込みのチャンクは壁として扱い、生成前の場所へ落ちないようにする。 */
    private static boolean isSolid(World world, int bx, int by, int bz) {
        if (by < 0) {
            return true;
        }
        if (by >= Chunk.HEIGHT) {
            return false;
        }
        if (!world.isLoadedAt(bx, bz)) {
            return true;
        }
        Block b = world.getBlock(bx, by, bz);
        return b.isSolid();
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    private static int ceil(double v) {
        return (int) Math.ceil(v);
    }
}
