package voxelfrontier.world;

import org.junit.jupiter.api.Test;
import voxelfrontier.TestWorlds;

import static org.junit.jupiter.api.Assertions.*;

class RaycastTest {

    @Test
    void lookingDownHitsFloorTopFace() {
        World world = TestWorlds.flat(4, 1);
        Raycast.Hit hit = Raycast.cast(world, 0.5, 6.62, 0.5, 0, -1, 0, 5);
        assertNotNull(hit);
        assertEquals(0, hit.x());
        assertEquals(4, hit.y());
        assertEquals(0, hit.z());
        assertEquals(1, hit.ny()); // 上面に当たった → 置くなら1つ上
    }

    @Test
    void hitsWallSideFace() {
        World world = TestWorlds.flat(4, 1);
        world.setBlock(3, 6, 0, Block.PLANKS);
        Raycast.Hit hit = Raycast.cast(world, 0.5, 6.5, 0.5, 1, 0, 0, 5);
        assertNotNull(hit);
        assertEquals(3, hit.x());
        assertEquals(-1, hit.nx());
        assertEquals(Block.PLANKS, hit.block());
    }

    @Test
    void missesBeyondMaxDistance() {
        World world = TestWorlds.flat(4, 1);
        assertNull(Raycast.cast(world, 0.5, 20, 0.5, 0, -1, 0, 5));
    }

    @Test
    void passesThroughWater() {
        World world = TestWorlds.flat(4, 1);
        world.setBlock(0, 5, 0, Block.WATER);
        Raycast.Hit hit = Raycast.cast(world, 0.5, 6.5, 0.5, 0, -1, 0, 5);
        assertNotNull(hit);
        assertEquals(4, hit.y());
    }
}
