package ml.mypals.vectorthree.mixin.flashback;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.moulberry.flashback.editor.ui.CustomImGuiImplB3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CustomImGuiImplB3D.class, remap = false)
public interface ImplB3DAccessor {
    @Accessor("renderTarget")
    RenderTarget vector3$renderTarget();

    @Accessor("renderTarget")
    void vector3$renderTarget(RenderTarget target);
}
