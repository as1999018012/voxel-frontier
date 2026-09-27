package voxelfrontier.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import voxelfrontier.entity.Player;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldStorageTest {

    @Test
    void savesAndLoadsEditsAndPlayer(@TempDir Path dir) throws Exception {
        World world = new World(99);
        world.setTime(123.5);
        world.setBlock(5, 70, -3, Block.BRICKS);
        world.setBlock(-40, 1, 17, Block.AIR);
        Player player = new Player(1.5, 80, -2.25);
        player.yaw = 1.25f;
        player.flying = true;

        WorldStorage.save(dir, world, player);

        WorldStorage.Level level = WorldStorage.loadLevel(dir).orElseThrow();
        assertEquals(99, level.seed());
        assertEquals(123.5, level.time());
        assertEquals(-2.25, level.player().z());
        assertEquals(1.25f, level.player().yaw());
        assertTrue(level.player().flying());

        World loaded = new World(level.seed());
        WorldStorage.loadEdits(dir, loaded);
        assertEquals(Block.BRICKS, loaded.peekBlock(5, 70, -3));
        assertEquals(Block.AIR, loaded.peekBlock(-40, 1, 17));

        // チャンクを読み込んだときにも変更が反映される
        Chunk chunk = loaded.loadChunkNow(0, -1);
        assertEquals(Block.BRICKS, chunk.get(5, 70, 13));
    }

    @Test
    void missingSaveIsEmpty(@TempDir Path dir) throws Exception {
        assertTrue(WorldStorage.loadLevel(dir.resolve("none")).isEmpty());
    }
}
