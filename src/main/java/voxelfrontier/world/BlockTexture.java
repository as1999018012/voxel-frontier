package voxelfrontier.world;

/**
 * テクスチャ配列のレイヤー。並び順がそのままGPU上のレイヤー番号になる。
 * 絵柄は render.TextureGenerator がプログラムで生成する。
 */
public enum BlockTexture {
    STONE,
    DIRT,
    GRASS_TOP,
    GRASS_SIDE,
    COBBLESTONE,
    PLANKS,
    LOG_SIDE,
    LOG_TOP,
    LEAVES,
    SAND,
    WATER,
    BEDROCK,
    COAL_ORE,
    IRON_ORE,
    BRICKS,
    // 他プレイヤーの見た目用
    PLAYER_SKIN,
    PLAYER_FACE,
    PLAYER_SHIRT;

    public int layer() {
        return ordinal();
    }
}
