package voxelfrontier;

import voxelfrontier.world.Block;
import voxelfrontier.world.Chunk;
import voxelfrontier.world.World;

/** テスト用のワールドを作る。 */
public final class TestWorlds {

    private TestWorlds() {
    }

    /** y = 0〜floorTop が石、それより上が空気の平らなワールド (チャンク -r〜r を読み込み済み)。 */
    public static World flat(int floorTop, int radius) {
        World world = new World(0);
        for (int cz = -radius; cz <= radius; cz++) {
            for (int cx = -radius; cx <= radius; cx++) {
                world.addChunk(new Chunk(cx, cz, flatBlocks(floorTop)));
            }
        }
        return world;
    }

    public static byte[] flatBlocks(int floorTop) {
        byte[] blocks = new byte[Chunk.VOLUME];
        for (int y = 0; y <= floorTop; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    blocks[Chunk.index(x, y, z)] = Block.STONE.id();
                }
            }
        }
        return blocks;
    }
}
