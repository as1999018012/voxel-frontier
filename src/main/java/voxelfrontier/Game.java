package voxelfrontier;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import voxelfrontier.entity.Player;
import voxelfrontier.net.ClientSession;
import voxelfrontier.net.GameClient;
import voxelfrontier.net.HostSession;
import voxelfrontier.net.LanDiscovery;
import voxelfrontier.net.RemotePlayer;
import voxelfrontier.net.Session;
import voxelfrontier.render.ChunkRenderer;
import voxelfrontier.render.HudRenderer;
import voxelfrontier.render.OutlineRenderer;
import voxelfrontier.render.PlayerRenderer;
import voxelfrontier.render.Screenshot;
import voxelfrontier.render.ShaderProgram;
import voxelfrontier.render.TextureArray;
import voxelfrontier.world.Block;
import voxelfrontier.world.Chunk;
import voxelfrontier.world.ChunkStreamer;
import voxelfrontier.world.Raycast;
import voxelfrontier.world.World;
import voxelfrontier.world.WorldStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * ゲーム本体。ウィンドウ・入力・更新・描画のループを回す。
 *
 * 操作:
 *   クリック            マウスを捕捉して操作開始
 *   マウス              視点移動
 *   W/A/S/D             移動          Space  ジャンプ (飛行中は上昇)
 *   左Ctrl              ダッシュ      左Shift 飛行中の下降
 *   F                   飛行モード切替
 *   左クリック          ブロックを壊す
 *   右クリック          ブロックを置く
 *   1〜9 / ホイール     置くブロックを選ぶ
 *   F2                  スクリーンショット (screenshots フォルダに保存)
 *   F6                  1人プレイ中のワールドをLANに公開する (ホストになる)
 *   ESC                 マウス捕捉を解除 (解除中にもう一度押すと終了)
 */
public final class Game implements AutoCloseable {

    /** 起動時に続行できない問題 (接続失敗など)。 */
    public static final class StartupException extends Exception {
        StartupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final String TITLE = "Voxel Frontier";
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final float FOV_DEGREES = 70.0f;
    private static final float MOUSE_SENSITIVITY = 0.0025f;
    private static final double AUTOSAVE_INTERVAL = 60.0;
    private static final double FIRST_REPEAT_DELAY = 0.3;
    private static final double REPEAT_INTERVAL = 0.2;
    private static final int MAX_FPS = 240;

    private static final Block[] HOTBAR = {
            Block.GRASS, Block.DIRT, Block.STONE, Block.COBBLESTONE, Block.PLANKS,
            Block.LOG, Block.LEAVES, Block.SAND, Block.BRICKS,
    };

    private final GameOptions options;
    private final World world;
    private final Player player;
    private final Session session;
    private final Path saveDir;

    private long window = NULL;
    private int framebufferWidth;
    private int framebufferHeight;

    // 入力 (コールバックで書き込み、ループで読む)
    private boolean mouseCaptured;
    private boolean firstMouse = true;
    private double lastMouseX;
    private double lastMouseY;
    private double mouseDeltaX;
    private double mouseDeltaY;
    private boolean leftDown;
    private boolean rightDown;
    private boolean leftClicked;
    private boolean rightClicked;
    private double leftRepeat;
    private double rightRepeat;
    private int selectedSlot;

    // 描画
    private ChunkStreamer streamer;
    private ChunkRenderer chunkRenderer;
    private TextureArray textures;
    private ShaderProgram blockShader;
    private HudRenderer hud;
    private OutlineRenderer outline;
    private PlayerRenderer playerRenderer;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f identity = new Matrix4f();
    private final Matrix4f projView = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();

    private Raycast.Hit target;
    private String exitMessage;
    private boolean screenshotRequested;

    private Game(GameOptions options, World world, Player player, Session session, Path saveDir) {
        this.options = options;
        this.world = world;
        this.player = player;
        this.session = session;
        this.saveDir = saveDir;
    }

    // ================================================================
    // 起動
    // ================================================================

    public static Game create(GameOptions o) throws StartupException {
        Game game = o.mode() == GameOptions.Mode.JOIN ? createClient(o) : createHost(o);
        try {
            game.initWindow();
            game.initGraphics();
        } catch (RuntimeException e) {
            game.close();
            throw e;
        }
        return game;
    }

