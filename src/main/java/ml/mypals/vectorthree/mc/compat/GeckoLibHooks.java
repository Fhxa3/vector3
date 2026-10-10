package ml.mypals.vectorthree.mc.compat;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.event.entity.GeoEntityPreRenderEvent;
import com.geckolib.event.replacedentity.GeoReplacedEntityPreRenderEvent;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import ml.mypals.vectorthree.mc.pose.EntityPoses;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.WeakHashMap;


final class GeckoLibHooks {
    private static final Map<BakedGeoModel, GeckoPartRig> RIGS = new WeakHashMap<>();

    private GeckoLibHooks() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void register() {
        GeoEntityPreRenderEvent.EVENT.register(event -> poseDraw(((GeoEntityPreRenderEvent) event).getRenderPassInfo()));
        GeoReplacedEntityPreRenderEvent.EVENT.register(event -> poseDraw(((GeoReplacedEntityPreRenderEvent) event).getRenderPassInfo()));
    }

    /** @return true: the draw goes on */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean poseDraw(RenderPassInfo info) {
        GeoRenderState state = info.renderState();
        if (!(state instanceof EntityPoses.Holder holder) || holder.vector3$poseEntity() == null) return true;
        GeckoPartRig rig = RIGS.computeIfAbsent(info.model(), GeckoPartRig::new);
        EntityPoses.Draw draw = EntityPoses.drawing(state);
        info.addBoneUpdater((pass, snapshots) -> {
            rig.bind(snapshots);
            try {
                EntityPoses.rigPosed(rig, state, draw, new Matrix4f(((RenderPassInfo<?>) pass).getModelRenderMatrixPose().pose()));
            } finally {
                rig.bind(null);
            }
        });
        return true;
    }
}
