package ml.mypals.vectorthree.mc.window;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
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

/** Feasibility prototype: a second SDL window with its own surface, cleared to a solid color each frame. */
public final class SecondWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger("vector3/second_window");
    private static final long FLAGS = 0x2020L;

    private static long handle;
    private static GpuSurface surface;
    private static TextureTarget target;
    private static boolean needsConfigure;
    private static boolean failed;
    private static int frames;

    private SecondWindow() {}

    public static boolean isOpen() {
        return handle != 0;
    }

    public static boolean failed() {
        return failed;
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
        if (target != null) {
            target.destroyBuffers();
            target = null;
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
        } else if (type == SDLEvents.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED || type == SDLEvents.SDL_EVENT_WINDOW_RESIZED) {
            needsConfigure = true;
        }
        return true;
    }

    /** Draws and presents one frame; called once per rendered frame on the render thread. */
    public static void frame() {
        if (handle == 0 || surface == null) return;
        try {
            int w;
            int h;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer pw = stack.mallocInt(1);
                IntBuffer ph = stack.mallocInt(1);
                SDLVideo.SDL_GetWindowSizeInPixels(handle, pw, ph);
                w = pw.get(0);
                h = ph.get(0);
            }
            if (w <= 0 || h <= 0) return;
            if (needsConfigure || surface.currentConfiguration().isEmpty()) {
                var mode = GpuSurface.PresentMode.getSupportedVsyncMode(surface.supportedPresentModes(), false);
                surface.configure(new GpuSurface.Configuration(w, h, mode));
                needsConfigure = false;
            }
            if (target == null || target.width != w || target.height != h) {
                if (target != null) target.destroyBuffers();
                target = new TextureTarget("vector3_second_window", w, h, GpuFormat.RGBA8_UNORM, null);
            }
            surface.acquireNextTexture();
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            float pulse = 0.35f + 0.15f * (float) Math.sin(frames++ * 0.05);
            encoder.clearColorTexture(target.getColorTexture(), new Vector4f(0.1f, pulse, 0.3f, 1f));
            surface.blitFromTexture(encoder, target.getColorTextureView());
            encoder.submit();
            surface.present();
        } catch (Throwable t) {
            LOGGER.error("second window frame failed, closing", t);
            close();
            failed = true;
        }
    }
}
