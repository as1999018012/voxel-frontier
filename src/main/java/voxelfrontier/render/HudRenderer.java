package voxelfrontier.render;

import org.joml.Matrix4f;
import voxelfrontier.world.Block;

import static org.lwjgl.opengl.GL33C.*;

/**
 * 画面に重ねる2D表示 (照準とホットバー)。毎フレーム頂点を作り直して描く。
 * 頂点の形式: 位置(2, ピクセル) + UV(2) + レイヤー(1, 負なら単色) + 色(4)
 */
public final class HudRenderer implements AutoCloseable {

    private final ShaderProgram shader = new ShaderProgram("/shaders/hud.vert", "/shaders/hud.frag");
    private final Mesh mesh = new Mesh(2, 2, 1, 4);
    private final FloatList vertices = new FloatList(4096);
    private final Matrix4f projection = new Matrix4f();

    public void render(int width, int height, Block[] hotbar, int selected, TextureArray textures) {
        float scale = Math.max(1, Math.round(height / 720f));
        vertices.clear();

        // 照準
        float cx = width / 2f;
        float cy = height / 2f;
        float arm = 9 * scale;
        float thick = Math.max(2, scale * 2);
        rect(cx - arm, cy - thick / 2, arm * 2, thick, 1, 1, 1, 0.85f);
        rect(cx - thick / 2, cy - arm, thick, arm * 2, 1, 1, 1, 0.85f);

        // ホットバー
        float slot = 44 * scale;
        float total = slot * hotbar.length;
        float x0 = (width - total) / 2f;
        float y0 = height - slot - 10 * scale;
        rect(x0 - 3 * scale, y0 - 3 * scale, total + 6 * scale, slot + 6 * scale, 0, 0, 0, 0.45f);
        for (int i = 0; i < hotbar.length; i++) {
            float sx = x0 + i * slot;
            rect(sx + 2 * scale, y0 + 2 * scale, slot - 4 * scale, slot - 4 * scale, 0.35f, 0.35f, 0.35f, 0.55f);
            float icon = 28 * scale;
            float ix = sx + (slot - icon) / 2;
            float iy = y0 + (slot - icon) / 2;
            textured(ix, iy, icon, icon, hotbar[i].sideTexture().layer());
            if (i == selected) {
                float b = 3 * scale;
                rect(sx, y0, slot, b, 1, 1, 1, 1);
                rect(sx, y0 + slot - b, slot, b, 1, 1, 1, 1);
                rect(sx, y0, b, slot, 1, 1, 1, 1);
                rect(sx + slot - b, y0, b, slot, 1, 1, 1, 1);
            }
        }

        mesh.upload(vertices, true);
        projection.setOrtho2D(0, width, height, 0);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        shader.use();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uTextures", 0);
        textures.bind(0);
        mesh.draw(GL_TRIANGLES);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    private void rect(float x, float y, float w, float h, float r, float g, float b, float a) {
        quad(x, y, w, h, -1, r, g, b, a);
    }

    private void textured(float x, float y, float w, float h, int layer) {
        quad(x, y, w, h, layer, 1, 1, 1, 1);
    }

    private void quad(float x, float y, float w, float h, float layer, float r, float g, float b, float a) {
        float[][] corners = {{x, y, 0, 0}, {x + w, y, 1, 0}, {x + w, y + h, 1, 1}, {x, y + h, 0, 1}};
        for (int k : new int[] {0, 1, 2, 0, 2, 3}) {
            float[] c = corners[k];
            vertices.add(c[0]);
            vertices.add(c[1]);
            vertices.add(c[2]);
            vertices.add(c[3]);
            vertices.add(layer);
            vertices.add(r);
            vertices.add(g);
            vertices.add(b);
            vertices.add(a);
        }
    }

    @Override
    public void close() {
        mesh.close();
        shader.close();
    }
}
