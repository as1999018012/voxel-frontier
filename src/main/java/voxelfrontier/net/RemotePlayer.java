package voxelfrontier.net;

/**
 * 他のプレイヤー。通信で届く位置は毎秒20回なので、描画位置はその間を滑らかに補間する。
 */
public final class RemotePlayer {

    public final int id;
    public final String name;

    private double targetX;
    private double targetY;
    private double targetZ;
    private float targetYaw;
    private float targetPitch;

    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;

    public RemotePlayer(int id, String name, double x, double y, double z, float yaw, float pitch) {
        this.id = id;
        this.name = name;
        setTarget(x, y, z, yaw, pitch);
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public void setTarget(double x, double y, double z, float yaw, float pitch) {
        targetX = x;
        targetY = y;
        targetZ = z;
        targetYaw = yaw;
        targetPitch = pitch;
    }

    public double targetX() {
        return targetX;
    }

    public double targetY() {
        return targetY;
    }

    public double targetZ() {
        return targetZ;
    }

    public float targetYaw() {
        return targetYaw;
    }

    public float targetPitch() {
        return targetPitch;
    }

    public void interpolate(double dt) {
        double t = 1.0 - Math.exp(-dt * 15.0);
        double dx = targetX - x;
        double dy = targetY - y;
        double dz = targetZ - z;
        if (dx * dx + dy * dy + dz * dz > 64) { // 大きく離れたら (テレポート等) 瞬時に合わせる
            x = targetX;
            y = targetY;
            z = targetZ;
        } else {
            x += dx * t;
            y += dy * t;
            z += dz * t;
        }
        // 角度は -π〜π の最短方向に回す
        double dyaw = Math.IEEEremainder(targetYaw - yaw, Math.PI * 2);
        yaw += (float) (dyaw * t);
        pitch += (float) ((targetPitch - pitch) * t);
    }

    public Packet.PlayerInfo toInfo() {
        return new Packet.PlayerInfo(id, name, targetX, targetY, targetZ, targetYaw, targetPitch);
    }
}
