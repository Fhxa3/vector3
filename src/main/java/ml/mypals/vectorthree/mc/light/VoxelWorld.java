package ml.mypals.vectorthree.mc.light;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import ml.mypals.vectorthree.core.light.VoxelLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A copy of the world's occluding blocks on the GPU, for light shadows that do not depend on what the screen shows.
 * It covers a window of sections around the camera; sections are scanned nearest first within a small time budget,
 * and rescanned when a block in them changes or their chunk is loaded or unloaded.
 */
public final class VoxelWorld {
    private static final long BUDGET_NANOS = 2_500_000L;
    private static final long NONE = Long.MIN_VALUE;
    private static final int[][] ORDER = windowOrder();
    private static final Set<Long> DIRTY_SECTIONS = new HashSet<>();
    private static final Set<Long> DIRTY_COLUMNS = new HashSet<>();

    private static ClientLevel world;
    private static GpuBuffer tagBuffer, bitBuffer, boxBuffer;
    private static int boxCount;
    private static long[] slotKey;
    private static int[] tags, bits;
    private static ByteBuffer scratch;
    private static boolean tagsDirty;
    private static int minX, minY, minZ;
    private static boolean ready;

    private VoxelWorld() {}

    private static final byte NOTHING = 0, SOLID = 1, GLASS = 2;
    private static final Map<BlockState, Byte> KINDS = new IdentityHashMap<>();

    /** What a block does to light: nothing, stops it, or passes it dimmed (glass, ice and other see-through cubes). */
    static byte kind(BlockState state) {
        Byte known = KINDS.get(state);
        if (known != null) return known;
        byte kind = NOTHING;
        if (!state.isAir()) {
            if (state.isSolidRender() || state.is(BlockTags.LEAVES)) kind = SOLID;
            else if (state.getRenderShape() != RenderShape.INVISIBLE
                    && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) kind = GLASS;
        }
        KINDS.put(state, kind);
        return kind;
    }

    public static boolean occluder(BlockState state) {
        return kind(state) != NOTHING;
    }

