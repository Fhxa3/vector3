package ml.mypals.vectorthree.mc.window;

import org.lwjgl.sdl.SDLVideo;

import java.nio.IntBuffer;

public final class SdlDisplays {
    private SdlDisplays() {}

    public static int count() {
        IntBuffer displays = SDLVideo.SDL_GetDisplays();
        return displays == null ? 0 : displays.remaining();
    }
}
