package voxelfrontier.net;

import voxelfrontier.entity.Player;
import voxelfrontier.world.Block;
import voxelfrontier.world.World;

import java.util.Collection;

/** ホストに参加してのプレイ。ワールドの正はホストが持つ。 */
public final class ClientSession implements Session {

    private final GameClient client;
    private final World world;
    private final String hostLabel;

    public ClientSession(GameClient client, World world, String hostLabel) {
        this.client = client;
        this.world = world;
        this.hostLabel = hostLabel;
    }

    @Override
    public void tick(double dt, Player local) {
        client.tick(dt, local, world);
    }

    @Override
    public void changeBlock(int x, int y, int z, Block block) {
        // 待たずに画面へ反映し (体感を良くするため)、ホストへ要求を送る
        world.setBlock(x, y, z, block);
        client.requestBlockChange(x, y, z, block);
    }

    @Override
    public Collection<RemotePlayer> remotePlayers() {
        return client.remotePlayers();
    }

    @Override
    public String status() {
        return "参加中 " + hostLabel + " (" + (client.remotePlayers().size() + 1) + "人)";
    }

    @Override
    public String disconnectReason() {
        return client.disconnectReason();
    }

    @Override
    public boolean ownsWorld() {
        return false;
    }

    @Override
    public void close() {
        client.close();
    }
}
