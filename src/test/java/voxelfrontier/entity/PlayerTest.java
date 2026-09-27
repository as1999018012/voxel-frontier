package voxelfrontier.entity;

import org.junit.jupiter.api.Test;
import voxelfrontier.TestWorlds;
import voxelfrontier.world.Block;
import voxelfrontier.world.World;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTest {

    private static final double DT = 1.0 / 60;

    private static void simulate(Player p, World w, Player.Input in, double seconds) {
        for (double t = 0; t < seconds; t += DT) {
            p.update(DT, in, w);
        }
    }

    @Test
    void fallsAndLandsOnFloor() {
        World world = TestWorlds.flat(4, 1); // 床の上面は y = 5
        Player p = new Player(0.5, 20, 0.5);
        simulate(p, world, Player.Input.NONE, 3);
        assertEquals(5.0, p.y, 0.01);
        assertTrue(p.onGround);
    }

    @Test
    void wallStopsWalking() {
        World world = TestWorlds.flat(4, 1);
        for (int y = 5; y <= 7; y++) {
            world.setBlock(3, y, 0, Block.STONE);
        }
        Player p = new Player(0.5, 5.001, 0.5);
        p.yaw = (float) (Math.PI / 2); // +X 方向を向く
        simulate(p, world, new Player.Input(1, 0, false, false, false), 2);
        assertEquals(3 - Player.WIDTH / 2, p.x, 0.01);
    }

    @Test
    void jumpsOntoOneBlockStep() {
        World world = TestWorlds.flat(4, 1);
        for (int z = -2; z <= 2; z++) {
            world.setBlock(2, 5, z, Block.STONE);
        }
        Player p = new Player(0.5, 5.001, 0.5);
        p.yaw = (float) (Math.PI / 2);
        simulate(p, world, Player.Input.NONE, 0.2);
        simulate(p, world, new Player.Input(1, 0, true, false, false), 1.0);
        assertTrue(p.x > 2.3, "段差に登れていません x=" + p.x);
        assertEquals(6.0, p.y, 0.3);
    }

    @Test
    void doesNotFallIntoUnloadedChunk() {
        World world = TestWorlds.flat(4, 0); // チャンク (0,0) だけ
        Player p = new Player(40, 20, 40);
        simulate(p, world, Player.Input.NONE, 1);
        assertEquals(20, p.y, 1e-9);
    }

    @Test
    void flyingIgnoresGravity() {
        World world = TestWorlds.flat(4, 1);
        Player p = new Player(0.5, 20, 0.5);
        p.flying = true;
        simulate(p, world, Player.Input.NONE, 1);
        assertEquals(20, p.y, 1e-9);
    }
}