    private static Game createHost(GameOptions o) throws StartupException {
        Path dir = Path.of("saves", o.worldName());
        Optional<WorldStorage.Level> level;
        try {
            level = WorldStorage.loadLevel(dir);
        } catch (IOException | RuntimeException e) {
            throw new StartupException("セーブデータを読めませんでした (" + dir.toAbsolutePath() + "): " + e.getMessage(), e);
        }

        long seed = level.map(WorldStorage.Level::seed)
                .orElseGet(() -> o.seed() != null ? o.seed() : new Random().nextLong());
        World world = new World(seed);
        Player player;
        if (level.isPresent()) {
            world.setTime(level.get().time());
            try {
                WorldStorage.loadEdits(dir, world);
            } catch (IOException e) {
                throw new StartupException("変更ブロックを読めませんでした: " + e.getMessage(), e);
            }
            System.out.println("ワールド '" + o.worldName() + "' を読み込みました (シード " + seed + ", 変更 "
                    + world.editCount() + " ブロック)");
        } else {
            System.out.println("新しいワールド '" + o.worldName() + "' を作成します (シード " + seed + ")");
        }

        WorldStorage.PlayerState saved = level.map(WorldStorage.Level::player).orElse(null);
        if (saved != null) {
            player = new Player(saved.x(), saved.y(), saved.z());
            player.yaw = saved.yaw();
            player.pitch = saved.pitch();
            player.flying = saved.flying();
        } else {
            int[] spawn = world.findSpawn();
            player = new Player(spawn[0] + 0.5, spawn[1], spawn[2] + 0.5);
        }

        HostSession session = new HostSession(world, o.playerName(), o.port(), o.upnp());
        if (o.mode() == GameOptions.Mode.HOST) {
            session.openToLan();
        }
        return new Game(o, world, player, session, dir);
    }

    private static Game createClient(GameOptions o) throws StartupException {
        String host;
        int port = o.port();
        try {
            if (o.joinAddress() == null) {
                System.out.println("LAN内のホストを探しています (最大5秒)...");
                LanDiscovery.FoundHost found = LanDiscovery.find(5000).orElseThrow(() -> new IOException(
                        "LAN内にホストが見つかりませんでした。--join <アドレス> で直接指定してください"));
                host = found.address().getHostAddress();
                port = found.port();
                System.out.println(found.hostName() + " のワールドが見つかりました (" + host + ":" + port + ")");
            } else {
                String addr = o.joinAddress();
                int colon = addr.lastIndexOf(':');
                if (colon > 0 && addr.indexOf(':') == colon) { // "host:port" (IPv6 アドレスは対象外)
                    host = addr.substring(0, colon);
                    port = Integer.parseInt(addr.substring(colon + 1));
                } else {
                    host = addr;
                }
            }
            System.out.println(host + ":" + port + " に接続しています...");
            GameClient client = GameClient.connect(host, port, o.playerName(), 10_000);
            World world = new World(client.welcome().seed());
            client.applyWelcome(world);
            Player player = new Player(client.welcome().spawnX(), client.welcome().spawnY(), client.welcome().spawnZ());
            player.yaw = client.welcome().spawnYaw();
            System.out.println("参加しました (プレイヤーID " + client.playerId() + ")");
            return new Game(o, world, player, new ClientSession(client, world, host + ":" + port), null);
        } catch (IOException | NumberFormatException e) {
            throw new StartupException(e.getMessage(), e);
        }
    }