    public static void blockChanged(BlockPos pos, BlockState before, BlockState after) {
        if (world == null || kind(before) == kind(after)) return;
        DIRTY_SECTIONS.add(VoxelLayout.key(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
    }

    public static void columnChanged(int chunkX, int chunkZ) {
        if (world == null) return;
        DIRTY_COLUMNS.add(VoxelLayout.key(chunkX, 0, chunkZ));
    }

    /** Whether a world position is inside the copied window. */
    public static boolean contains(Vec3 position) {
        double x = position.x - minX * 16.0, y = position.y - minY * 16.0, z = position.z - minZ * 16.0;
        return x >= 0 && y >= 0 && z >= 0 && x < VoxelLayout.GX * 16 && y < VoxelLayout.GY * 16
                && z < VoxelLayout.GZ * 16;
    }

    public static GpuBuffer tags() { return tagBuffer; }
    public static GpuBuffer bits() { return bitBuffer; }
    public static GpuBuffer boxes() { return boxBuffer; }
    public static int boxCount() { return ready ? boxCount : 0; }

    /** Window corner and wrap offsets for the shader; a camera at {@code camera}, in blocks from the corner. */
    public static boolean ready() { return ready; }
    public static float originX(Vec3 camera) { return (float) (camera.x - minX * 16.0); }
    public static float originY(Vec3 camera) { return (float) (camera.y - minY * 16.0); }
    public static float originZ(Vec3 camera) { return (float) (camera.z - minZ * 16.0); }
    public static int minX() { return minX; }
    public static int minY() { return minY; }
    public static int minZ() { return minZ; }

    public static void clear() {
        world = null;
        ready = false;
        DIRTY_SECTIONS.clear();
        DIRTY_COLUMNS.clear();
        if (tagBuffer != null) tagBuffer.close();
        if (bitBuffer != null) bitBuffer.close();
        if (boxBuffer != null) boxBuffer.close();
        tagBuffer = null;
        bitBuffer = null;
        boxBuffer = null;
        boxCount = 0;
        slotKey = null;
        tags = null;
        bits = null;
        if (scratch != null) MemoryUtil.memFree(scratch);
        scratch = null;
    }

    /**
     * Brings the copy up to date around the camera, when {@code scan} says it is needed; false when it is not
     * usable. The buffers exist either way, so they can always be bound.
     */
    public static boolean update(ClientLevel level, Vec3 camera, boolean scan, Entity hidden) {
        if (level != null && world != level) {
            clear();
            world = level;
        }
        ensureBuffers();
        if (level == null || !scan) {
            ready = false;
            return false;
        }
        int cameraX = (int) Math.floor(camera.x) >> 4, cameraY = (int) Math.floor(camera.y) >> 4,
                cameraZ = (int) Math.floor(camera.z) >> 4;
        minX = cameraX - VoxelLayout.GX / 2;
        minY = cameraY - VoxelLayout.GY / 2;
        minZ = cameraZ - VoxelLayout.GZ / 2;
        applyDirty();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int built = 0;
        for (int[] offset : ORDER) {
            int sx = minX + offset[0], sy = minY + offset[1], sz = minZ + offset[2];
            int slot = VoxelLayout.slot(sx, sy, sz);
            long key = VoxelLayout.key(sx, sy, sz);
            if (slotKey[slot] == key) continue;
            scan(level, sx, sy, sz, slot);
            slotKey[slot] = key;
            if ((++built & 3) == 0 && System.nanoTime() > deadline) break;
        }
        if (tagsDirty) {
            upload(tagBuffer, tags, 0, tags.length);
            tagsDirty = false;
        }
        collectBoxes(level, camera, hidden);
        ready = true;
        return true;
    }

    private static void ensureBuffers() {
        if (tagBuffer != null) return;
        slotKey = new long[VoxelLayout.SLOTS];
        Arrays.fill(slotKey, NONE);
        tags = new int[VoxelLayout.SLOTS];
        bits = new int[VoxelLayout.SLOTS * VoxelLayout.WORDS];
        scratch = MemoryUtil.memAlloc(VoxelLayout.WORDS * 4).order(ByteOrder.nativeOrder());
        tagBuffer = RenderSystem.getDevice().createBuffer(() -> "vector3_voxel_tags",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, VoxelLayout.SLOTS * 4);
        bitBuffer = RenderSystem.getDevice().createBuffer(() -> "vector3_voxel_bits",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                VoxelLayout.SLOTS * VoxelLayout.WORDS * 4);
        boxBuffer = RenderSystem.getDevice().createBuffer(() -> "vector3_voxel_boxes",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, VoxelLayout.MAX_BOXES * 32);
        tagsDirty = true;
    }

    // A changed section or column is only marked stale; the nearest-first pass rebuilds it, keeping the old data meanwhile.
    private static void applyDirty() {
        if (!DIRTY_SECTIONS.isEmpty()) {
            for (long key : DIRTY_SECTIONS) {
                for (int slot = 0; slot < slotKey.length; slot++) if (slotKey[slot] == key) slotKey[slot] = NONE;
            }
            DIRTY_SECTIONS.clear();
        }
        if (!DIRTY_COLUMNS.isEmpty()) {
            for (long column : DIRTY_COLUMNS) {
                int cx = (int) (column << 38 >> 38), cz = (int) (column >> 26 << 38 >> 38);
                for (int sy = minY; sy < minY + VoxelLayout.GY; sy++) {
                    int slot = VoxelLayout.slot(cx, sy, cz);
                    if (slotKey[slot] == VoxelLayout.key(cx, sy, cz)) slotKey[slot] = NONE;
                }
            }
            DIRTY_COLUMNS.clear();
        }
    }

    private static void scan(ClientLevel level, int sx, int sy, int sz, int slot) {
        int base = slot * VoxelLayout.WORDS;
        int flags = 0;
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        int index = level.getSectionIndexFromSectionY(sy);
        if (chunk != null) {
            flags = VoxelLayout.PRESENT;
            if (index >= 0 && index < level.getSectionsCount()) {
                LevelChunkSection section = chunk.getSection(index);
                if (!section.hasOnlyAir() && section.maybeHas(VoxelWorld::occluder)) {
                    Arrays.fill(bits, base, base + VoxelLayout.WORDS, 0);
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                byte kind = kind(section.getBlockState(x, y, z));
                                if (kind == SOLID) {
                                    VoxelLayout.set(bits, base, x, y, z);
                                    flags |= VoxelLayout.FILLED;
                                } else if (kind == GLASS) {
                                    VoxelLayout.setGlass(bits, base, x, y, z);
                                    flags |= VoxelLayout.FILLED | VoxelLayout.GLASS;
                                }
                            }
                    if ((flags & VoxelLayout.FILLED) != 0) upload(bitBuffer, bits, base, VoxelLayout.WORDS);
                }
            }
        }
        int tag = chunk == null ? 0 : VoxelLayout.tag(sx, sy, sz, flags);
        if (tags[slot] != tag) {
            tags[slot] = tag;
            tagsDirty = true;
        }
    }

    // Living things and vehicles near the camera as boxes, in blocks from the window corner; they move every frame.
    private static void collectBoxes(ClientLevel level, Vec3 camera, Entity hidden) {
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        List<Entity> found = new ArrayList<>();
        double limit = VoxelLayout.GX * 8.0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == hidden || entity.isRemoved() || entity.isInvisible() || entity.isSpectator()) continue;
            if (!(entity instanceof LivingEntity) && !(entity instanceof VehicleEntity)) continue;
            AABB box = entity.getBoundingBox();
            if (box.getXsize() > 6 || box.getYsize() > 6 || box.getZsize() > 6) continue;
            if (box.getXsize() < 0.2 || box.getYsize() < 0.2 || box.getZsize() < 0.2) continue;
            if (entity.distanceToSqr(camera) > limit * limit) continue;
            found.add(entity);
        }
        found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(camera)));
        boxCount = Math.min(found.size(), VoxelLayout.MAX_BOXES);
        if (boxCount == 0) return;
        ByteBuffer data = MemoryUtil.memAlloc(boxCount * 32).order(ByteOrder.nativeOrder());
        try {
            for (int i = 0; i < boxCount; i++) {
                Entity entity = found.get(i);
                Vec3 shift = entity.getPosition(partial).subtract(entity.position());
                AABB box = entity.getBoundingBox().move(shift);
                data.putFloat((float) (box.minX - minX * 16.0)).putFloat((float) (box.minY - minY * 16.0))
                        .putFloat((float) (box.minZ - minZ * 16.0)).putFloat(0);
                data.putFloat((float) (box.maxX - minX * 16.0)).putFloat((float) (box.maxY - minY * 16.0))
                        .putFloat((float) (box.maxZ - minZ * 16.0)).putFloat(0);
            }
            data.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(boxBuffer.slice(0, boxCount * 32L), data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void upload(GpuBuffer buffer, int[] source, int offset, int count) {
        ByteBuffer data = count == VoxelLayout.WORDS ? scratch : MemoryUtil.memAlloc(count * 4);
        try {
            data.clear();
            data.order(ByteOrder.nativeOrder()).asIntBuffer().put(source, offset, count);
            data.limit(count * 4);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                    buffer.slice((long) offset * 4, (long) count * 4), data);
        } finally {
            if (data != scratch) MemoryUtil.memFree(data);
        }
    }

    // Window offsets, nearest to the window's center first.
    private static int[][] windowOrder() {
        List<int[]> offsets = new ArrayList<>();
        for (int y = 0; y < VoxelLayout.GY; y++)
            for (int z = 0; z < VoxelLayout.GZ; z++)
                for (int x = 0; x < VoxelLayout.GX; x++) offsets.add(new int[]{x, y, z});
        int cx = VoxelLayout.GX / 2, cy = VoxelLayout.GY / 2, cz = VoxelLayout.GZ / 2;
        offsets.sort(Comparator.comparingInt(o -> (o[0] - cx) * (o[0] - cx) + (o[1] - cy) * (o[1] - cy) * 4
                + (o[2] - cz) * (o[2] - cz)));
        return offsets.toArray(new int[0][]);
    }
}
