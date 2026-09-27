package voxelfrontier.render;

import org.lwjgl.system.MemoryUtil;
import voxelfrontier.world.BlockTexture;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * 全ブロックのテクスチャを1つの「テクスチャ配列」(GL_TEXTURE_2D_ARRAY) にまとめたもの。
 * 1枚の大きな画像に並べる方式 (アトラス) と違い、ミップマップで隣の絵柄がにじまない。
 */
public final class TextureArray implements AutoCloseable {

    private final int id;

    public TextureArray() {
        BlockTexture[] textures = BlockTexture.values();
        int size = TextureGenerator.SIZE;
        ByteBuffer pixels = MemoryUtil.memAlloc(size * size * 4 * textures.length);
        try {
            for (BlockTexture t : textures) {
                for (int argb : TextureGenerator.generate(t)) {
                    pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
                }
            }
            pixels.flip();

            id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, id);
            glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, size, size, textures.length, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        } finally {
            MemoryUtil.memFree(pixels);
        }
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
        // 近くはドット絵らしくくっきり、遠くはミップマップでちらつきを抑える
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_LEVEL, 4);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
    }

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D_ARRAY, id);
    }

    @Override
    public void close() {
        glDeleteTextures(id);
    }
}
