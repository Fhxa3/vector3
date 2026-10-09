package ml.mypals.vectorthree.mixin.minecraft;

import ml.mypals.vectorthree.mc.window.SecondWindow;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftCloseMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void vector3$closeSecondWindow(CallbackInfo ci) {
        SecondWindow.close();
    }
}
