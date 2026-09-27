package voxelfrontier.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * GPU上の頂点データ (VAO + VBO)。頂点は float の並びで、属性ごとの要素数をコンストラクタで指定する。
 * 例: new Mesh(3, 2, 1, 1) → 位置(3) + UV(2) + レイヤー(1) + 明るさ(1)
 */
public final class Mesh implements AutoCloseable {

    private final int vao;
    private final int vbo;
    private final int floatsPerVertex;
    private int vertexCount;

    public Mesh(int... attributeSizes) {
        int total = 0;
        for (int size : attributeSizes) {
            total += size;
        }
        floatsPerVertex = total;

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        int stride = total * Float.BYTES;
        long offset = 0;
        for (int i = 0; i < attributeSizes.length; i++) {
            glVertexAttribPointer(i, attributeSizes[i], GL_FLOAT, false, stride, offset);
            glEnableVertexAttribArray(i);
            offset += (long) attributeSizes[i] * Float.BYTES;
        }
        glBindVertexArray(0);
    }

    /** 頂点データを送る。dynamic=true は毎フレーム作り直すもの (HUDなど) 用。 */
    public void upload(float[] data, int floatCount, boolean dynamic) {
        vertexCount = floatCount / floatsPerVertex;
        FloatBuffer buffer = MemoryUtil.memAllocFloat(Math.max(1, floatCount));
        try {
            buffer.put(data, 0, floatCount).flip();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, buffer, dynamic ? GL_STREAM_DRAW : GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public void upload(FloatList list, boolean dynamic) {
        upload(list.array(), list.size(), dynamic);
    }

    public void draw(int mode) {
        if (vertexCount == 0) {
            return;
        }
        glBindVertexArray(vao);
        glDrawArrays(mode, 0, vertexCount);
    }

    public int vertexCount() {
        return vertexCount;
    }

    @Override
    public void close() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
    }
}
