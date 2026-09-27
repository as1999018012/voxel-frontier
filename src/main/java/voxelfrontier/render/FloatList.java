package voxelfrontier.render;

import java.util.Arrays;

/** 伸びる float 配列 (頂点データの組み立て用)。 */
public final class FloatList {

    private float[] data;
    private int size;

    public FloatList(int initialCapacity) {
        data = new float[Math.max(16, initialCapacity)];
    }

    public void add(float v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = v;
    }

    public void clear() {
        size = 0;
    }

    public int size() {
        return size;
    }

    public float[] array() {
        return data;
    }
}
