package voxelfrontier.net;

import voxelfrontier.entity.Player;
import voxelfrontier.world.Block;

import java.util.Collection;

/**
 * ゲーム本体から見た「プレイの形態」。1人プレイ/ホスト/参加者の違いをここに閉じ込める。
 */
public interface Session extends AutoCloseable {

    /** 毎フレーム呼ぶ。通信の送受信などを行う。 */
    void tick(double dt, Player local);

    /** ローカルプレイヤーによるブロック変更。 */
    void changeBlock(int x, int y, int z, Block block);

    Collection<RemotePlayer> remotePlayers();

    /** タイトルバーに出す状態表示。 */
    String status();

    /** 切断されてゲームを続けられなければ理由、問題なければ null。 */
    String disconnectReason();

    /** このプロセスがワールドを保存すべきか (ホスト/1人プレイ)。 */
    boolean ownsWorld();

    /** 1人プレイ中のワールドをLANに公開する。すでに公開中・参加者なら何もしない。 */
    default void openToLan() {
    }

    @Override
    void close();
}
