package voxelfrontier.world;

/**
 * 16 x 256 x 16 ブロックの塊。ワールドはチャンク単位で生成・描画・破棄する。
 * ブロックは1バイトのID (Block.ordinal()) で保持する。
 */
public final class Chunk {

    public static final int SIZE = 16;
    public static final int HEIGHT = 256;
    public static final int VOLUME = SIZE * SIZE * HEIGHT;

    public final int cx;
    public final int cz;
    private final byte[] blocks;

    public Chunk(int cx, int cz, byte[] blocks) {
        if (blocks.length != VOLUME) {
            throw new IllegalArgumentException("ブロック配列の長さが不正です: " + blocks.length);
        }
        this.cx = cx;
        this.cz = cz;
        this.blocks = blocks;
    }

    /** チャンク内座標 → 配列の添字。x が最も速く変化する並び。 */
    public static int index(int x, int y, int z) {
        return (y * SIZE + z) * SIZE + x;
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    public long key() {
        return key(cx, cz);
    }

    public Block get(int x, int y, int z) {
        return Block.byId(blocks[index(x, y, z)]);
    }

    public void set(int x, int y, int z, Block block) {
        blocks[index(x, y, z)] = block.id();
    }

    /** メッシュ生成用に配列を直接読む。書き込みはしないこと。 */
    public byte[] rawBlocks() {
        return blocks;
    }
}
