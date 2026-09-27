package voxelfrontier.world;

/**
 * ブロックの種類。ordinal() がそのままワールドデータ・通信・セーブで使うIDになるため、
 * 既存の並び順は変えず、新しいブロックは末尾に追加すること。
 */
public enum Block {
    AIR("空気", Kind.AIR, null, null, null),
    STONE("石", Kind.SOLID, BlockTexture.STONE),
    GRASS("草ブロック", Kind.SOLID, BlockTexture.GRASS_TOP, BlockTexture.GRASS_SIDE, BlockTexture.DIRT),
    DIRT("土", Kind.SOLID, BlockTexture.DIRT),
    COBBLESTONE("丸石", Kind.SOLID, BlockTexture.COBBLESTONE),
    PLANKS("木材", Kind.SOLID, BlockTexture.PLANKS),
    LOG("原木", Kind.SOLID, BlockTexture.LOG_TOP, BlockTexture.LOG_SIDE, BlockTexture.LOG_TOP),
    LEAVES("葉", Kind.SOLID, BlockTexture.LEAVES),
    SAND("砂", Kind.SOLID, BlockTexture.SAND),
    WATER("水", Kind.LIQUID, BlockTexture.WATER),
    BEDROCK("岩盤", Kind.SOLID, BlockTexture.BEDROCK),
    COAL_ORE("石炭鉱石", Kind.SOLID, BlockTexture.COAL_ORE),
    IRON_ORE("鉄鉱石", Kind.SOLID, BlockTexture.IRON_ORE),
    BRICKS("レンガ", Kind.SOLID, BlockTexture.BRICKS);

    private enum Kind { AIR, SOLID, LIQUID }

    private static final Block[] BY_ID = values();

    public final String displayName;
    private final Kind kind;
    private final BlockTexture top;
    private final BlockTexture side;
    private final BlockTexture bottom;

    Block(String displayName, Kind kind, BlockTexture all) {
        this(displayName, kind, all, all, all);
    }

    Block(String displayName, Kind kind, BlockTexture top, BlockTexture side, BlockTexture bottom) {
        this.displayName = displayName;
        this.kind = kind;
        this.top = top;
        this.side = side;
        this.bottom = bottom;
    }

    public byte id() {
        return (byte) ordinal();
    }

    public static Block byId(int id) {
        return isValidId(id) ? BY_ID[id] : AIR;
    }

    public static boolean isValidId(int id) {
        return id >= 0 && id < BY_ID.length;
    }

    /** プレイヤーがぶつかるか。 */
    public boolean isSolid() {
        return kind == Kind.SOLID;
    }

    /** 隣の面を完全に隠すか (隠れた面はメッシュに含めない)。 */
    public boolean isOpaque() {
        return kind == Kind.SOLID;
    }

    public boolean isLiquid() {
        return kind == Kind.LIQUID;
    }

    /** 視線で選択できるか (空気と水は素通りする)。 */
    public boolean isTargetable() {
        return kind == Kind.SOLID;
    }

    public boolean isBreakable() {
        return isTargetable() && this != BEDROCK;
    }

    /** そのマスに別のブロックを置けるか。 */
    public boolean isReplaceable() {
        return kind != Kind.SOLID;
    }

    public BlockTexture topTexture() {
        return top;
    }

    public BlockTexture sideTexture() {
        return side;
    }

    public BlockTexture bottomTexture() {
        return bottom;
    }
}
