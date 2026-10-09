package ml.mypals.vectorthree.mixin.flashback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.moulberry.flashback.editor.ui.CustomImGuiImplB3D;
import com.moulberry.flashback.editor.ui.ReplayUI;
import imgui.moulberry90.ImDrawData;
import imgui.moulberry90.ImGuiViewport;
import ml.mypals.vectorthree.fb.editor.DualLayout;
import ml.mypals.vectorthree.mc.window.SecondWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Frame boundaries for the second window, the main dockspace limited to the main window, and one render pass per window.
@Mixin(value = ReplayUI.class, remap = false)
public class ReplayUIDualMixin {
    @Inject(method = "drawOverlay", at = @At("HEAD"))
    private static void vector3$beginDual(CallbackInfo ci) {
        DualLayout.beginFrame();
    }

    @Inject(method = "drawOverlay", at = @At("RETURN"))
    private static void vector3$endDual(CallbackInfo ci) {
        DualLayout.endFrame();
    }

    @WrapOperation(method = "drawOverlayInternal", at = @At(value = "INVOKE",
            target = "Limgui/moulberry90/ImGui;dockSpaceOverViewport(ILimgui/moulberry90/ImGuiViewport;I)I"))
    private static int vector3$mainDock(int id, ImGuiViewport viewport, int flags, Operation<Integer> original) {
        if (!DualLayout.frameActive()) return original.call(id, viewport, flags);
        float fullWidth = viewport.getWorkSizeX();
        float height = viewport.getWorkSizeY();
        float top = viewport.getWorkPosY();
        viewport.setWorkSize(DualLayout.mainW(), Math.max(1, DualLayout.mainH() - top));
        int dock = original.call(id, viewport, flags);
        viewport.setWorkSize(fullWidth, height);
        DualLayout.secondDock(dock, top);
        return dock;
    }

    @WrapOperation(method = {"drawOverlayInternal", "transitionActiveState"}, at = @At(value = "INVOKE",
            target = "Limgui/moulberry90/ImGuiViewport;getSizeX()F"))
    private static float vector3$viewportWidth(ImGuiViewport viewport, Operation<Float> original) {
        return DualLayout.frameActive() ? DualLayout.mainW() : original.call(viewport);
    }

    @WrapOperation(method = {"drawOverlayInternal", "transitionActiveState"}, at = @At(value = "INVOKE",
            target = "Limgui/moulberry90/ImGuiViewport;getSizeY()F"))
    private static float vector3$viewportHeight(ImGuiViewport viewport, Operation<Float> original) {
        return DualLayout.frameActive() ? DualLayout.mainH() : original.call(viewport);
    }

    @WrapOperation(method = "drawOverlayInternal", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImGuiViewport;getCenterX()F"))
    private static float vector3$centerX(ImGuiViewport viewport, Operation<Float> original) {
        return DualLayout.frameActive() ? DualLayout.mainW() / 2 : original.call(viewport);
    }

    @WrapOperation(method = "drawOverlayInternal", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImGuiViewport;getCenterY()F"))
    private static float vector3$centerY(ImGuiViewport viewport, Operation<Float> original) {
        return DualLayout.frameActive() ? DualLayout.mainH() / 2 : original.call(viewport);
    }

    // The same draw data is rendered twice, once per window, each time into that window's own target.
    @WrapOperation(method = "drawOverlayInternal", at = @At(value = "INVOKE",
            target = "Lcom/moulberry/flashback/editor/ui/CustomImGuiImplB3D;renderDrawData(Limgui/moulberry90/ImDrawData;)Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private static RenderTarget vector3$renderBoth(CustomImGuiImplB3D renderer, ImDrawData data, Operation<RenderTarget> original) {
        if (!DualLayout.frameActive()) return original.call(renderer, data);
        RenderTarget main;
        DualLayout.pass = 1;
        try {
            main = original.call(renderer, data);
        } finally {
            DualLayout.pass = 0;
        }
        ImplB3DAccessor accessor = (ImplB3DAccessor) (Object) renderer;
        RenderTarget own = accessor.vector3$renderTarget();
        accessor.vector3$renderTarget(SecondWindow.imguiTarget());
        DualLayout.pass = 2;
        try {
            SecondWindow.setImguiTarget(original.call(renderer, data));
        } finally {
            DualLayout.pass = 0;
            accessor.vector3$renderTarget(own);
        }
        return main;
    }
}
