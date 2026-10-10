package ml.mypals.vectorthree.mc.pose;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.vectorthree.core.pose.EntityPose;
import ml.mypals.vectorthree.core.pose.PartTree;
import ml.mypals.vectorthree.mixin.minecraft.pose.ModelPartAccessor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A vanilla model: its {@link ModelPart}s, named by their keys in the parents' children maps. */
final class VanillaPartRig implements PartRig {
    private final PartTree tree;
    private final Map<String, ModelPart> parts = new LinkedHashMap<>();

    VanillaPartRig(Model<?> model) {
        PartTree.Builder builder = PartTree.builder();
        List<ModelPart> input = new ArrayList<>();
        collect(model.root(), EntityPose.ROOT, -1, builder, input, Collections.newSetFromMap(new IdentityHashMap<>()));
        tree = builder.build();
        for (int i = 0; i < input.size(); i++) parts.put(tree.nameOfInput(i), input.get(i));
    }

    // A part reached again while still inside itself (a cyclic model) is not entered twice.
    private static void collect(ModelPart part, String name, int parent, PartTree.Builder builder, List<ModelPart> input,
                                Set<ModelPart> path) {
        if (!path.add(part)) return;
        int index = builder.add(name, parent);
        input.add(part);
        ((ModelPartAccessor) (Object) part).vector3$children()
                .forEach((childName, child) -> collect(child, childName, index, builder, input, path));
        path.remove(part);
    }

    @Override
    public PartTree tree() {
        return tree;
    }

    @Override
    public void apply(EntityPose pose) {
        boolean absolute = pose.mode() == EntityPose.Mode.ABSOLUTE;
        for (Map.Entry<String, EntityPose.Limb> entry : pose.parts().entrySet()) {
            EntityPose.Limb limb = entry.getValue();
            ModelPart part = parts.get(entry.getKey());
            if (part == null) continue;
            if (limb.rotate()) {
                float xRot = limb.xRot() * Mth.DEG_TO_RAD, yRot = limb.yRot() * Mth.DEG_TO_RAD, zRot = limb.zRot() * Mth.DEG_TO_RAD;
                part.xRot = absolute ? xRot : part.xRot + xRot;
                part.yRot = absolute ? yRot : part.yRot + yRot;
                part.zRot = absolute ? zRot : part.zRot + zRot;
            }
            if (limb.move()) {
                PartPose rest = part.getInitialPose();
                part.x = (absolute ? rest.x() : part.x) + limb.x();
                part.y = (absolute ? rest.y() : part.y) + limb.y();
                part.z = (absolute ? rest.z() : part.z) + limb.z();
            }
        }
    }

    @Override
    public Map<String, EntityPose.Limb> read() {
        Map<String, EntityPose.Limb> limbs = new LinkedHashMap<>();
        parts.forEach((name, part) -> {
            PartPose rest = part.getInitialPose();
            float x = part.x - rest.x(), y = part.y - rest.y(), z = part.z - rest.z();
            boolean moved = Math.abs(x) > 1.0e-4f || Math.abs(y) > 1.0e-4f || Math.abs(z) > 1.0e-4f;
            limbs.put(name, new EntityPose.Limb(true, moved, part.xRot * Mth.RAD_TO_DEG, part.yRot * Mth.RAD_TO_DEG,
                    part.zRot * Mth.RAD_TO_DEG, x, y, z));
        });
        return limbs;
    }

    @Override
    public Map<String, EntityPose.Limb> rest() {
        Map<String, EntityPose.Limb> limbs = new LinkedHashMap<>();
        parts.forEach((name, part) -> {
            PartPose rest = part.getInitialPose();
            limbs.put(name, new EntityPose.Limb(false, false, rest.xRot() * Mth.RAD_TO_DEG, rest.yRot() * Mth.RAD_TO_DEG,
                    rest.zRot() * Mth.RAD_TO_DEG, 0, 0, 0));
        });
        return limbs;
    }

    @Override
    public Map<String, EntityPoses.Frame> frames(Matrix4f entityMatrix, Vec3 camera) {
        Map<String, EntityPoses.Frame> frames = new LinkedHashMap<>();
        parts.forEach((name, part) -> {
            PoseStack stack = new PoseStack();
            stack.last().pose().set(entityMatrix);
            transform(name, stack, false);
            Matrix4f parentMatrix = stack.last().pose();
            Vector3f pivot = parentMatrix.transformPosition(new Vector3f(part.x / 16f, part.y / 16f, part.z / 16f));
            Quaternionf z = new Quaternionf().rotationZ(part.zRot);
            Quaternionf zy = new Quaternionf(z).rotateY(part.yRot);
            PartPose rest = part.getInitialPose();
            frames.put(name, new EntityPoses.Frame(new Vec3(pivot.x + camera.x, pivot.y + camera.y, pivot.z + camera.z),
                    EntityPoses.worldAxis(parentMatrix, zy.transform(new Vector3f(1, 0, 0))),
                    EntityPoses.worldAxis(parentMatrix, z.transform(new Vector3f(0, 1, 0))),
                    EntityPoses.worldAxis(parentMatrix, new Vector3f(0, 0, 1)),
                    parentMatrix.determinant3x3() < 0,
                    part.xRot * Mth.RAD_TO_DEG, part.yRot * Mth.RAD_TO_DEG, part.zRot * Mth.RAD_TO_DEG,
                    EntityPoses.worldStep(parentMatrix, 1, 0, 0), EntityPoses.worldStep(parentMatrix, 0, 1, 0),
                    EntityPoses.worldStep(parentMatrix, 0, 0, 1),
                    part.x - rest.x(), part.y - rest.y(), part.z - rest.z()));
        });
        return frames;
    }

    @Override
    public void transform(String part, PoseStack stack, boolean self) {
        for (String ancestor : tree.ancestors(part)) parts.get(ancestor).translateAndRotate(stack);
        if (self) {
            ModelPart target = parts.get(part);
            if (target != null) target.translateAndRotate(stack);
        }
    }
}
