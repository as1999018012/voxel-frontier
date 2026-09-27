package voxelfrontier.net;

/** 通信の共通定数。 */
public final class Protocol {

    /** パケット形式を変えたら上げる。ホストとクライアントで一致しないと接続を断る。 */
    public static final int VERSION = 1;
    public static final int MAGIC = 0x56465254; // "VFRT"

    /** ゲーム本体の接続を待ち受けるTCPポート。 */
    public static final int DEFAULT_PORT = 24454;
    /** LAN内のホスト検出に使うUDPポート。 */
    public static final int DISCOVERY_PORT = 24455;

    public static final int MAX_PLAYERS = 8;
    public static final int MAX_NAME_LENGTH = 16;
    /** ブロックを操作できる距離 (目からブロック中心まで)。ホストはこれを超える操作を拒否する。 */
    public static final double MAX_REACH = 6.0;

    /** 位置の送信間隔 [秒] (= 毎秒20回)。 */
    public static final double MOVE_INTERVAL = 0.05;
    public static final double TIME_SYNC_INTERVAL = 5.0;

    private Protocol() {
    }

    public static String sanitizeName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\p{Cntrl};]", "").strip();
        if (cleaned.isEmpty()) {
            cleaned = "Player";
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }
}
