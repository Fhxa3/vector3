package ml.mypals.vectorthree.mc.pose;

import ml.mypals.vectorthree.core.pose.EntityPose;
import ml.mypals.vectorthree.core.pose.PartTree;

import ml.mypals.vectorthree.mc.camera.PreviewPass;
import ml.mypals.vectorthree.mc.compat.IrisCompat;
import ml.mypals.vectorthree.mc.render.ScreenLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Applies entity pose keyframes while models are set up. Poses are collected from the keyframes on the render
 * thread (begin / request / finish, like LookToCamera), one per track in track order, handed to each
 * EntityRenderState at extraction, and applied in that order after every setupAnim that state goes through (the
 * entity's own model, and each armour / cape / elytra model submitted with it), so everything attached follows.
 */
public final class EntityPoses {
    /** Implemented on EntityRenderState. */
    public interface Holder {
        @Nullable UUID vector3$poseEntity();

        List<EntityPose> vector3$poses();

        void vector3$setPoses(@Nullable UUID entity, List<EntityPose> poses);
    }

    /** A renderer outside LivingEntityRenderer whose entity has a posable model (the ender dragon's). */
    public interface ModelOwner {
        Model<?> vector3$poseModel();
    }

    private static final Map<UUID, List<EntityPose>> pending = new HashMap<>();
    private static Map<UUID, List<EntityPose>> current = Map.of();
    private static final Set<UUID> snapshotRequests = new HashSet<>();
    private static final Map<UUID, Map<String, EntityPose.Limb>> snapshots = new HashMap<>();
    private static final Map<Model<?>, PartRig> RIGS = new WeakHashMap<>();
    private static final Map<UUID, Preview> previews = new HashMap<>();

    /** {@code replacement} shown in place of the track pose {@code original} (by identity), or on top if absent. */
    private record Preview(EntityPose original, EntityPose replacement) {}

    /**
     * Where a part sits this frame: its pivot and the world axes its X / Y / Z rotations turn about.
     * {@code moveX..Z}: the world displacement of one unit of offset; {@code x..z}: the part's offset from rest.
     */
    public record Frame(Vec3 pivot, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis, boolean mirrored, float xRot, float yRot, float zRot,
                        Vec3 moveX, Vec3 moveY, Vec3 moveZ, float x, float y, float z) {}

    private static volatile @Nullable UUID watched;
    private static volatile Map<String, Frame> watchedFrames = Map.of();

    private EntityPoses() {}

    /** The entity whose part frames {@link #watchedFrames} should follow, or null. */
    public static void watch(@Nullable UUID entity) {
        if (!Objects.equals(watched, entity)) watchedFrames = Map.of();
        watched = entity;
    }

    public static boolean isWatched(UUID entity) {
        return entity.equals(watched);
    }

    public static void publishFrames(UUID entity, Map<String, Frame> frames) {
        if (entity.equals(watched)) watchedFrames = frames;
    }

    /**
     * A part's frame for a model that is not a vanilla one: {@code parentMatrix} takes the part's parent space to
     * camera-relative world, {@code pivot} is where the part turns (in that space), rotations in degrees, offsets in
     * the parent's units.
     */
    public static Frame frame(Matrix4f parentMatrix, Vector3f pivot, float[] rotation, float[] offset, Vec3 camera) {
        Vector3f at = parentMatrix.transformPosition(new Vector3f(pivot));
        Quaternionf z = new Quaternionf().rotationZ(rotation[2] * Mth.DEG_TO_RAD);
        Quaternionf zy = new Quaternionf(z).rotateY(rotation[1] * Mth.DEG_TO_RAD);
        return new Frame(new Vec3(at.x + camera.x, at.y + camera.y, at.z + camera.z),
                worldAxis(parentMatrix, zy.transform(new Vector3f(1, 0, 0))),
                worldAxis(parentMatrix, z.transform(new Vector3f(0, 1, 0))),
                worldAxis(parentMatrix, new Vector3f(0, 0, 1)),
                parentMatrix.determinant3x3() < 0,
                rotation[0], rotation[1], rotation[2],
                worldStep(parentMatrix, 16, 0, 0), worldStep(parentMatrix, 0, 16, 0), worldStep(parentMatrix, 0, 0, 16),
                offset[0], offset[1], offset[2]);
    }

    public static Map<String, Frame> watchedFrames() {
        return watchedFrames;
    }

    public static void begin() {
        pending.clear();
    }

    /** Called once per track, in track order; several tracks may pose one entity. */
    public static void request(EntityPose pose) {
        if (pose.entity() != null) pending.computeIfAbsent(pose.entity(), entity -> new ArrayList<>()).add(pose);
    }

    public static void finish() {
        Map<UUID, List<EntityPose>> next = new HashMap<>();
        pending.forEach((entity, poses) -> next.put(entity, List.copyOf(poses)));
        current = next;
    }

    public static void clear() {
        previews.clear();
        pending.clear();
        current = Map.of();
        snapshotRequests.clear();
        snapshots.clear();
        MODEL_MATRICES.clear();
        DRAWN_RIGS.clear();
        submission = null;
    }

    private static @Nullable UUID identity;

    /** Extracts under another UUID: a projected entity poses as its shape (see ProjectedEntityShape). */
    public static <T> T asIdentity(UUID uuid, java.util.function.Supplier<T> extract) {
        UUID previous = identity;
        identity = uuid;
        try {
            return extract.get();
        } finally {
            identity = previous;
        }
    }

    /** Whose poses an entity being extracted takes: the projecting shape's when it has any, else its own. */
    public static UUID poseIdentity(UUID own) {
        UUID override = identity;
        return override == null || get(override).isEmpty() && !get(own).isEmpty() ? own : override;
    }

    /** Hands an entity's poses to its render state as it is extracted. */
    public static void attach(Entity entity, Object state) {
        if (!(state instanceof Holder holder)) return;
        UUID identity = poseIdentity(entity.getUUID());
        holder.vector3$setPoses(identity, get(identity));
    }

    public static List<EntityPose> get(UUID entity) {
        List<EntityPose> poses = current.isEmpty() ? List.of() : current.getOrDefault(entity, List.of());
        Preview preview = previews.isEmpty() ? null : previews.get(entity);
        if (preview == null) return poses;
        List<EntityPose> shown = new ArrayList<>(poses);
        int index = -1;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i) == preview.original()) index = i;
        }
        if (index >= 0) shown.set(index, preview.replacement());
        else shown.add(preview.replacement());
        return shown;
    }

    /**
     * Shows {@code replacement} instead of the track pose {@code original} until cleared, e.g. while a gizmo drags;
     * the other tracks keep applying around it.
     */
    public static void preview(@Nullable UUID entity, EntityPose original, @Nullable EntityPose replacement) {
        if (entity == null) return;
        if (replacement == null) previews.remove(entity);
        else previews.put(entity, new Preview(original, replacement));
    }

    public static void clearPreview(@Nullable UUID entity) {
        if (entity != null) previews.remove(entity);
    }

    /** Called right after a model's setupAnim with the render state it was given; {@code snapshot} allows taking a requested snapshot first. */
    public static void afterSetupAnim(Model<?> model, Object state, boolean snapshot) {
        if (!(state instanceof Holder holder) || holder.vector3$poseEntity() == null) return;
        afterSetupAnim(rig(model), state, snapshot);
    }

    /** As above for any posable model, once its rig is known. */
    public static void afterSetupAnim(PartRig rig, Object state, boolean snapshot) {
        if (!(state instanceof Holder holder) || holder.vector3$poseEntity() == null) return;
        UUID entity = holder.vector3$poseEntity();
        if (snapshot && snapshotRequests.remove(entity)) snapshots.put(entity, rig.read());
        for (EntityPose pose : holder.vector3$poses()) rig.apply(pose);
    }

    /** Asks for the entity's animated pose on its next frame; poll with {@link #takeSnapshot}. */
    public static void requestSnapshot(UUID entity) {
        snapshotRequests.add(entity);
        snapshots.remove(entity);
    }

    public static @Nullable Map<String, EntityPose.Limb> takeSnapshot(UUID entity) {
        return snapshots.remove(entity);
    }

    public static boolean snapshotPending(UUID entity) {
        return snapshotRequests.contains(entity);
    }

    /** The entity EntityRenderDispatcher is submitting: its state, renderer, origin and the camera it is seen from. */
    private static final class Submission {
        final Object state;
        final EntityRenderer<?, ?> renderer;
        final Matrix4f inverseOrigin;
        final Vec3 camera;
        boolean modelSeen;

        Submission(Object state, EntityRenderer<?, ?> renderer, Matrix4f inverseOrigin, Vec3 camera) {
            this.state = state;
            this.renderer = renderer;
            this.inverseOrigin = inverseOrigin;
            this.camera = camera;
        }
    }

    private static final Map<EntityRenderer<?, ?>, Model<?>> RENDERER_MODELS = new WeakHashMap<>();
    private static final Map<UUID, Matrix4f> MODEL_MATRICES = new HashMap<>();
    private static final Map<UUID, PartRig> DRAWN_RIGS = new HashMap<>();
    private static @Nullable Submission submission;

    /** {@code origin}: the pose at the entity's position (camera-relative), before its renderer adds anything. */
    public static void beginEntitySubmit(Object state, EntityRenderer<?, ?> renderer, Matrix4f origin, Vec3 camera) {
        submission = new Submission(state, renderer, origin.invert(new Matrix4f()), camera);
    }

    public static void endEntitySubmit() {
        submission = null;
    }

    /**
     * Every model submission. The first one made with the state being dispatched is that entity's own model,
     * whatever its renderer: it is remembered for the renderer, with its matrix relative to the entity's position,
     * and reported to the gizmo and pending snapshots.
     */
    public static void modelSubmitted(Model<?> model, Object state, Matrix4f pose) {
        Submission current = submission;
        if (current == null || current.state != state || current.modelSeen) return;
        current.modelSeen = true;
        RENDERER_MODELS.put(current.renderer, model);
        if (!(state instanceof Holder holder) || holder.vector3$poseEntity() == null || PreviewPass.isRendering()
                || IrisCompat.isRenderingShadowPass() || ScreenLayer.isRendering()) return;
        MODEL_MATRICES.put(holder.vector3$poseEntity(), current.inverseOrigin.mul(pose, new Matrix4f()));
        beforeSubmit(model, state, pose, current.camera);
    }

    /** How an entity is being dispatched, for a rig whose bones are only set up when the draw runs (GeckoLib's). */
    public record Draw(Matrix4f inverseOrigin, Vec3 camera, boolean main) {}

    /** The dispatch {@code state} is part of right now, or null if it is not the entity being submitted. */
    public static @Nullable Draw drawing(Object state) {
        Submission current = submission;
        if (current == null || current.state != state) return null;
        boolean main = !PreviewPass.isRendering() && !IrisCompat.isRenderingShadowPass() && !ScreenLayer.isRendering();
        return new Draw(current.inverseOrigin, current.camera, main);
    }

    /**
     * Called when {@code rig}'s bones are set up for a draw, with the matrix of the model that is drawn: applies the
     * entity's poses, and reports the rig to the gizmo, pending snapshots and part mounts.
     */
    public static void rigPosed(PartRig rig, Object state, @Nullable Draw draw, Matrix4f pose) {
        boolean main = draw != null && draw.main();
        afterSetupAnim(rig, state, main);
        if (!main || !(state instanceof Holder holder) || holder.vector3$poseEntity() == null) return;
        UUID entity = holder.vector3$poseEntity();
        DRAWN_RIGS.put(entity, rig);
        MODEL_MATRICES.put(entity, draw.inverseOrigin().mul(pose, new Matrix4f()));
        if (entity.equals(watched)) watchedFrames = rig.frames(new Matrix4f(pose), draw.camera());
    }

    /** The rig last drawn for the entity, if it is not a vanilla model. */
    static @Nullable PartRig drawnRig(UUID entity) {
        return DRAWN_RIGS.get(entity);
    }

    /** The model {@code renderer} submitted last, and its matrix relative to {@code entity} the last time it was drawn. */
    static @Nullable Model<?> seenModel(EntityRenderer<?, ?> renderer) {
        return RENDERER_MODELS.get(renderer);
    }

    static @Nullable Matrix4f seenMatrix(UUID entity) {
        return MODEL_MATRICES.get(entity);
    }

    /**
     * Called as an entity's model is submitted, with the model-space matrix it is submitted with. The model's parts
     * are only set up later, when the submit is drawn; for the gizmo's entity, or one with a snapshot pending, they
     * are set up here once more (as ModelFeatureRenderer will) to report where each part is.
     */
    private static void beforeSubmit(Model<?> model, Object state, Matrix4f pose, Vec3 camera) {
        UUID entity = state instanceof Holder holder ? holder.vector3$poseEntity() : null;
        boolean gizmo = entity != null && entity.equals(watched);
        // Not from the preview or shadow passes: their matrices aren't relative to the main camera.
        if (entity == null || PreviewPass.isRendering() || IrisCompat.isRenderingShadowPass() || ScreenLayer.isRendering()
                || !gizmo && !snapshotPending(entity)) return;
        setupAnim(model, state);
        afterSetupAnim(model, state, true);
        if (gizmo) watchedFrames = frames(model, new Matrix4f(pose), camera);
    }

    @SuppressWarnings("unchecked")
    private static <S> void setupAnim(Model<S> model, Object state) {
        model.setupAnim((S) state);
    }

    // Known up front for living entities and the dragon; any other renderer once it has drawn a model.
    private static @Nullable Model<?> model(EntityRenderer<?, ?> renderer) {
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> living) return living.getModel();
        if (renderer instanceof ModelOwner owner) return owner.vector3$poseModel();
        return seenModel(renderer);
    }

    /** The entity's model parts at rest (disabled), root first, or an empty map when it has no posable model. */
    public static Map<String, EntityPose.Limb> restPose(@Nullable Entity entity) {
        PartRig rig = rig(entity);
        return rig == null ? Map.of() : rig.rest();
    }

    /** How the entity's model parts hang from one another, or an empty tree when it has no posable model. */
    public static PartTree tree(@Nullable Entity entity) {
        PartRig rig = rig(entity);
        return rig == null ? PartTree.EMPTY : rig.tree();
    }

    static @Nullable PartRig rig(@Nullable Entity entity) {
        if (entity == null) return null;
        Model<?> model = model(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity));
        return model == null ? DRAWN_RIGS.get(entity.getUUID()) : rig(model);
    }

    /**
     * Each part's frame for the model as it is set up now, drawn with {@code entityMatrix} (model space to
     * camera-relative world, as LivingEntityRenderer submits it) from a camera at {@code camera}.
     */
    public static Map<String, Frame> frames(Model<?> model, Matrix4f entityMatrix, Vec3 camera) {
        return rig(model).frames(entityMatrix, camera);
    }

    public static Vec3 worldStep(Matrix4f matrix, float x, float y, float z) {
        Vector3f world = matrix.transformDirection(new Vector3f(x / 16f, y / 16f, z / 16f));
        return new Vec3(world.x, world.y, world.z);
    }

    public static Vec3 worldAxis(Matrix4f matrix, Vector3f local) {
        Vector3f world = matrix.transformDirection(local).normalize();
        return new Vec3(world.x, world.y, world.z);
    }

    // The tree of parts is worked out once per model.
    static PartRig rig(Model<?> model) {
        return RIGS.computeIfAbsent(model, VanillaPartRig::new);
    }
}
