package voxelfrontier.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * プレイヤーの周囲のチャンクを別スレッドで生成して読み込み、遠くのチャンクを破棄する。
 */
public final class ChunkStreamer implements AutoCloseable {

    private final World world;
    private final ExecutorService pool;
    private final int maxPending;
    private final Set<Long> pending = new HashSet<>();
    private final ConcurrentLinkedQueue<Chunk> finished = new ConcurrentLinkedQueue<>();

    private int offsetsRadius = -1;
    private int[][] offsets = new int[0][];

    public ChunkStreamer(World world) {
        this.world = world;
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        this.maxPending = threads * 3;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "chunk-gen-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    /** 毎フレーム呼ぶ。centerCx/Cz を中心に半径 radius チャンクを読み込む。 */
    public void update(int centerCx, int centerCz, int radius) {
        Chunk done;
        while ((done = finished.poll()) != null) {
            pending.remove(done.key());
            if (distSq(done.cx - centerCx, done.cz - centerCz) <= (radius + 1) * (radius + 1)) {
                world.addChunk(done);
            }
        }

        int unloadR = radius + 2;
        world.removeChunksIf(c -> distSq(c.cx - centerCx, c.cz - centerCz) > unloadR * unloadR);

        for (int[] off : offsetsFor(radius)) {
            if (pending.size() >= maxPending) {
                break;
            }
            int cx = centerCx + off[0];
            int cz = centerCz + off[1];
            long key = Chunk.key(cx, cz);
            if (pending.contains(key) || world.isChunkLoaded(cx, cz)) {
                continue;
            }
            pending.add(key);
            pool.execute(() -> {
                try {
                    finished.add(world.generateChunk(cx, cz));
                } catch (Throwable t) {
                    System.err.println("チャンク生成に失敗しました (" + cx + ", " + cz + ")");
                    t.printStackTrace();
                }
            });
        }
    }

    /** 中心から近い順に並べた、半径内のオフセット一覧。 */
    private int[][] offsetsFor(int radius) {
        if (radius != offsetsRadius) {
            List<int[]> list = new ArrayList<>();
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (distSq(dx, dz) <= radius * radius) {
                        list.add(new int[] {dx, dz});
                    }
                }
            }
            offsets = list.toArray(new int[0][]);
            Arrays.sort(offsets, (a, b) -> Integer.compare(distSq(a[0], a[1]), distSq(b[0], b[1])));
            offsetsRadius = radius;
        }
        return offsets;
    }

    private static int distSq(int dx, int dz) {
        return dx * dx + dz * dz;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
