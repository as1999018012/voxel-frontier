package voxelfrontier;

import voxelfrontier.net.Protocol;

/**
 * コマンドライン引数。
 *
 *   (引数なし)                1人プレイ (ゲーム中に F6 でLANに公開できる)
 *   --host                    ホストとして開始する
 *   --join [アドレス[:ポート]] ホストに参加する。アドレスを省略するとLAN内を自動で探す
 */
public record GameOptions(
        Mode mode,
        String joinAddress,
        int port,
        boolean upnp,
        String playerName,
        String worldName,
        Long seed,
        int renderDistance,
        double screenshotAfter) {

    public enum Mode { SINGLE, HOST, JOIN }

    public static final String USAGE = """
            使い方: gradlew run --args="[オプション]"

              (オプションなし)          1人プレイ (ゲーム中に F6 でLANに公開してホストになれる)
              --host                    ホストとして開始する
              --join [アドレス[:ポート]] ホストに参加する。アドレスを省略するとLAN内のホストを自動で探す
              --port <番号>             待ち受け/接続ポート (既定: %d)
              --upnp                    ホスト時、ルーターにUPnPでポート開放を依頼する (インターネット越し用)
              --name <名前>             プレイヤー名 (既定: OSのユーザー名)
              --world <名前>            セーブするワールド名 (既定: world)
              --seed <数値>             新しいワールドのシード
              --render-distance <数>    描画距離 [チャンク] (既定: 8、2〜32)
              --screenshot-after <秒>   動作確認用: 指定秒後にスクリーンショットを撮って終了する
              --help                    この説明を表示する
            """.formatted(Protocol.DEFAULT_PORT);

    public static GameOptions parse(String[] args) {
        Mode mode = Mode.SINGLE;
        String joinAddress = null;
        int port = Protocol.DEFAULT_PORT;
        boolean upnp = false;
        String name = System.getProperty("user.name", "Player");
        String world = "world";
        Long seed = null;
        int renderDistance = 8;
        double screenshotAfter = 0;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--host" -> mode = Mode.HOST;
                case "--join" -> {
                    mode = Mode.JOIN;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        joinAddress = args[++i];
                    }
                }
                case "--port" -> port = parseInt(a, next(args, ++i, a), 1, 65535);
                case "--upnp" -> upnp = true;
                case "--name" -> name = next(args, ++i, a);
                case "--world" -> world = next(args, ++i, a);
                case "--seed" -> {
                    String s = next(args, ++i, a);
                    try {
                        seed = Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        seed = (long) s.hashCode(); // 文字列のシードも使えるようにする
                    }
                }
                case "--render-distance" -> renderDistance = parseInt(a, next(args, ++i, a), 2, 32);
                case "--screenshot-after" -> screenshotAfter = parseInt(a, next(args, ++i, a), 1, 3600);
                case "--help", "-h" -> throw new UsageException(null);
                default -> throw new UsageException("不明なオプションです: " + a);
            }
        }
        if (!world.matches("[A-Za-z0-9_\\-]+")) {
            throw new UsageException("ワールド名に使えるのは英数字と _ - だけです: " + world);
        }
        return new GameOptions(mode, joinAddress, port, upnp, Protocol.sanitizeName(name), world, seed, renderDistance,
                screenshotAfter);
    }

    private static String next(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new UsageException(option + " の後に値が必要です");
        }
        return args[i];
    }

    private static int parseInt(String option, String value, int min, int max) {
        try {
            int v = Integer.parseInt(value);
            if (v < min || v > max) {
                throw new UsageException(option + " は " + min + "〜" + max + " で指定してください");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new UsageException(option + " には数値を指定してください: " + value);
        }
    }

    /** 引数が不正、または --help。message が null なら説明だけ表示する。 */
    public static final class UsageException extends RuntimeException {
        public UsageException(String message) {
            super(message);
        }
    }
}
