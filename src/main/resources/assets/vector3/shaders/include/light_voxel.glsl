// A GPU copy of the world's light-blocking blocks and of the entities near the camera. The including shader
// declares the LightSettings block first (VoxelOrigin, VoxelMin, VoxelWrap, VoxelDims); the layout mirrors
// core/light/VoxelLayout.java.
uniform usamplerBuffer VoxelTags;
uniform usamplerBuffer VoxelBits;
uniform samplerBuffer VoxelBoxes;

const float GLASS_PASS = 0.55;

// A view-space point as blocks from the corner of the voxel window.
vec3 voxelFromView(vec3 view) {
    return vec3(dot(WorldX.xyz, view), dot(WorldY.xyz, view), dot(WorldZ.xyz, view)) + VoxelOrigin.xyz;
}

bool voxelInside(vec3 p) {
    return all(greaterThanEqual(p, vec3(0.0))) && all(lessThan(p, vec3(VoxelDims.xyz) * 16.0));
}

// Flags of a section (1 present, 2 holds occluders, 4 holds see-through blocks); 0 when it is not in the copy.
uint voxelSection(ivec3 rel, out int slot) {
    ivec3 absolute = VoxelMin.xyz + rel;
    uint hash = uint(absolute.x) * 73856093u ^ uint(absolute.y) * 19349663u ^ uint(absolute.z) * 83492791u;
    ivec3 wrapped = (VoxelWrap.xyz + rel) % VoxelDims.xyz;
    slot = (wrapped.y * VoxelDims.z + wrapped.z) * VoxelDims.x + wrapped.x;
    uint tag = texelFetch(VoxelTags, slot).r;
    if ((tag >> 3u) != (hash >> 3u) || (tag & 1u) == 0u) return 0u;
    return tag & 7u;
}

// 0 for air, 1 for a see-through block, 2 for an opaque one.
int voxelCell(int slot, ivec3 local, bool hasGlass) {
    int bit = (local.y * 16 + local.z) * 16 + local.x;
    int base = slot * 256 + (bit >> 5);
    uint shift = uint(bit & 31);
    if (((texelFetch(VoxelBits, base).r >> shift) & 1u) != 0u) return 2;
    if (hasGlass && ((texelFetch(VoxelBits, base + 128).r >> shift) & 1u) != 0u) return 1;
    return 0;
}

// How much light gets along a ray (window-relative blocks) through the blocks: 0 when an opaque block is in the
// way, less than 1 behind glass. Empty and unknown sections are crossed in one step; `unknown` is set when the ray
// went through a section that is not in the copy yet.
float voxelTransmit(vec3 origin, vec3 direction, float span, int maxSteps, out bool unknown) {
    unknown = false;
    vec3 safe = direction + vec3(lessThan(abs(direction), vec3(1e-6))) * 1e-6;
    vec3 inverse_ = 1.0 / safe;
    vec3 forward = step(0.0, safe);
    vec3 limit = vec3(VoxelDims.xyz) * 16.0;
    float transmit = 1.0;
    float t = 0.0;
    for (int i = 0; i < maxSteps; i++) {
        if (t >= span) return transmit;
        vec3 p = origin + direction * t;
        if (any(lessThan(p, vec3(0.0))) || any(greaterThanEqual(p, limit))) {
            unknown = true;
            return transmit;
        }
        ivec3 cell = ivec3(floor(p));
        ivec3 rel = cell / 16;
        int slot;
        uint flags = voxelSection(rel, slot);
        vec3 base;
        float size;
        if ((flags & 2u) != 0u) {
            int content = voxelCell(slot, cell - rel * 16, (flags & 4u) != 0u);
            if (content == 2) return 0.0;
            if (content == 1) {
                transmit *= GLASS_PASS;
                if (transmit < 0.03) return 0.0;
            }
            base = vec3(cell);
            size = 1.0;
        } else {
            if (flags == 0u) unknown = true;
            base = vec3(rel * 16);
            size = 16.0;
        }
        vec3 exits = (base + forward * size - origin) * inverse_;
        t = min(min(exits.x, exits.y), exits.z) + 1e-3;
    }
    return transmit;
}

// Whether the segment from `origin` to `target` runs through an entity's box. A box that holds the origin well
// inside it is the entity's own surface, and one that holds the target is carrying the light, so those are skipped.
bool voxelEntityBlocked(vec3 origin, vec3 target) {
    vec3 path = target - origin;
    float span = length(path);
    vec3 direction = path / max(span, 1e-4);
    vec3 safe = direction + vec3(lessThan(abs(direction), vec3(1e-6))) * 1e-6;
    vec3 inverse_ = 1.0 / safe;
    for (int i = 0; i < VoxelDims.w; i++) {
        vec3 low = texelFetch(VoxelBoxes, i * 2).xyz;
        vec3 high = texelFetch(VoxelBoxes, i * 2 + 1).xyz;
        if (all(greaterThan(origin, low + 0.15)) && all(lessThan(origin, high - 0.15))) continue;
        if (all(greaterThan(target, low)) && all(lessThan(target, high))) continue;
        vec3 a = (low - origin) * inverse_;
        vec3 b = (high - origin) * inverse_;
        float enter = max(max(min(a.x, b.x), min(a.y, b.y)), min(a.z, b.z));
        float leave = min(min(max(a.x, b.x), max(a.y, b.y)), max(a.z, b.z));
        if (leave >= max(enter, 0.0) && enter <= span) return true;
    }
    return false;
}
