package ml.mypals.vectorthree.mc.pose;

import com.mojang.blaze3d.vertex.PoseStack;
import ml.mypals.vectorthree.core.Mod;
import ml.mypals.vectorthree.core.pose.EntityPose;
import ml.mypals.vectorthree.core.pose.PartJoints;
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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A vanilla model: its {@link ModelPart}s, named by their keys in the parents' children maps. */
final class VanillaPartRig implements PartRig {
    private final PartTree tree;
    private final PartTree jointTree;
    private final Map<String, String> joints;
    private final Map<String, ModelPart> parts = new LinkedHashMap<>();
    // What each joined part was carried by this frame (in its parent's space), so its own rotation and offset can be told.
    private final Map<String, Matrix4f> carries = new HashMap<>();

    VanillaPartRig(Model<?> model) {
        PartTree.Builder builder = PartTree.builder();
        List<ModelPart> input = new ArrayList<>();
        collect(model.root(), EntityPose.ROOT, -1, builder, input, Collections.newSetFromMap(new IdentityHashMap<>()));
        tree = builder.build();
        for (int i = 0; i < input.size(); i++) parts.put(tree.nameOfInput(i), input.get(i));
        joints = PartJoints.infer(describe());
        jointTree = tree.withParents(joints);
        if (!joints.isEmpty()) Mod.LOGGER.debug("Joined the parts of {}: {}", model.getClass().getSimpleName(), joints);
    }

    private List<PartJoints.Part> describe() {
        List<PartJoints.Part> described = new ArrayList<>();
        parts.forEach((name, part) -> {
            PartPose rest = part.getInitialPose();
            double volume = 0;
            for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).vector3$cubes()) {
                volume += Math.abs((cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ));
            }
            described.add(new PartJoints.Part(name, tree.parent(name), rest.x(), rest.y(), rest.z(), volume));
        });
        return described;
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
    public void begin() {
        carries.clear();
    }

    @Override
    public PartTree jointTree() {
        return jointTree;
    }

    @Override
    public void apply(EntityPose pose) {
        boolean absolute = pose.mode() == EntityPose.Mode.ABSOLUTE;
        if (!pose.joints() || joints.isEmpty()) {
            pose.parts().forEach((name, limb) -> {
                ModelPart part = parts.get(name);
                if (part != null) set(part, limb, absolute);
            });
            return;
        }
        // Parents first. A part that moved (by its own limb, or because what it is joined to moved) carries the joined
        // parts along: each takes the same rigid move about the hinge, on top of what its own limb gives it.
        Map<String, Matrix4f> moved = new HashMap<>();
        for (String name : jointTree.names()) {
            ModelPart part = parts.get(name);
            EntityPose.Limb limb = pose.parts().get(name);
            String parent = joints.get(name);
            Matrix4f carried = parent == null ? null : moved.get(parent);
            boolean own = limb != null && limb.active();
            if (part == null || !own && carried == null) continue;
            Matrix4f before = placement(part);
            if (own) set(part, limb, absolute);
            if (carried != null) {
                place(part, carried.mul(placement(part), new Matrix4f()));
                carries.put(name, new Matrix4f(carried));
            }
            moved.put(name, placement(part).mul(before.invert(), new Matrix4f()));
        }
    }

    private static void set(ModelPart part, EntityPose.Limb limb, boolean absolute) {
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

    // The part's place in its parent's space, as ModelPart#translateAndRotate takes it there.
    private static Matrix4f placement(ModelPart part) {
        return new Matrix4f().translation(part.x / 16f, part.y / 16f, part.z / 16f).rotateZYX(part.zRot, part.yRot, part.xRot);
    }

    private static void place(ModelPart part, Matrix4f placement) {
        Vector3f angles = placement.getEulerAnglesZYX(new Vector3f());
        part.x = placement.m30() * 16f;
        part.y = placement.m31() * 16f;
        part.z = placement.m32() * 16f;
        part.xRot = angles.x;
        part.yRot = angles.y;
        part.zRot = angles.z;
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
            PartPose rest = part.getInitialPose();
            // The part's own place is where it is drawn less what a joint carried it by.
            float x = part.x, y = part.y, z = part.z, xRot = part.xRot, yRot = part.yRot, zRot = part.zRot;
            Quaternionf carry = new Quaternionf();
            Matrix4f carried = carries.get(name);
            if (carried != null) {
                Matrix4f own = carried.invert(new Matrix4f()).mul(placement(part));
                Vector3f angles = own.getEulerAnglesZYX(new Vector3f());
                x = own.m30() * 16f;
                y = own.m31() * 16f;
                z = own.m32() * 16f;
                xRot = angles.x;
                yRot = angles.y;
                zRot = angles.z;
                carried.getNormalizedRotation(carry);
            }
            Vector3f moveX = carry.transform(new Vector3f(1, 0, 0)), moveY = carry.transform(new Vector3f(0, 1, 0)),
                    moveZ = carry.transform(new Vector3f(0, 0, 1));
            frames.put(name, new EntityPoses.Frame(new Vec3(pivot.x + camera.x, pivot.y + camera.y, pivot.z + camera.z),
                    EntityPoses.worldAxis(parentMatrix, new Vector3f(1, 0, 0)),
                    EntityPoses.worldAxis(parentMatrix, new Vector3f(0, 1, 0)),
                    EntityPoses.worldAxis(parentMatrix, new Vector3f(0, 0, 1)),
                    parentMatrix.determinant3x3() < 0,
                    xRot * Mth.RAD_TO_DEG, yRot * Mth.RAD_TO_DEG, zRot * Mth.RAD_TO_DEG,
                    EntityPoses.worldStep(parentMatrix, moveX.x, moveX.y, moveX.z),
                    EntityPoses.worldStep(parentMatrix, moveY.x, moveY.y, moveY.z),
                    EntityPoses.worldStep(parentMatrix, moveZ.x, moveZ.y, moveZ.z),
                    x - rest.x(), y - rest.y(), z - rest.z(), carry, 0, 0, 0));
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