    private void initWindow() {
        System.out.println("LWJGL " + Version.getVersion());
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW の初期化に失敗しました");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(WIDTH, HEIGHT, TITLE, NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("ウィンドウの作成に失敗しました (OpenGL 3.3 非対応の可能性)");
        }

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> onKey(key, action));
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> onMouseButton(button, action));
        glfwSetCursorPosCallback(window, (win, x, y) -> onCursorMove(x, y));
        glfwSetScrollCallback(window, (win, dx, dy) -> {
            if (dy != 0) {
                selectedSlot = Math.floorMod(selectedSlot - (int) Math.signum(dy), HOTBAR.length);
            }
        });
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            framebufferWidth = w;
            framebufferHeight = h;
        });

        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        framebufferWidth = w[0];
        framebufferHeight = h[0];

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // VSync
        glfwShowWindow(window);
    }

    private void initGraphics() {
        GL.createCapabilities();
        System.out.println("OpenGL " + glGetString(GL_VERSION) + " / " + glGetString(GL_RENDERER));

        textures = new TextureArray();
        blockShader = new ShaderProgram("/shaders/block.vert", "/shaders/block.frag");
        hud = new HudRenderer();
        outline = new OutlineRenderer();
        playerRenderer = new PlayerRenderer();
        chunkRenderer = new ChunkRenderer(world);
        streamer = new ChunkStreamer(world);
    }

    // ================================================================
    // 入力
    // ================================================================

    private void onKey(int key, int action) {
        if (action != GLFW_PRESS) {
            return;
        }
        switch (key) {
            case GLFW_KEY_ESCAPE -> {
                if (mouseCaptured) {
                    setMouseCaptured(false);
                } else {
                    glfwSetWindowShouldClose(window, true);
                }
            }
            case GLFW_KEY_F -> {
                player.flying = !player.flying;
                player.vy = 0;
            }
            case GLFW_KEY_F2 -> screenshotRequested = true;
            case GLFW_KEY_F6 -> session.openToLan();
            default -> {
                if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
                    selectedSlot = key - GLFW_KEY_1;
                }
            }
        }
    }

    private void onMouseButton(int button, int action) {
        if (!mouseCaptured) {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                setMouseCaptured(true); // このクリックはブロック操作に使わない
            }
            return;
        }
        boolean pressed = action == GLFW_PRESS;
        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            leftDown = pressed;
            leftClicked |= pressed;
        } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
            rightDown = pressed;
            rightClicked |= pressed;
        }
    }

    private void onCursorMove(double x, double y) {
        if (!mouseCaptured) {
            return;
        }
        if (firstMouse) { // 捕捉直後の大きなジャンプを無視する
            firstMouse = false;
        } else {
            mouseDeltaX += x - lastMouseX;
            mouseDeltaY += y - lastMouseY;
        }
        lastMouseX = x;
        lastMouseY = y;
    }

    private void setMouseCaptured(boolean captured) {
        mouseCaptured = captured;
        firstMouse = true;
        leftDown = rightDown = leftClicked = rightClicked = false;
        glfwSetInputMode(window, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, captured ? GLFW_TRUE : GLFW_FALSE);
        }
    }

    private boolean isDown(int key) {
        return glfwGetKey(window, key) == GLFW_PRESS;
    }

    private float axis(int positive, int negative) {
        return (isDown(positive) ? 1f : 0f) - (isDown(negative) ? 1f : 0f);
    }

    // ================================================================
    // ループ
    // ================================================================

    public void run() {
        glClearColor(0.53f, 0.81f, 0.92f, 1.0f);
        double lastTime = glfwGetTime();
        double titleTimer = 0;
        double saveTimer = 0;
        int frames = 0;

        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            double dt = Math.min(now - lastTime, 0.1); // 止まっていた後に一気に動かないよう上限を付ける
            lastTime = now;

            glfwPollEvents();
            update(dt);
            render();
            if (options.screenshotAfter() > 0 && now >= options.screenshotAfter()) {
                screenshotRequested = true;
                glfwSetWindowShouldClose(window, true); // 動作確認用: 撮影したら終了する
            }
            if (screenshotRequested) {
                screenshotRequested = false;
                takeScreenshot();
            }
            glfwSwapBuffers(window);
            limitFrameRate(now);

            String reason = session.disconnectReason();
            if (reason != null) {
                exitMessage = reason;
                glfwSetWindowShouldClose(window, true);
            }

            frames++;
            titleTimer += dt;
            if (titleTimer >= 0.5) {
                updateTitle((int) Math.round(frames / titleTimer));
                frames = 0;
                titleTimer = 0;
            }
            saveTimer += dt;
            if (saveTimer >= AUTOSAVE_INTERVAL) {
                saveTimer = 0;
                save();
            }
        }
        if (exitMessage != null) {
            System.out.println(exitMessage);
        }
    }

    private void update(double dt) {
        world.advanceTime(dt);

        if (mouseCaptured) {
            player.rotate((float) mouseDeltaX * MOUSE_SENSITIVITY, (float) mouseDeltaY * MOUSE_SENSITIVITY);
        }
        mouseDeltaX = 0;
        mouseDeltaY = 0;

        Player.Input input = mouseCaptured
                ? new Player.Input(axis(GLFW_KEY_W, GLFW_KEY_S), axis(GLFW_KEY_D, GLFW_KEY_A),
                isDown(GLFW_KEY_SPACE), isDown(GLFW_KEY_LEFT_SHIFT), isDown(GLFW_KEY_LEFT_CONTROL))
                : Player.Input.NONE;
        player.update(dt, input, world);

        session.tick(dt, player);
        for (RemotePlayer rp : session.remotePlayers()) {
            rp.interpolate(dt);
        }

        int pcx = Math.floorDiv((int) Math.floor(player.x), Chunk.SIZE);
        int pcz = Math.floorDiv((int) Math.floor(player.z), Chunk.SIZE);
        // 描画する範囲 + 1 まで読み込む (端のチャンクの面とAOを決めるのに隣が必要なため)
        streamer.update(pcx, pcz, options.renderDistance() + 1);
        chunkRenderer.update(player.x, player.z);

        double[] dir = player.lookDirection();
        target = Raycast.cast(world, player.x, player.eyeY(), player.z, dir[0], dir[1], dir[2], 5.0);
        handleBlockInteraction(dt);
    }

    private void handleBlockInteraction(double dt) {
        leftRepeat -= dt;
        rightRepeat -= dt;
        boolean doBreak = leftClicked || (leftDown && leftRepeat <= 0);
        boolean doPlace = rightClicked || (rightDown && rightRepeat <= 0);
        if (leftClicked) {
            leftRepeat = FIRST_REPEAT_DELAY;
        } else if (doBreak) {
            leftRepeat = REPEAT_INTERVAL;
        }
        if (rightClicked) {
            rightRepeat = FIRST_REPEAT_DELAY;
        } else if (doPlace) {
            rightRepeat = REPEAT_INTERVAL;
        }
        leftClicked = false;
        rightClicked = false;

        if (target == null) {
            return;
        }
        if (doBreak && target.block().isBreakable()) {
            session.changeBlock(target.x(), target.y(), target.z(), Block.AIR);
        } else if (doPlace) {
            int x = target.x() + target.nx();
            int y = target.y() + target.ny();
            int z = target.z() + target.nz();
            if (canPlaceAt(x, y, z)) {
                session.changeBlock(x, y, z, HOTBAR[selectedSlot]);
            }
        }
    }

    private boolean canPlaceAt(int x, int y, int z) {
        if (y < 1 || y >= Chunk.HEIGHT || !world.getBlock(x, y, z).isReplaceable()) {
            return false;
        }
        if (player.intersectsBlock(x, y, z)) {
            return false;
        }
        for (RemotePlayer rp : session.remotePlayers()) {
            double half = Player.WIDTH / 2;
            if (rp.x + half > x && rp.x - half < x + 1 && rp.y + Player.HEIGHT > y && rp.y < y + 1
                    && rp.z + half > z && rp.z - half < z + 1) {
                return false;
            }
        }
        return true;
    }

    // ================================================================
    // 描画
    // ================================================================

    private void render() {
        if (framebufferWidth == 0 || framebufferHeight == 0) {
            return; // 最小化中
        }
        glViewport(0, 0, framebufferWidth, framebufferHeight);

        // 昼夜: phase 0 = 日の出、0.25 = 正午、0.75 = 真夜中
        double phase = (world.time() / World.DAY_LENGTH) % 1.0;
        float sun = (float) Math.sin(phase * Math.PI * 2);
        float day = Math.clamp((sun + 0.2f) / 0.5f, 0f, 1f);
        float daylight = 0.18f + 0.82f * day;
        float skyR = lerp(0.02f, 0.53f, day);
        float skyG = lerp(0.03f, 0.81f, day);
        float skyB = lerp(0.08f, 0.92f, day);

        float fogEnd = options.renderDistance() * Chunk.SIZE;
        float fogStart = fogEnd * 0.6f;
        float fogR = skyR;
        float fogG = skyG;
        float fogB = skyB;
        boolean underwater = world.getBlock((int) Math.floor(player.x), (int) Math.floor(player.eyeY()),
                (int) Math.floor(player.z)).isLiquid();
        if (underwater) {
            fogR = 0.1f * daylight;
            fogG = 0.25f * daylight;
            fogB = 0.55f * daylight;
            fogStart = 0;
            fogEnd = 24;
        }

        glClearColor(fogR, fogG, fogB, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        float aspect = (float) framebufferWidth / framebufferHeight;
        projection.setPerspective((float) Math.toRadians(FOV_DEGREES), aspect, 0.05f,
                options.renderDistance() * Chunk.SIZE + 64f);
        player.viewMatrix(view);
        frustum.set(projection.mul(view, projView));

        blockShader.use();
        blockShader.setUniform("uProjection", projection);
        blockShader.setUniform("uView", view);
        blockShader.setUniform("uModel", identity);
        blockShader.setUniform("uTextures", 0);
        blockShader.setUniform("uDaylight", daylight);
        blockShader.setUniform("uFogColor", fogR, fogG, fogB);
        blockShader.setUniform("uFogStart", fogStart);
        blockShader.setUniform("uFogEnd", fogEnd);
        blockShader.setUniform("uAlpha", 1f);
        blockShader.setUniform("uTint", 1f, 1f, 1f);
        textures.bind(0);

        chunkRenderer.renderOpaque(blockShader, frustum);
        playerRenderer.render(blockShader, session.remotePlayers());

        // 水は不透明なものを描き終えてから、半透明で重ねる
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE); // 水中から水面を見上げたときも描く
        blockShader.setUniform("uAlpha", 0.72f);
        chunkRenderer.renderWater(blockShader, frustum);
        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);

        if (target != null && mouseCaptured) {
            outline.render(projection, view, target.x(), target.y(), target.z());
        }
        hud.render(framebufferWidth, framebufferHeight, HOTBAR, selectedSlot, textures);
    }

    private void updateTitle(int fps) {
        double phase = (world.time() / World.DAY_LENGTH) % 1.0;
        double hours = (phase * 24 + 6) % 24; // phase 0 を朝6時とする
        String title = String.format("%s | %d FPS | XYZ %.1f / %.1f / %.1f | %02d:%02d | %s%s | %s%s",
                TITLE, fps, player.x, player.y, player.z,
                (int) hours, (int) ((hours % 1) * 60),
                HOTBAR[selectedSlot].displayName,
                player.flying ? " | 飛行" : "",
                session.status(),
                mouseCaptured ? "" : " | クリックで操作開始");
        glfwSetWindowTitle(window, title);
    }

    private void takeScreenshot() {
        try {
            Path file = Screenshot.save(framebufferWidth, framebufferHeight, Path.of("screenshots"));
            System.out.println("スクリーンショットを保存しました: " + file.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("スクリーンショットを保存できませんでした: " + e.getMessage());
        }
    }

    /**
     * VSync がドライバ設定などで無効になっていると数千FPSで回り、GPUを無駄に使うので上限を設ける。
     */
    private static void limitFrameRate(double frameStart) {
        double remaining = 1.0 / MAX_FPS - (glfwGetTime() - frameStart);
        if (remaining > 0.001) {
            try {
                Thread.sleep((long) (remaining * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ================================================================
    // 終了
    // ================================================================

    private void save() {
        if (saveDir == null || !session.ownsWorld()) {
            return;
        }
        try {
            WorldStorage.save(saveDir, world, player);
        } catch (IOException e) {
            System.err.println("ワールドを保存できませんでした: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        save();
        if (saveDir != null && session.ownsWorld()) {
            System.out.println("ワールドを保存しました: " + saveDir.toAbsolutePath());
        }
        session.close();
        if (streamer != null) {
            streamer.close();
        }
        if (chunkRenderer != null) {
            chunkRenderer.close();
        }
        if (playerRenderer != null) {
            playerRenderer.close();
        }
        if (outline != null) {
            outline.close();
        }
        if (hud != null) {
            hud.close();
        }
        if (blockShader != null) {
            blockShader.close();
        }
        if (textures != null) {
            textures.close();
        }
        if (window != NULL) {
            glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
            window = NULL;
        }
        glfwTerminate();
        GLFWErrorCallback callback = glfwSetErrorCallback(null);
        if (callback != null) {
            callback.free();
        }
    }
}
