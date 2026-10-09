package ml.mypals.vectorthree.mc.window;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import ml.mypals.vectorthree.mixin.minecraft.RenderSystemAccessor;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.function.Consumer;

/** A second SDL window with its own surface. The editor draws into {@link #imguiTarget()} and this presents it. */
public final class SecondWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger("vector3/second_window");
    private static final long FLAGS = 0x2020L;

    private static long handle;
    private static GpuSurface surface;
    private static TextureTarget fallback;
    private static RenderTarget imguiTarget;
    private static Consumer<SDL_Event> eventSink;
    private static boolean needsConfigure;
    private static boolean failed;
    private static int frames;
    private static long lastDrawNanos;

    private SecondWindow() {}

    public static boolean isOpen() {
        return handle != 0;
    }

    public static boolean failed() {
        return failed;
    }

    public static long handle() {
        return handle;
    }

    public static void setEventSink(Consumer<SDL_Event> sink) {
        eventSink = sink;
    }

    public static RenderTarget imguiTarget() {
        return imguiTarget;
    }

    public static void setImguiTarget(RenderTarget target) {
        imguiTarget = target;
    }

    public static long nanosSinceDraw() {
        return System.nanoTime() - lastDrawNanos;
    }

    public static int logicalWidth() {
        return handle == 0 ? 0 : size(false, false);
    }

    public static int logicalHeight() {
        return handle == 0 ? 0 : size(false, true);
    }

    public static int pixelWidth() {
        return handle == 0 ? 0 : size(true, false);
    }

    public static int pixelHeight() {
        return handle == 0 ? 0 : size(true, true);
    }

    private static int size(boolean pixels, boolean height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            if (pixels) SDLVideo.SDL_GetWindowSizeInPixels(handle, w, h);
            else SDLVideo.SDL_GetWindowSize(handle, w, h);
            return height ? h.get(0) : w.get(0);
        }
    }

    public static void open() {
        if (handle != 0) return;
        failed = false;
        try {
            create();
            LOGGER.info("second window opened (handle {})", handle);
        } catch (Throwable t) {
            LOGGER.error("second window failed to open", t);
            close();
            failed = true;
        }
    }

    private static void create() {
        long main = Minecraft.getInstance().getWindow().handle();
        int mainDisplay = SDLVideo.SDL_GetDisplayForWindow(main);
        IntBuffer displays = SDLVideo.SDL_GetDisplays();
        int other = 0;
        if (displays != null) {
            for (int i = 0; i < displays.remaining(); i++) {
                if (displays.get(i) != mainDisplay) {
                    other = displays.get(i);
                    break;
                }
            }
        }
        if (other == 0) throw new IllegalStateException("no second display");

        GpuBackend backend = RenderSystemAccessor.vector3$backend();
        if (backend == null) throw new IllegalStateException("no gpu backend");
        long window = backend.createWindow("Vector3 Timeline", 1280, 720, FLAGS);
        if (window == 0) throw new IllegalStateException("createWindow: " + SDLError.SDL_GetError());
        handle = window;
        LOGGER.info("backend {} created window {} for display {}", backend.getName(), window, other);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect bounds = SDL_Rect.malloc(stack);
            if (SDLVideo.SDL_GetDisplayBounds(other, bounds)) {
                SDLVideo.SDL_SetWindowPosition(window, bounds.x() + (bounds.w() - 1280) / 2, bounds.y() + (bounds.h() - 720) / 2);
            }
        }
        SDLVideo.SDL_ShowWindow(window);

        GpuDevice device = RenderSystem.getDevice();
        surface = device.createSurface(window, () -> (SDLVideo.SDL_GetWindowFlags(window) & SDLVideo.SDL_WINDOW_MINIMIZED) != 0);
        needsConfigure = true;
    }

    public static void close() {
        if (surface != null) {
            try {
                surface.close();
            } catch (Throwable t) {
                LOGGER.warn("closing second surface", t);
            }
            surface = null;
        }
        if (fallback != null) {
            fallback.destroyBuffers();
            fallback = null;
        }
        if (imguiTarget != null) {
            imguiTarget.destroyBuffers();
            imguiTarget = null;
        }
        if (handle != 0) {
            SDLVideo.SDL_DestroyWindow(handle);
            handle = 0;
            LOGGER.info("second window closed");
        }
    }

    /** Takes the events of the second window; returns true when the event was ours. */
    public static boolean consume(SDL_Event event) {
        if (handle == 0 || SDLEvents.SDL_GetWindowFromEvent(event) != handle) return false;
        int type = event.type();
        if (type == SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
            close();
            return true;
        }
        if (type == SDLEvents.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED || type == SDLEvents.SDL_EVENT_WINDOW_RESIZED) needsConfigure = true;
        if (eventSink != null) eventSink.accept(event);
        return true;
    }

    /** Presents the editor image, or a placeholder when there is none. Called once per rendered frame on the render thread. */
    public static void drawFrame(RenderTarget content) {
        if (handle == 0 || surface == null) return;
        try {
            int w = pixelWidth();
            int h = pixelHeight();
            if (w <= 0 || h <= 0) return;
            if (needsConfigure || surface.currentConfiguration().isEmpty()) {
                var mode = GpuSurface.PresentMode.getSupportedVsyncMode(surface.supportedPresentModes(), false);
                surface.configure(new GpuSurface.Configuration(w, h, mode));
                needsConfigure = false;
            }
            surface.acquireNextTexture();
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            GpuTextureView view = content == null ? null : content.getColorTextureView();
            if (view == null || content.width != w || content.height != h) {
                if (fallback == null || fallback.width != w || fallback.height != h) {
                    if (fallback != null) fallback.destroyBuffers();
                    fallback = new TextureTarget("vector3_second_window", w, h, GpuFormat.RGBA8_UNORM, null);
                }
                float pulse = 0.12f + 0.03f * (float) Math.sin(frames++ * 0.05);
                encoder.clearColorTexture(fallback.getColorTexture(), new Vector4f(0.1f, pulse, 0.14f, 1f));
                view = fallback.getColorTextureView();
            }
            surface.blitFromTexture(encoder, view);
            encoder.submit();
            surface.present();
            lastDrawNanos = System.nanoTime();
        } catch (Throwable t) {
            LOGGER.error("second window frame failed, closing", t);
            close();
            failed = true;
        }
    }
}
