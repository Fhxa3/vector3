package ml.mypals.vectorthree.fb.editor;

import com.moulberry.flashback.editor.ui.CustomImGuiWindowerSdl;
import com.moulberry.flashback.editor.ui.ReplayUI;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiStyleVar;
import imgui.moulberry90.flag.ImGuiWindowFlags;
import ml.mypals.vectorthree.mc.window.SecondWindow;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDL_Event;

/**
 * The editor keeps one ImGui context; with two windows its canvas is the main window, a gap, then the second window,
 * side by side. Each window shows its own part of the canvas.
 */
public final class DualLayout {
    public static final float GAP = 256;
    private static final long IDLE_NANOS = 250_000_000L;
    private static final String[] SECOND_WINDOWS = {"###Timeline", "###vector3_properties", "###vector3_shape_manager"};

    /** 0 outside the renderer, 1 while drawing the main window, 2 while drawing the second window. */
    public static int pass;
    public static boolean routing;

    private static boolean frameActive;
    private static boolean mainFocused = true;
    private static boolean secondFocused;
    private static float mainW, mainH, secW, secH, secScaleX = 1, secScaleY = 1;
    private static int lastMainDock;
    private static boolean wasActive;
    private static long lastOverlayNanos;

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
    public static float secondX() { return mainW + GAP; }
    public static float canvasW() { return mainW + GAP + secW; }
    public static float canvasH() { return Math.max(mainH, secH); }

    public static void setMain(float w, float h) {
        mainW = w;
        mainH = h;
    }

    /** Start of ReplayUI.drawOverlay. */
    public static void beginFrame() {
        lastOverlayNanos = System.nanoTime();
        boolean active = SecondWindow.isOpen() && ReplayUI.isActive();
        int lw = SecondWindow.logicalWidth();
        int lh = SecondWindow.logicalHeight();
        int pw = SecondWindow.pixelWidth();
        int ph = SecondWindow.pixelHeight();
        if (lw <= 0 || lh <= 0 || pw <= 0 || ph <= 0) active = false;
        if (active) {
            secW = lw;
            secH = lh;
            // a little over a pixel count keeps the renderer's int(size * scale) from rounding down
            secScaleX = (pw + 0.25f) / lw;
            secScaleY = (ph + 0.25f) / lh;
        }
        frameActive = active;
        if (wasActive && !active) restoreWindows();
        wasActive = active;
    }

    /** End of ReplayUI.drawOverlay. */
    public static void endFrame() {
        if (!SecondWindow.isOpen()) return;
        SecondWindow.drawFrame(frameActive ? SecondWindow.imguiTarget() : null);
    }

    /** Keeps the second window alive while the editor is not drawing (no replay open). */
    public static void idleFrame() {
        if (SecondWindow.isOpen() && System.nanoTime() - lastOverlayNanos > IDLE_NANOS) SecondWindow.drawFrame(null);
    }

    /** Replaces the main dockspace so it only fills the main window, and adds one for the second window. */
    public static void secondDock(int mainDock, float top) {
        lastMainDock = mainDock;
        float x = secondX();
        float w = secW;
        float h = Math.max(1, secH - top);
        ImGui.getBackgroundDrawList().addRectFilled(x, 0, x + w, secH, 0xFF1E1E1E);

        int dock = ImGui.getID("Vector3SecondDock");
        if (imgui.moulberry90.internal.ImGui.dockBuilderGetNode(dock) == null) {
            imgui.moulberry90.internal.ImGui.dockBuilderAddNode(dock, 1 << 10);
            imgui.moulberry90.internal.ImGui.dockBuilderSetNodeSize(dock, w, h);
            for (String name : SECOND_WINDOWS) imgui.moulberry90.internal.ImGui.dockBuilderDockWindow(name, dock);
            imgui.moulberry90.internal.ImGui.dockBuilderFinish(dock);
        }

        ImGui.setNextWindowPos(x, top);
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

    // Windows left in the second dockspace would vanish with it, so they go back to the main one.
    private static void restoreWindows() {
        if (lastMainDock == 0) return;
        for (String name : SECOND_WINDOWS) imgui.moulberry90.internal.ImGui.dockBuilderDockWindow(name, lastMainDock);
        imgui.moulberry90.internal.ImGui.dockBuilderRemoveNode(ImGui.getID("Vector3SecondDock"));
    }

    /** The second window's events, handed to Flashback's input as if they happened to the right of the main window. */
    public static void route(SDL_Event event) {
        if (!SecondWindow.isOpen() || !ReplayUI.isActive()) return;
        if (!(ReplayUI.imguiWindower instanceof CustomImGuiWindowerSdl windower)) return;
        int type = event.type();
        float offset = secondX();
        routing = true;
        try {
            if (type == SDLEvents.SDL_EVENT_MOUSE_MOTION) {
                event.motion().x(event.motion().x() + offset);
                windower.processEvent(event);
            } else if (type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN || type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP) {
                // Flashback takes the pointer position from motion events only
                try (SDL_Event move = SDL_Event.calloc()) {
                    move.type(SDLEvents.SDL_EVENT_MOUSE_MOTION);
                    move.motion().x(event.button().x() + offset);
                    move.motion().y(event.button().y());
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
