package ml.mypals.vectorthree.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {
    @Accessor("BACKEND")
    static GpuBackend vector3$backend() {
        throw new AssertionError();
    }
}
