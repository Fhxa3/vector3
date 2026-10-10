package ml.mypals.vectorthree.mc.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The only way the rest of the mod reaches GeckoLib. GeckoLibHooks (which links against GeckoLib) is never loaded when
 * GeckoLib isn't installed.
 */
public final class GeckoLibCompat {
    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("geckolib");

    private GeckoLibCompat() {}

    /** Lets entity poses reach GeckoLib bones. */
    public static void register() {
        if (LOADED) GeckoLibHooks.register();
    }
}
