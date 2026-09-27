package voxelfrontier.render;

import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.lwjgl.opengl.GL33C.*;

/** 描画結果をPNGで保存する。バッファを入れ替える (glfwSwapBuffers) 前に呼ぶこと。 */
public final class Screenshot {

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private Screenshot() {
    }

    public static Path save(int width, int height, Path dir) throws IOException {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try {
            glReadBuffer(GL_BACK);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            for (int y = 0; y < height; y++) {
                int row = (height - 1 - y) * width * 4; // OpenGLは下から上の順
                for (int x = 0; x < width; x++) {
                    int i = row + x * 4;
                    int rgb = (pixels.get(i) & 0xff) << 16 | (pixels.get(i + 1) & 0xff) << 8 | (pixels.get(i + 2) & 0xff);
                    image.setRGB(x, y, rgb);
                }
            }
        } finally {
            MemoryUtil.memFree(pixels);
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(LocalDateTime.now().format(NAME) + ".png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }
}
