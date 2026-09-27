package voxelfrontier.render;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import voxelfrontier.world.Block;
import voxelfrontier.world.Chunk;
import voxelfrontier.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL33C.GL_TRIANGLES;

/**
 * チャンクごとのメッシュを管理する。
 * メッシュ生成 (ChunkMesher) は別スレッドで行い、完成したものをメインスレッドでGPUに送る。
 * ブロックが変わったチャンク (と、境界なら隣のチャンク) は作り直す。
 */
public final class ChunkRenderer implements World.Listener, AutoCloseable {

    private record Result(long key, ChunkMesher.MeshData data) {
    }

    private static final class GpuChunk implements AutoCloseable {
        final int cx;
        final int cz;
        final Mesh opaque = new Mesh(3, 2, 1, 1);
        final Mesh water = new Mesh(3, 2, 1, 1);

        GpuChunk(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }

        @Override
        public void close() {
            opaque.close();
            water.close();
        }
    }

    private final World world;
    private final ExecutorService pool;
    private final int maxInFlight;
    private final Map<Long, GpuChunk> gpuChunks = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    private final Set<Long> inFlight = new HashSet<>();
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final Matrix4f model = new Matrix4f();

    public ChunkRenderer(World world) {
        this.world = world;
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        this.maxInFlight = threads * 4;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "chunk-mesh-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        world.addListener(this);
    }

    // ---- World.Listener ----

    @Override
    public void chunkAdded(Chunk chunk, boolean hadEdits) {
        dirty.add(chunk.key());
        if (hadEdits) {
            // 保存済みの変更が境界にあるかもしれないので、隣のメッシュも作り直す
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    long key = Chunk.key(chunk.cx + dx, chunk.cz + dz);
                    if (gpuChunks.containsKey(key)) {
                        dirty.add(key);
                    }
                }
            }
        }
    }

    @Override
    public void chunkRemoved(Chunk chunk) {
        long key = chunk.key();
        dirty.remove(key);
        GpuChunk gpu = gpuChunks.remove(key);
        if (gpu != null) {
            gpu.close();
        }
    }

    @Override
    public void blockChanged(int x, int y, int z, Block block) {
        int cx = Math.floorDiv(x, Chunk.SIZE);
        int cz = Math.floorDiv(z, Chunk.SIZE);
        int lx = Math.floorMod(x, Chunk.SIZE);
        int lz = Math.floorMod(z, Chunk.SIZE);
        // 面の有無とAOは隣のブロックにも影響されるので、境界に接していれば隣 (斜めも含む) も作り直す
        for (int dz = -1; dz <= 1; dz++) {
            if ((dz == -1 && lz != 0) || (dz == 1 && lz != Chunk.SIZE - 1)) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx == -1 && lx != 0) || (dx == 1 && lx != Chunk.SIZE - 1)) {
                    continue;
                }
                if (world.isChunkLoaded(cx + dx, cz + dz)) {
                    dirty.add(Chunk.key(cx + dx, cz + dz));
                }
            }
        }
    }

    // ---- 毎フレームの処理 ----

    /** 完成したメッシュをGPUに送り、作り直しが必要なチャンクのメッシュ生成を依頼する。 */
    public void update(double playerX, double playerZ) {
        Result result;
        while ((result = results.poll()) != null) {
            inFlight.remove(result.key());
            Chunk chunk = world.getChunk(Chunk.keyX(result.key()), Chunk.keyZ(result.key()));
            if (chunk == null) {
                continue; // 生成中に破棄された
            }
            GpuChunk gpu = gpuChunks.computeIfAbsent(result.key(), k -> new GpuChunk(chunk.cx, chunk.cz));
            ChunkMesher.MeshData data = result.data();
            gpu.opaque.upload(data.opaque(), data.opaqueFloats(), false);
            gpu.water.upload(data.water(), data.waterFloats(), false);
        }

        if (dirty.isEmpty() || inFlight.size() >= maxInFlight) {
            return;
        }
        int pcx = Math.floorDiv((int) Math.floor(playerX), Chunk.SIZE);
        int pcz = Math.floorDiv((int) Math.floor(playerZ), Chunk.SIZE);
        List<Long> ready = new ArrayList<>();
        for (long key : dirty) {
            if (!inFlight.contains(key) && neighborhoodLoaded(key)) {
                ready.add(key);
            }
        }
        // プレイヤーに近いチャンクから (自分で壊したブロックがすぐ反映されるように)
        ready.sort(Comparator.comparingInt(k -> {
            int dx = Chunk.keyX(k) - pcx;
            int dz = Chunk.keyZ(k) - pcz;
            return dx * dx + dz * dz;
        }));
        for (long key : ready) {
            if (inFlight.size() >= maxInFlight) {
                break;
            }
            Chunk[] neighborhood = neighborhood(key);
            dirty.remove(key);
            inFlight.add(key);
            pool.execute(() -> {
                try {
                    results.add(new Result(key, ChunkMesher.build(neighborhood)));
                } catch (Throwable t) {
                    System.err.println("メッシュ生成に失敗しました");
                    t.printStackTrace();
                }
            });
        }
    }

    private boolean neighborhoodLoaded(long key) {
        int cx = Chunk.keyX(key);
        int cz = Chunk.keyZ(key);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!world.isChunkLoaded(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Chunk[] neighborhood(long key) {
        int cx = Chunk.keyX(key);
        int cz = Chunk.keyZ(key);
        Chunk[] n = new Chunk[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                n[(dx + 1) + (dz + 1) * 3] = world.getChunk(cx + dx, cz + dz);
            }
        }
        return n;
    }

    public void renderOpaque(ShaderProgram shader, FrustumIntersection frustum) {
        for (GpuChunk gpu : gpuChunks.values()) {
            if (isVisible(gpu, frustum)) {
                shader.setUniform("uModel", model.translation(gpu.cx * Chunk.SIZE, 0, gpu.cz * Chunk.SIZE));
                gpu.opaque.draw(GL_TRIANGLES);
            }
        }
    }

    public void renderWater(ShaderProgram shader, FrustumIntersection frustum) {
        for (GpuChunk gpu : gpuChunks.values()) {
            if (gpu.water.vertexCount() > 0 && isVisible(gpu, frustum)) {
                shader.setUniform("uModel", model.translation(gpu.cx * Chunk.SIZE, 0, gpu.cz * Chunk.SIZE));
                gpu.water.draw(GL_TRIANGLES);
            }
        }
    }

    private static boolean isVisible(GpuChunk gpu, FrustumIntersection frustum) {
        float x = gpu.cx * Chunk.SIZE;
        float z = gpu.cz * Chunk.SIZE;
        return frustum.testAab(x, 0, z, x + Chunk.SIZE, Chunk.HEIGHT, z + Chunk.SIZE);
    }

    public int meshCount() {
        return gpuChunks.size();
    }

    @Override
    public void close() {
        pool.shutdownNow();
        for (GpuChunk gpu : gpuChunks.values()) {
            gpu.close();
        }
        gpuChunks.clear();
    }
}
