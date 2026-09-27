package voxelfrontier.render;

import org.joml.Matrix4f;
import voxelfrontier.net.RemotePlayer;
import voxelfrontier.world.BlockTexture;

import java.util.Collection;

import static org.lwjgl.opengl.GL33C.GL_TRIANGLES;

/**
 * 他のプレイヤーを箱2つ (胴体と頭) で描く。服の色はプレイヤーIDごとに変える。
 * ブロックと同じシェーダー・頂点形式を使う。
 */
public final class PlayerRenderer implements AutoCloseable {

    private static final float NECK_Y = 1.3f;
    private static final float[][] SHIRT_COLORS = {
            {0.95f, 0.35f, 0.3f}, {0.3f, 0.55f, 0.95f}, {0.35f, 0.8f, 0.35f}, {0.95f, 0.8f, 0.25f},
            {0.75f, 0.4f, 0.9f}, {0.3f, 0.85f, 0.85f}, {0.95f, 0.55f, 0.2f}, {0.9f, 0.9f, 0.9f},
    };

    private final Mesh body = new Mesh(3, 2, 1, 1);
    private final Mesh head = new Mesh(3, 2, 1, 1);
    private final Matrix4f model = new Matrix4f();

    public PlayerRenderer() {
        FloatList v = new FloatList(256);
        int shirt = BlockTexture.PLAYER_SHIRT.layer();
        box(v, -0.3f, 0, -0.15f, 0.3f, NECK_Y, 0.15f, new int[] {shirt, shirt, shirt, shirt, shirt, shirt});
        body.upload(v, false);

        v.clear();
        int skin = BlockTexture.PLAYER_SKIN.layer();
        int face = BlockTexture.PLAYER_FACE.layer();
        // 顔は -Z 側 (プレイヤーの正面)
        box(v, -0.25f, 0, -0.25f, 0.25f, 0.5f, 0.25f, new int[] {skin, skin, skin, skin, skin, face});
        head.upload(v, false);
    }

    public void render(ShaderProgram shader, Collection<RemotePlayer> players) {
        for (RemotePlayer p : players) {
            float[] color = SHIRT_COLORS[Math.floorMod(p.id, SHIRT_COLORS.length)];
            // モデルの正面 (-Z) を、プレイヤーの向き yaw に合わせて回す
            model.translation((float) p.x, (float) p.y, (float) p.z).rotateY(-p.yaw);
            shader.setUniform("uModel", model);
            shader.setUniform("uTint", color[0], color[1], color[2]);
            body.draw(GL_TRIANGLES);

            model.translate(0, NECK_Y, 0).rotateX(-p.pitch);
            shader.setUniform("uModel", model);
            shader.setUniform("uTint", 1f, 1f, 1f);
            head.draw(GL_TRIANGLES);
        }
        shader.setUniform("uTint", 1f, 1f, 1f);
    }

    /** 直方体の6面を追加する。layers は Faces の面の順 (上, 下, 東, 西, 南, 北)。 */
    private static void box(FloatList out, float x0, float y0, float z0, float x1, float y1, float z1, int[] layers) {
        for (int face = 0; face < 6; face++) {
            int[][] corners = Faces.CORNERS[face];
            for (int k : new int[] {0, 1, 2, 0, 2, 3}) {
                int[] c = corners[k];
                out.add(c[0] == 0 ? x0 : x1);
                out.add(c[1] == 0 ? y0 : y1);
                out.add(c[2] == 0 ? z0 : z1);
                out.add(Faces.u(face, c));
                out.add(Faces.v(face, c));
                out.add(layers[face]);
                out.add(Faces.SHADE[face]);
            }
        }
    }

    @Override
    public void close() {
        body.close();
        head.close();
    }
}
