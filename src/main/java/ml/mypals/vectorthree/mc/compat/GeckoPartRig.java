package ml.mypals.vectorthree.mc.compat;

import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.renderer.base.BoneSnapshots;
import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.vectorthree.core.pose.EntityPose;
import ml.mypals.vectorthree.core.pose.PartTree;
import ml.mypals.vectorthree.mc.pose.EntityPoses;
import ml.mypals.vectorthree.mc.pose.PartRig;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A GeckoLib model's bones. A pose's values are the ones GeckoLib's animations write into a bone's snapshot for a draw
 * (rotation in degrees where the snapshot has radians, offset in model units), on top of what the model gives the bone;
 * so a bone at rest is all zeros. The snapshots exist only while a draw sets its bones up: {@link #bind} them around
 * {@link #apply}, {@link #read} and {@link #frames}.
 */
final class GeckoPartRig implements PartRig {
    private final PartTree tree;
    private final Map<String, GeoBone> bones = new LinkedHashMap<>();
    private @Nullable BoneSnapshots snapshots;

    GeckoPartRig(BakedGeoModel model) {
        PartTree.Builder builder = PartTree.builder();
        List<GeoBone> input = new ArrayList<>();
        for (GeoBone top : model.topLevelBones()) collect(top, -1, builder, input);
        tree = builder.build();
        for (int i = 0; i < input.size(); i++) bones.put(tree.nameOfInput(i), input.get(i));
    }

    private static void collect(GeoBone bone, int parent, PartTree.Builder builder, List<GeoBone> input) {
        int index = builder.add(bone.name(), parent);
        input.add(bone);
        for (GeoBone child : bone.children()) collect(child, index, builder, input);
    }

    void bind(@Nullable BoneSnapshots snapshots) {
        this.snapshots = snapshots;
    }

    private @Nullable BoneSnapshot snapshot(GeoBone bone) {
        return snapshots == null ? bone.frameSnapshot : snapshots.get(bone);
    }

    @Override
    public PartTree tree() {
        return tree;
    }

    @Override
    public void apply(EntityPose pose) {
        boolean absolute = pose.mode() == EntityPose.Mode.ABSOLUTE;
        for (Map.Entry<String, EntityPose.Limb> entry : pose.parts().entrySet()) {
            GeoBone bone = bones.get(entry.getKey());
            BoneSnapshot snapshot = bone == null ? null : snapshot(bone);
            if (snapshot == null) continue;
            EntityPose.Limb limb = entry.getValue();
            if (limb.rotate()) {
                float x = limb.xRot() * Mth.DEG_TO_RAD, y = limb.yRot() * Mth.DEG_TO_RAD, z = limb.zRot() * Mth.DEG_TO_RAD;
                snapshot.setRotation(absolute ? x : snapshot.getRotX() + x, absolute ? y : snapshot.getRotY() + y,
                        absolute ? z : snapshot.getRotZ() + z);
            }
            if (limb.move()) {
                snapshot.setTranslation(absolute ? limb.x() : snapshot.getTranslateX() + limb.x(),
                        absolute ? limb.y() : snapshot.getTranslateY() + limb.y(),
                        absolute ? limb.z() : snapshot.getTranslateZ() + limb.z());
            }
        }
    }

    @Override
    public Map<String, EntityPose.Limb> read() {
        Map<String, EntityPose.Limb> limbs = new LinkedHashMap<>();
        bones.forEach((name, bone) -> {
            BoneSnapshot s = snapshot(bone);
            if (s == null) {
                limbs.put(name, EntityPose.Limb.NONE);
                return;
            }
            boolean moved = Math.abs(s.getTranslateX()) > 1.0e-4f || Math.abs(s.getTranslateY()) > 1.0e-4f
                    || Math.abs(s.getTranslateZ()) > 1.0e-4f;
            limbs.put(name, new EntityPose.Limb(true, moved, s.getRotX() * Mth.RAD_TO_DEG, s.getRotY() * Mth.RAD_TO_DEG,
                    s.getRotZ() * Mth.RAD_TO_DEG, s.getTranslateX(), s.getTranslateY(), s.getTranslateZ()));
        });
        return limbs;
    }

    @Override
    public Map<String, EntityPose.Limb> rest() {
        Map<String, EntityPose.Limb> limbs = new LinkedHashMap<>();
        bones.keySet().forEach(name -> limbs.put(name, EntityPose.Limb.NONE));
        return limbs;
    }

    @Override
    public Map<String, EntityPoses.Frame> frames(Matrix4f entityMatrix, Vec3 camera) {
        Map<String, EntityPoses.Frame> frames = new LinkedHashMap<>();
        bones.forEach((name, bone) -> {
            Matrix4f parent = new Matrix4f(entityMatrix);
            for (String ancestor : tree.ancestors(name)) place(parent, bones.get(ancestor), true);
            BoneSnapshot s = snapshot(bone);
            float tx = s == null ? 0 : s.getTranslateX(), ty = s == null ? 0 : s.getTranslateY(), tz = s == null ? 0 : s.getTranslateZ();
            float rx = s == null ? 0 : s.getRotX(), ry = s == null ? 0 : s.getRotY(), rz = s == null ? 0 : s.getRotZ();
            Vector3f pivot = parent.transformPosition(new Vector3f((bone.pivotX() - tx) / 16f, (bone.pivotY() + ty) / 16f,
                    (bone.pivotZ() + tz) / 16f));
            Quaternionf z = new Quaternionf().rotationZ(bone.baseRotZ() + rz);
            Quaternionf zy = new Quaternionf(z).rotateY(bone.baseRotY() + ry);
            frames.put(name, new EntityPoses.Frame(new Vec3(pivot.x + camera.x, pivot.y + camera.y, pivot.z + camera.z),
                    EntityPoses.worldAxis(parent, zy.transform(new Vector3f(1, 0, 0))),
                    EntityPoses.worldAxis(parent, z.transform(new Vector3f(0, 1, 0))),
                    EntityPoses.worldAxis(parent, new Vector3f(0, 0, 1)),
                    parent.determinant3x3() < 0,
                    rx * Mth.RAD_TO_DEG, ry * Mth.RAD_TO_DEG, rz * Mth.RAD_TO_DEG,
                    EntityPoses.worldStep(parent, -1, 0, 0), EntityPoses.worldStep(parent, 0, 1, 0),
                    EntityPoses.worldStep(parent, 0, 0, 1),
                    tx, ty, tz));
        });
        return frames;
    }

    @Override
    public void transform(String part, PoseStack stack, boolean self) {
        Matrix4f pose = stack.last().pose();
        for (String ancestor : tree.ancestors(part)) place(pose, bones.get(ancestor), true);
        GeoBone target = bones.get(part);
        if (self && target != null) place(pose, target, false);
    }

    /** What GeckoLib's RenderUtil does to the pose stack for a bone: move, to the pivot, turn, scale, and back from it. */
    private void place(Matrix4f pose, GeoBone bone, boolean awayFromPivot) {
        BoneSnapshot s = snapshot(bone);
        if (s != null) pose.translate(-s.getTranslateX() / 16f, s.getTranslateY() / 16f, s.getTranslateZ() / 16f);
        pose.translate(bone.pivotX() / 16f, bone.pivotY() / 16f, bone.pivotZ() / 16f);
        pose.rotateZYX(bone.baseRotZ() + (s == null ? 0 : s.getRotZ()), bone.baseRotY() + (s == null ? 0 : s.getRotY()),
                bone.baseRotX() + (s == null ? 0 : s.getRotX()));
        if (s != null) pose.scale(s.getScaleX(), s.getScaleY(), s.getScaleZ());
        if (awayFromPivot) pose.translate(-bone.pivotX() / 16f, -bone.pivotY() / 16f, -bone.pivotZ() / 16f);
    }
}
