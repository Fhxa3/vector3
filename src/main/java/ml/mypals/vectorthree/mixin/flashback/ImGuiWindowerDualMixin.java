package ml.mypals.vectorthree.mixin.flashback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.editor.ui.CustomImGuiWindowerSdl;
import imgui.moulberry90.ImGuiIO;
import ml.mypals.vectorthree.fb.editor.DualLayout;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// With two windows the ImGui display is both windows side by side, and the second window's events are accepted.
@Mixin(value = CustomImGuiWindowerSdl.class, remap = false)
public class ImGuiWindowerDualMixin {
    @Shadow
    private long windowId;

    @WrapOperation(method = "updateContentScale", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImGuiIO;setDisplaySize(FF)V"))
    private void vector3$canvasSize(ImGuiIO io, float width, float height, Operation<Void> original) {
        DualLayout.setMain(width, height);
        if (DualLayout.frameActive()) original.call(io, DualLayout.canvasW(), DualLayout.canvasH());
        else original.call(io, width, height);
    }

    @WrapOperation(method = "processEvent", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLEvents;SDL_GetWindowFromEvent(Lorg/lwjgl/sdl/SDL_Event;)J"))
    private long vector3$eventWindow(SDL_Event event, Operation<Long> original) {
        return DualLayout.routing ? this.windowId : original.call(event);
    }

    @WrapOperation(method = "processEvent", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImGuiIO;addFocusEvent(Z)V"))
    private void vector3$focus(ImGuiIO io, boolean focused, Operation<Void> original) {
        original.call(io, DualLayout.focus(focused));
    }

    @Inject(method = "updateIme", at = @At("TAIL"))
    private void vector3$secondTextInput(ImGuiIO io, CallbackInfo ci) {
        DualLayout.textInput(io.getWantTextInput());
    }
}
