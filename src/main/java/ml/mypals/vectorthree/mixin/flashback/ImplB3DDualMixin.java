package ml.mypals.vectorthree.mixin.flashback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.editor.ui.CustomImGuiImplB3D;
import imgui.moulberry90.ImDrawData;
import imgui.moulberry90.ImVec2;
import ml.mypals.vectorthree.fb.editor.DualLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CustomImGuiImplB3D.class, remap = false)
public class ImplB3DDualMixin {
    @WrapOperation(method = {"renderDrawData", "setupUniforms"}, at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImDrawData;getDisplaySizeX()F"))
    private float vector3$sizeX(ImDrawData data, Operation<Float> original) {
        return switch (DualLayout.pass) {
            case 1 -> DualLayout.mainW();
            case 2 -> DualLayout.secW();
            default -> original.call(data);
        };
    }

    @WrapOperation(method = {"renderDrawData", "setupUniforms"}, at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImDrawData;getDisplaySizeY()F"))
    private float vector3$sizeY(ImDrawData data, Operation<Float> original) {
        return switch (DualLayout.pass) {
            case 1 -> DualLayout.mainH();
            case 2 -> DualLayout.secH();
            default -> original.call(data);
        };
    }

    @WrapOperation(method = "setupUniforms", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImDrawData;getDisplayPosX()F"))
    private float vector3$posX(ImDrawData data, Operation<Float> original) {
        return DualLayout.pass == 2 ? DualLayout.secondX() : original.call(data);
    }

    @WrapOperation(method = "setupUniforms", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImDrawData;getDisplayPosY()F"))
    private float vector3$posY(ImDrawData data, Operation<Float> original) {
        return DualLayout.pass == 2 ? DualLayout.secondY() : original.call(data);
    }

    @WrapOperation(method = "renderDrawData", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImDrawData;getDisplayPos()Limgui/moulberry90/ImVec2;"))
    private ImVec2 vector3$pos(ImDrawData data, Operation<ImVec2> original) {
        return DualLayout.pass == 2 ? new ImVec2(DualLayout.secondX(), DualLayout.secondY()) : original.call(data);
    }

    @WrapOperation(method = "renderDrawData", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImDrawData;getFramebufferScaleX()F"))
    private float vector3$scaleX(ImDrawData data, Operation<Float> original) {
        return DualLayout.pass == 2 ? DualLayout.secScaleX() : original.call(data);
    }

    @WrapOperation(method = "renderDrawData", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImDrawData;getFramebufferScaleY()F"))
    private float vector3$scaleY(ImDrawData data, Operation<Float> original) {
        return DualLayout.pass == 2 ? DualLayout.secScaleY() : original.call(data);
    }

    @WrapOperation(method = "renderDrawData", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImDrawData;getFramebufferScale()Limgui/moulberry90/ImVec2;"))
    private ImVec2 vector3$scale(ImDrawData data, Operation<ImVec2> original) {
        return DualLayout.pass == 2 ? new ImVec2(DualLayout.secScaleX(), DualLayout.secScaleY()) : original.call(data);
    }
}
