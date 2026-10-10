#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D DistanceSampler;
layout(std140) uniform LightSettings {
    vec4 LightPositionRadius;
    vec4 LightColorIntensity;
    vec4 Projection;
    vec4 Effects;
    mat4 ProjectionMatrix;
    mat4 InverseProjectionMatrix;
    vec4 LightDirectionType;
    vec4 AreaCone;
    vec4 AreaRight;
    vec4 AreaUp;
    vec4 WorldX;
    vec4 WorldY;
    vec4 WorldZ;
    vec4 VoxelOrigin;
    ivec4 VoxelMin;
    ivec4 VoxelWrap;
    ivec4 VoxelDims;
};
#include <vector3:light_voxel.glsl>
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int ORDER[16] = int[16](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
const int STEPS = 24;

vec3 viewPosition(vec2 uv, float depth) {
    vec4 point = InverseProjectionMatrix * vec4(uv * 2.0 - 1.0, 0.5, 1.0);
    vec3 ray = point.xyz / point.w;
    return ray * (depth / -ray.z);
}

// World size of one pixel at a view depth.
float footprint(float z) {
    return abs(z) * 2.0 * Projection.w / max(Projection.y, 1e-4);
}

// Block faces are exactly axis aligned, so a normal that is nearly so is snapped onto the axis.
vec3 snapToBlockAxes(vec3 n) {
    float dx = dot(n, WorldX.xyz);
    float dy = dot(n, WorldY.xyz);
    float dz = dot(n, WorldZ.xyz);
    if (abs(dx) > 0.985) return WorldX.xyz * sign(dx);
    if (abs(dy) > 0.985) return WorldY.xyz * sign(dy);
    if (abs(dz) > 0.985) return WorldZ.xyz * sign(dz);
    return n;
}

vec3 surfaceNormal(vec3 surface, float depth) {
    ivec2 size = textureSize(DistanceSampler, 0);
    ivec2 pixel = clamp(ivec2(texCoord * vec2(size)), ivec2(0), size - 1);
    ivec2 left = max(pixel - ivec2(1, 0), ivec2(0));
    ivec2 right = min(pixel + ivec2(1, 0), size - 1);
    ivec2 down = max(pixel - ivec2(0, 1), ivec2(0));
    ivec2 up = min(pixel + ivec2(0, 1), size - 1);
    float dl = texelFetch(DistanceSampler, left, 0).r;
    float dr = texelFetch(DistanceSampler, right, 0).r;
    float dd = texelFetch(DistanceSampler, down, 0).r;
    float du = texelFetch(DistanceSampler, up, 0).r;
    float el = dl > 0.01 && dl < 50000.0 ? abs(dl - depth) : 1e20;
    float er = dr > 0.01 && dr < 50000.0 ? abs(dr - depth) : 1e20;
    float ed = dd > 0.01 && dd < 50000.0 ? abs(dd - depth) : 1e20;
    float eu = du > 0.01 && du < 50000.0 ? abs(du - depth) : 1e20;
    vec3 facing = normalize(-surface);
    if (min(el, er) > depth * 0.08 || min(ed, eu) > depth * 0.08)
        return facing;
    vec3 dx = er <= el
            ? viewPosition((vec2(right) + 0.5) / vec2(size), dr) - surface
            : surface - viewPosition((vec2(left) + 0.5) / vec2(size), dl);
    vec3 dy = eu <= ed
            ? viewPosition((vec2(up) + 0.5) / vec2(size), du) - surface
            : surface - viewPosition((vec2(down) + 0.5) / vec2(size), dd);
    vec3 normal = cross(dx, dy);
    if (dot(normal, normal) < 1e-12) return facing;
    normal = normalize(normal);
    if (dot(normal, facing) < 0.0) normal = -normal;
    normal = snapToBlockAxes(normal);
    float edge = max(min(el, er), min(ed, eu)) / max(depth, 0.25);
    float confidence = 1.0 - smoothstep(0.015, 0.08, edge);
    return normalize(mix(facing, normal, confidence));
}

// How much of the ray from start to target is hidden behind what the screen shows.
float blocked(vec3 start, vec3 target, float jitter, float slope) {
    vec3 ray = target - start;
    float span = max(length(ray), 1e-4);
    // The first few pixels are the surface itself, so the march begins a little way out.
    float begin = min(3.0 * footprint(start.z), span * 0.5) / span;
    float occlusion = 0.0;
    for (int i = 0; i < STEPS; i++) {
        float t = begin + (1.0 - begin) * (float(i) + jitter) / float(STEPS);
        vec3 point = start + ray * t;
        if (point.z >= -0.01) break;
        vec4 clip = ProjectionMatrix * vec4(point, 1.0);
        if (clip.w <= 0.0) continue;
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) continue;
        float observed = texture(DistanceSampler, uv).r;
        if (observed > 0.01 && observed < 50000.0) {
            float bias = 0.02 + 2.0 * footprint(point.z) / slope;
            float thickness = max(0.1, -point.z * 0.01);
            occlusion = max(occlusion, smoothstep(bias, bias + thickness, -point.z - observed));
            if (occlusion >= 0.999) break;
        }
    }
    return occlusion;
}

