package voxelfrontier;

/** エントリーポイント。引数を解釈してゲームを起動する。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        GameOptions options;
        try {
            options = GameOptions.parse(args);
        } catch (GameOptions.UsageException e) {
            if (e.getMessage() != null) {
                System.err.println(e.getMessage());
                System.err.println();
            }
            System.out.println(GameOptions.USAGE);
            System.exit(e.getMessage() == null ? 0 : 2);
            return;
        }

        try (Game game = Game.create(options)) {
            game.run();
        } catch (Game.StartupException e) {
            System.err.println("起動できませんでした: " + e.getMessage());
            System.exit(1);
        }
    }
}
