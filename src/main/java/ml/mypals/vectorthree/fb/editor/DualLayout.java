package ml.mypals.vectorthree.fb.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.CustomImGuiWindowerSdl;
import com.moulberry.flashback.editor.ui.ReplayUI;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiStyleVar;
import imgui.moulberry90.flag.ImGuiWindowFlags;
import imgui.moulberry90.type.ImInt;
import ml.mypals.vectorthree.core.Mod;
import ml.mypals.vectorthree.mc.window.SecondWindow;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDL_Event;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The editor keeps one ImGui context; with two windows its canvas holds the main window and the second window, side by
 * side or one above the other like the displays, and each window shows its own part of the canvas. The window layout
 * of each mode is remembered in {@code config/vector3/dual_layout.json} and applied when the mode changes.
 */
public final class DualLayout {
    public static final float GAP = 256;
    private static final long IDLE_NANOS = 250_000_000L;
    private static final long SNAPSHOT_NANOS = 5_000_000_000L;
    private static final int NO_REPLAY_FRAMES = 10;
    private static final String SECOND_DOCK = "Vector3SecondDock";
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("vector3").resolve("dual_layout.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 0 outside the renderer, 1 while drawing the main window, 2 while drawing the second window. */
    public static int pass;
    public static boolean routing;

    private static boolean frameActive;
    private static boolean wanted;
    private static boolean vertical;
    private static boolean mainFocused = true;
    private static boolean secondFocused;
    private static float mainW, mainH, secW, secH, secScaleX = 1, secScaleY = 1;
    private static long lastOverlayNanos;
    private static int noReplayFrames;

    private static String singleLayout;
    private static String dualLayout;
    private static boolean dualApplied;
    private static boolean needDefault;
    private static long lastSnapshot;
    private static boolean restoreSingle;

    static {
        load();
    }

    private DualLayout() {}

    public static boolean frameActive() {
        return frameActive;
    }

    public static float mainW() { return mainW; }
    public static float mainH() { return mainH; }
    public static float secW() { return secW; }
    public static float secH() { return secH; }
    public static float secScaleX() { return secScaleX; }
    public static float secScaleY() { return secScaleY; }
    public static float secondX() { return vertical ? 0 : mainW + GAP; }
    public static float secondY() { return vertical ? mainH + GAP : 0; }
    public static float canvasW() { return vertical ? Math.max(mainW, secW) : mainW + GAP + secW; }
    public static float canvasH() { return vertical ? mainH + GAP + secH : Math.max(mainH, secH); }

    public static void setMain(float w, float h) {
        mainW = w;
        mainH = h;
    }

    /** Start of ReplayUI.drawOverlay. */
    public static void beginFrame() {
        lastOverlayNanos = System.nanoTime();
        watchReplay();
        boolean open = SecondWindow.isOpen();
        int lw = SecondWindow.logicalWidth();
        int lh = SecondWindow.logicalHeight();
        int pw = SecondWindow.pixelWidth();
        int ph = SecondWindow.pixelHeight();
        boolean sized = lw > 0 && lh > 0 && pw > 0 && ph > 0;
        wanted = open && sized;
        if (wanted) {
            secW = lw;
            secH = lh;
            // a little over a pixel count keeps the renderer's int(size * scale) from rounding down
            secScaleX = (pw + 0.25f) / lw;
            secScaleY = (ph + 0.25f) / lh;
            vertical = SecondWindow.stackedVertically();
        }
        // Not tied to ReplayUI.isActive(), which only updates inside the frame: the frame that reactivates the editor
        // must already use the two-window canvas, or the panels docked in the second window get thrown out.
        frameActive = wanted;
    }

    /** End of ReplayUI.drawOverlay. */
    public static void endFrame() {
        if (!SecondWindow.isOpen()) return;
        SecondWindow.drawFrame(frameActive && ReplayUI.isActive() ? SecondWindow.imguiTarget() : null);
    }

    /** Runs every frame: keeps the second window alive while the editor is not drawing, and closes it with the replay. */
    public static void idleFrame() {
        watchReplay();
        if (SecondWindow.isOpen() && System.nanoTime() - lastOverlayNanos > IDLE_NANOS) SecondWindow.drawFrame(null);
    }

    private static void watchReplay() {
        if (!SecondWindow.isOpen() || Flashback.getReplayServer() != null) {
            noReplayFrames = 0;
            return;
        }
        if (++noReplayFrames > NO_REPLAY_FRAMES) SecondWindow.close();
    }

    /** Start of ReplayUI.drawOverlayInternal, before the ImGui frame: swaps in the window layout of the current mode. */
    public static void applyLayout() {
        if (restoreSingle) {
            restoreSingle = false;
            if (singleLayout != null) ImGui.loadIniSettingsFromMemory(singleLayout);
            dualApplied = false;
            save(false);
        }
        if (wanted == dualApplied) {
            snapshot();
            return;
        }
        String current = ImGui.saveIniSettingsToMemory();
        if (wanted) {
            singleLayout = current;
            needDefault = dualLayout == null;
            if (dualLayout != null) ImGui.loadIniSettingsFromMemory(dualLayout);
        } else {
            dualLayout = current;
            needDefault = false;
            if (singleLayout != null) ImGui.loadIniSettingsFromMemory(singleLayout);
        }
        dualApplied = wanted;
        save(dualApplied);
    }

    // The two-window layout is kept up to date while it is in use, so closing the game never loses changes.
    private static void snapshot() {
        if (!dualApplied || System.nanoTime() - lastSnapshot < SNAPSHOT_NANOS) return;
        lastSnapshot = System.nanoTime();
        String current = ImGui.saveIniSettingsToMemory();
        if (current.equals(dualLayout)) return;
        dualLayout = current;
        save(true);
    }

    /** Draws the second window's dockspace, after the main one was limited to the main window. */
    public static void secondDock(float menuBarHeight) {
        // beside the main window the menu bar strip continues across, below it the second window is whole
        float top = vertical ? 0 : menuBarHeight;
        float x = secondX();
        float y = secondY() + top;
        float w = secW;
        float h = Math.max(1, secH - top);
        ImGui.getBackgroundDrawList().addRectFilled(x, secondY(), x + w, secondY() + secH, 0xFF1E1E1E);

        int dock = ImGui.getID(SECOND_DOCK);
        if (needDefault || imgui.moulberry90.internal.ImGui.dockBuilderGetNode(dock) == null) {
            needDefault = false;
            defaultLayout(dock, w, h);
        }

        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(w, h);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus
                | ImGuiWindowFlags.NoBackground | ImGuiWindowFlags.NoSavedSettings;
        if (ImGui.begin("Vector3SecondDockHost", flags)) ImGui.dockSpace(dock, 0, 0, 0);
        ImGui.end();
        ImGui.popStyleVar(3);
    }

    // The timeline takes the whole left side of the second window, the properties the right side.
    private static void defaultLayout(int dock, float w, float h) {
        imgui.moulberry90.internal.ImGui.dockBuilderRemoveNode(dock);
        imgui.moulberry90.internal.ImGui.dockBuilderAddNode(dock, 1 << 10);
        imgui.moulberry90.internal.ImGui.dockBuilderSetNodeSize(dock, w, h);
        ImInt right = new ImInt();
        ImInt left = new ImInt();
        imgui.moulberry90.internal.ImGui.dockBuilderSplitNode(dock, 1, 0.3f, right, left);
        imgui.moulberry90.internal.ImGui.dockBuilderDockWindow("###Timeline", left.get());
        imgui.moulberry90.internal.ImGui.dockBuilderDockWindow("###vector3_properties", right.get());
        imgui.moulberry90.internal.ImGui.dockBuilderDockWindow("###vector3_shape_manager", right.get());
        imgui.moulberry90.internal.ImGui.dockBuilderFinish(dock);
    }

    private static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("single")) singleLayout = json.get("single").getAsString();
            if (json.has("dual")) dualLayout = json.get("dual").getAsString();
            restoreSingle = json.has("dualActive") && json.get("dualActive").getAsBoolean() && singleLayout != null;
        } catch (IOException | RuntimeException exception) {
            Mod.LOGGER.warn("Could not read {}, using the default layout", FILE, exception);
        }
    }

    private static void save(boolean dualActive) {
        JsonObject json = new JsonObject();
        if (singleLayout != null) json.addProperty("single", singleLayout);
        if (dualLayout != null) json.addProperty("dual", dualLayout);
        json.addProperty("dualActive", dualActive);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            Mod.LOGGER.warn("Could not save {}", FILE, exception);
        }
    }

    /** The second window's events, handed to Flashback's input as if they happened where it sits on the canvas. */
    public static void route(SDL_Event event) {
        if (!SecondWindow.isOpen() || !ReplayUI.isActive()) return;
        if (!(ReplayUI.imguiWindower instanceof CustomImGuiWindowerSdl windower)) return;
        int type = event.type();
        float offsetX = secondX();
        float offsetY = secondY();
        routing = true;
        try {
            if (type == SDLEvents.SDL_EVENT_MOUSE_MOTION) {
                event.motion().x(event.motion().x() + offsetX);
                event.motion().y(event.motion().y() + offsetY);
                windower.processEvent(event);
            } else if (type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN || type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP) {
                // Flashback takes the pointer position from motion events only
                try (SDL_Event move = SDL_Event.calloc()) {
                    move.type(SDLEvents.SDL_EVENT_MOUSE_MOTION);
                    move.motion().x(event.button().x() + offsetX);
                    move.motion().y(event.button().y() + offsetY);
                    windower.processEvent(move);
                }
                windower.processEvent(event);
            } else {
                windower.processEvent(event);
            }
        } finally {
            routing = false;
        }
        Minecraft.getInstance().getFramerateLimitTracker().onInputReceived();
    }

    /** Focus of either window counts as the editor having focus. */
    public static boolean focus(boolean focused) {
        if (routing) secondFocused = focused;
        else mainFocused = focused;
        return mainFocused || secondFocused;
    }

    /** SDL delivers text input per window, so the second one has to be told when a text field is active. */
    public static void textInput(boolean wanted) {
        long handle = SecondWindow.handle();
        if (handle == 0 || !frameActive) return;
        if (wanted) SDLKeyboard.SDL_StartTextInput(handle);
        else SDLKeyboard.SDL_StopTextInput(handle);
    }
}