void main() {
    float depth = texture(DistanceSampler, texCoord).r;
    if (depth <= 0.01 || depth >= 50000.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 surface = viewPosition(texCoord, depth);
    float reach = LightPositionRadius.w * 2.5;
    if (LightDirectionType.w > 0.5 && LightDirectionType.w < 1.5)
        reach = length(vec2(AreaRight.w, reach + length(AreaCone.xy) * 0.5));
    if (length(LightPositionRadius.xyz - surface) > reach) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 normal = surfaceNormal(surface, depth);
    float slope = max(abs(dot(normal, normalize(-surface))), 0.15);
    vec3 start = surface + normal * (0.03 + 0.002 * depth);

    // One ray per pixel to one point of the light; the 16 pixels of a 4x4 tile take 16 different points and
    // different march offsets, and the blur that follows averages them back into one soft shadow.
    ivec2 tile = ivec2(gl_FragCoord.xy) & 3;
    int order = ORDER[tile.x + tile.y * 4];
    float jitter = fract(float(order) * 0.618034 + 0.25);
    vec3 center = LightPositionRadius.xyz;
    vec3 target;
    if (LightDirectionType.w > 0.5 && LightDirectionType.w < 1.5) {
        vec2 cell = (vec2(order & 3, order >> 2) + 0.5) / 4.0 * 2.0 - 1.0;
        target = center + AreaRight.xyz * (AreaCone.x * 0.38 * cell.x) + AreaUp.xyz * (AreaCone.y * 0.38 * cell.y);
    } else {
        float spread = LightDirectionType.w > 1.5
                ? clamp(LightPositionRadius.w * 0.02, 0.025, 0.18)
                : clamp(LightPositionRadius.w * 0.04, 0.06, 0.4);
        // A disc facing the surface, filled by a golden-angle spiral.
        vec3 axis = normalize(surface - center);
        vec3 first = normalize(cross(axis, abs(axis.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 second = cross(axis, first);
        float radius = sqrt((float(order) + 0.5) / 16.0);
        float angle = float(order) * 2.39996323;
        target = center + (first * cos(angle) + second * sin(angle)) * (radius * spread);
    }
    float known = 0.0;
    if (VoxelOrigin.w > 0.5) {
        vec3 from = voxelFromView(start);
        vec3 to = voxelFromView(target);
        if (voxelInside(from) && voxelInside(to)) {
            vec3 path = to - from;
            float length_ = length(path);
            bool unknown;
            // The last half block is left out, so a light set into a block is not shut in by it.
            float transmit = voxelTransmit(from, path / max(length_, 1e-4), max(length_ - 0.5, 0.0), 160, unknown);
            if (voxelEntityBlocked(from, to)) transmit = 0.0;
            known = 1.0 - transmit;
            // The voxel copy decides alone when it knew every section on the way; only a path through sections
            // that are not copied yet (or out of the window) is left to what the screen shows.
            if (known > 0.99 || !unknown) {
                fragColor = vec4(known, 0.0, 0.0, 1.0);
                return;
            }
        }
    }
    fragColor = vec4(max(known, blocked(start, target, jitter, slope)), 0.0, 0.0, 1.0);
}
