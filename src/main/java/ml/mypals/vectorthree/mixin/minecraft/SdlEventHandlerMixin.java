package ml.mypals.vectorthree.mixin.minecraft;

import com.mojang.blaze3d.platform.SDLEventHandler;
import ml.mypals.vectorthree.mc.window.SecondWindow;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Minecraft applies every window event to the main window, so events of the second window must not reach it.
@Mixin(SDLEventHandler.class)
public class SdlEventHandlerMixin {
    @Redirect(method = "pollEvents", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLEvents;SDL_PollEvent(Lorg/lwjgl/sdl/SDL_Event;)Z"))
    private boolean vector3$pollEvent(SDL_Event event) {
        while (SDLEvents.SDL_PollEvent(event)) {
            if (!SecondWindow.consume(event)) return true;
        }
        return false;
    }
}
