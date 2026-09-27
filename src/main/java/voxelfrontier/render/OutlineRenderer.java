package voxelfrontier.render;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33C.*;

/** 視線の先のブロックを囲む枠線。 */
public final class OutlineRenderer implements AutoCloseable {

    private static final float GROW = 0.002f; // ブロックの面と重なってちらつかないよう、わずかに大きく描く

    private final ShaderProgram shader = new ShaderProgram("/shaders/line.vert", "/shaders/line.frag");
    private final Mesh mesh = new Mesh(3);
    private final Matrix4f model = new Matrix4f();

    public OutlineRenderer() {
        FloatList v = new FloatList(72);
        float[][] edges = {
                // 下の四角
                {0, 0, 0, 1, 0, 0}, {1, 0, 0, 1, 0, 1}, {1, 0, 1, 0, 0, 1}, {0, 0, 1, 0, 0, 0},
                // 上の四角
                {0, 1, 0, 1, 1, 0}, {1, 1, 0, 1, 1, 1}, {1, 1, 1, 0, 1, 1}, {0, 1, 1, 0, 1, 0},
                // 縦の辺
                {0, 0, 0, 0, 1, 0}, {1, 0, 0, 1, 1, 0}, {1, 0, 1, 1, 1, 1}, {0, 0, 1, 0, 1, 1},
        };
        for (float[] e : edges) {
            for (float f : e) {
                v.add(f);
            }
        }
        mesh.upload(v, false);
    }

    public void render(Matrix4f projection, Matrix4f view, int x, int y, int z) {
        model.translation(x - GROW, y - GROW, z - GROW).scale(1 + 2 * GROW);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uModel", model);
        shader.setUniform("uColor", 0f, 0f, 0f, 0.7f);
        mesh.draw(GL_LINES);
        glDisable(GL_BLEND);
    }

    @Override
    public void close() {
        mesh.close();
        shader.close();
    }
}
