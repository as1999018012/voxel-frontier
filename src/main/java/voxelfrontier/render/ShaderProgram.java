package voxelfrontier.render;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * 頂点シェーダー + フラグメントシェーダーをリンクしたプログラム。
 * シェーダーのソースはクラスパス上のリソース (src/main/resources) から読み込む。
 */
public final class ShaderProgram implements AutoCloseable {

    private final int id;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    public ShaderProgram(String vertPath, String fragPath) {
        int vert = compile(GL_VERTEX_SHADER, readResource(vertPath), vertPath);
        int frag = compile(GL_FRAGMENT_SHADER, readResource(fragPath), fragPath);

        id = glCreateProgram();
        glAttachShader(id, vert);
        glAttachShader(id, frag);
        glLinkProgram(id);
        glDeleteShader(vert);
        glDeleteShader(frag);
        if (glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("シェーダーのリンクに失敗しました:\n" + glGetProgramInfoLog(id));
        }
    }

    public void use() {
        glUseProgram(id);
    }

    public void setUniform(String name, Matrix4f value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(location(name), false, value.get(stack.mallocFloat(16)));
        }
    }

    public void setUniform(String name, float value) {
        glUniform1f(location(name), value);
    }

    public void setUniform(String name, float x, float y, float z) {
        glUniform3f(location(name), x, y, z);
    }

    public void setUniform(String name, float x, float y, float z, float w) {
        glUniform4f(location(name), x, y, z, w);
    }

    public void setUniform(String name, int value) {
        glUniform1i(location(name), value);
    }

    /**
     * 見つからない uniform は -1 を返す (OpenGLは -1 への設定を無視する)。
     * シェーダー内で使われていない uniform はコンパイラに削除されることがあるため、エラーにはしない。
     */
    private int location(String name) {
        return uniformLocations.computeIfAbsent(name, n -> glGetUniformLocation(id, n));
    }

    @Override
    public void close() {
        glDeleteProgram(id);
    }

    private static int compile(int type, String source, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException(name + " のコンパイルに失敗しました:\n" + log);
        }
        return shader;
    }

    private static String readResource(String path) {
        try (InputStream in = ShaderProgram.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("リソースが見つかりません: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
