#version 330
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
#moj_import <vector3:light_voxel.glsl>
in vec2 texCoord;
out vec4 fragColor;

const int ANCHORS = 12;

// An integer hash: sin() based hashes lose precision at pixel coordinates and show up as repeating patterns.
uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float hashNoise(vec3 cell) {
    uvec3 q = uvec3(ivec3(floor(cell)));
    return float(pcg(q.x ^ pcg(q.y ^ pcg(q.z)))) * (1.0 / 4294967295.0);
}

// Interleaved gradient noise: neighbouring pixels differ evenly, so the blur afterwards removes it well.
float pixelNoise(vec2 offset) {
    return fract(52.9829189 * fract(dot(gl_FragCoord.xy + offset, vec2(0.06711056, 0.00583715))));
}

float fogNoise(vec3 position) {
    vec3 cell = floor(position);
    vec3 fraction = fract(position);
    fraction = fraction * fraction * (3.0 - 2.0 * fraction);
    float x00 = mix(hashNoise(cell), hashNoise(cell + vec3(1.0, 0.0, 0.0)), fraction.x);
    float x10 = mix(hashNoise(cell + vec3(0.0, 1.0, 0.0)), hashNoise(cell + vec3(1.0, 1.0, 0.0)), fraction.x);
    float x01 = mix(hashNoise(cell + vec3(0.0, 0.0, 1.0)), hashNoise(cell + vec3(1.0, 0.0, 1.0)), fraction.x);
    float x11 = mix(hashNoise(cell + vec3(0.0, 1.0, 1.0)), hashNoise(cell + vec3(1.0, 1.0, 1.0)), fraction.x);
    return mix(mix(x00, x10, fraction.y), mix(x01, x11, fraction.y), fraction.z);
}

bool areaInterval(vec3 ray, vec3 center, float radius, out float start, out float end) {
    vec3 origin = vec3(dot(-center, AreaRight.xyz), dot(-center, AreaUp.xyz),
            dot(-center, LightDirectionType.xyz));
    vec3 direction = vec3(dot(ray, AreaRight.xyz), dot(ray, AreaUp.xyz),
            dot(ray, LightDirectionType.xyz));
    vec3 lower = vec3(-AreaCone.x * 0.5 - radius, -AreaCone.y * 0.5 - radius, 0.0);
    vec3 upper = vec3(AreaCone.x * 0.5 + radius, AreaCone.y * 0.5 + radius, AreaRight.w);
    start = 0.0;
    end = 50000.0;
    for (int axis = 0; axis < 3; axis++) {
        if (abs(direction[axis]) < 1e-5) {
            if (origin[axis] < lower[axis] || origin[axis] > upper[axis]) return false;
        } else {
            float a = (lower[axis] - origin[axis]) / direction[axis];
            float b = (upper[axis] - origin[axis]) / direction[axis];
            start = max(start, min(a, b));
            end = min(end, max(a, b));
        }
    }
    return end > start;
}

float screenOcclusion(vec3 point, vec3 emitter, float phase) {
    if (VoxelOrigin.w > 0.5) {
        vec3 from = voxelFromView(point);
        vec3 to = voxelFromView(emitter);
        if (voxelInside(from) && voxelInside(to)) {
            vec3 path = to - from;
            float span = length(path);
            bool unknown;
            float transmit = voxelTransmit(from, path / max(span, 1e-4), max(span - 0.5, 0.0), 96, unknown);
            if (voxelEntityBlocked(from, to)) transmit = 0.0;
            if (transmit < 0.01 || !unknown) return 1.0 - transmit;
        }
    }
    vec2 uvJitter = (vec2(phase, fract(phase * 1.618)) - 0.5) * Projection.zw * 1.5;
    float coverage = 0.0;
    for (int i = 0; i < 8; i++) {
        vec3 probe = mix(point, emitter, (float(i) + phase) / 8.0);
        if (probe.z >= -0.05) continue;
        vec4 clip = ProjectionMatrix * vec4(probe, 1.0);
        if (clip.w <= 0.0) continue;
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5 + uvJitter;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) continue;
        float observed = texture(DistanceSampler, uv).r;
        if (observed < 0.01 || observed >= 50000.0) continue;
        float bias = max(0.12, -probe.z * 0.004);
        float separation = -probe.z - observed;
        coverage += smoothstep(bias, bias + max(0.15, -probe.z * 0.01), separation);
    }
    return 1.0 - exp(-coverage * 1.3);
}

void main() {
    vec4 rayPoint = InverseProjectionMatrix * vec4(texCoord * 2.0 - 1.0, 0.5, 1.0);
    vec3 ray = normalize(rayPoint.xyz / rayPoint.w);
    vec3 center = LightPositionRadius.xyz;
    float radius = LightPositionRadius.w;
    float start, end;
    if (LightDirectionType.w > 0.5 && LightDirectionType.w < 1.5) {
        if (!areaInterval(ray, center, radius, start, end)) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
    } else {
        float bounds = radius;
        vec3 boundsCenter = center;
        if (LightDirectionType.w > 1.5) {
            float tangent = sqrt(max(1.0 - AreaCone.w * AreaCone.w, 0.0)) / max(AreaCone.w, 0.017);
            boundsCenter += LightDirectionType.xyz * radius * 0.5;
            bounds = length(vec2(radius * 0.5, radius * tangent));
        }
        float projected = dot(ray, boundsCenter);
        float discriminant = projected * projected - (dot(boundsCenter, boundsCenter) - bounds * bounds);
        if (discriminant <= 0.0) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        float reach = sqrt(discriminant);
        start = max(0.0, projected - reach);
        end = projected + reach;
    }
    float depth = texture(DistanceSampler, texCoord).r;
    end = min(end, depth / max(-ray.z, 1e-4));
    if (end <= start) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    int steps = min(64, (LightDirectionType.w > 0.5 ? 48 : 24)
            + int(clamp(Effects.w / 8.0, 0.0, 16.0)));
    float stepLength = (end - start) / float(steps);
    bool uniformPieces = LightDirectionType.w > 0.5 && LightDirectionType.w < 1.5;
    // Point and spot light fall off with the distance to the light, so the ray is cut into pieces of equal angle
    // as seen from the light: they are short where the ray passes close to it and long far away.
    float lineDistance = max(length(ray * dot(ray, center) - center), 0.05);
    float angleStart = atan((start - dot(ray, center)) / lineDistance);
    float angleEnd = atan((end - dot(ray, center)) / lineDistance);
    float scattering = 0.0;
    float jitter = pixelNoise(vec2(0.0));
    // Shadow rays are cast at a few depths along the view ray. Each pixel puts them at slightly different depths
    // and aims them at a different point of the light, so the silhouettes of the occluders at the different
    // depths do not line up into visible layers but average out into one smooth shadow.
    vec3 pixelBase = vec3(pixelNoise(vec2(0.0)), pixelNoise(vec2(37.0, 11.0)), pixelNoise(vec2(5.0, 71.0)));
    float occlusionAnchors[ANCHORS];
    float softness = LightDirectionType.w > 1.5 ? clamp(radius * 0.02, 0.025, 0.18)
            : clamp(radius * 0.04, 0.06, 0.4);
    for (int anchor = 0; anchor < ANCHORS; anchor++) {
        occlusionAnchors[anchor] = 0.0;
        if (Effects.y <= 0.001) continue;
        float seed = float(anchor);
        // Neighbouring pixels get evenly spread values, shifted along a golden-ratio sequence for each ray, so the
        // blur that follows averages them into a smooth shadow instead of leaving random grain.
        vec3 noise = fract(pixelBase + seed * vec3(0.7548776662, 0.5698402910, 0.6180339887));
        vec3 samplePoint = ray * mix(start, end, (seed + 0.15 + 0.7 * noise.x) / float(ANCHORS));
        vec3 emitter = center;
        if (LightDirectionType.w > 0.5 && LightDirectionType.w < 1.5) {
            emitter += AreaRight.xyz * (AreaCone.x * 0.45 * (noise.y * 2.0 - 1.0))
                    + AreaUp.xyz * (AreaCone.y * 0.45 * (noise.z * 2.0 - 1.0));
        } else {
            vec3 axis = samplePoint - center;
            axis = dot(axis, axis) > 1e-8 ? normalize(axis) : vec3(0.0, 0.0, 1.0);
            vec3 first = normalize(cross(axis, abs(axis.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
            vec3 second = cross(axis, first);
            float angle = 6.2831853 * noise.z;
            emitter += (first * cos(angle) + second * sin(angle)) * (softness * sqrt(noise.y));
        }
        occlusionAnchors[anchor] = screenOcclusion(samplePoint, emitter, noise.y);
    }
    for (int i = 0; i < 64; i++) {
        if (i >= steps) break;
        float along = (float(i) + jitter) / float(steps);
        float distanceAlong = start + along * (end - start);
        float piece = stepLength;
        if (!uniformPieces) {
            float angle = mix(angleStart, angleEnd, along);
            float offset = lineDistance * tan(angle);
            distanceAlong = dot(ray, center) + offset;
            float secant = 1.0 + tan(angle) * tan(angle);
            piece = lineDistance * secant * (angleEnd - angleStart) / float(steps);
        }
        vec3 point = ray * distanceAlong;
        vec3 emitter = center;
        float shape = 1.0;
        float radial;
        if (LightDirectionType.w > 0.5 && LightDirectionType.w < 1.5) {
            vec3 displacement = point - center;
            emitter += AreaRight.xyz * clamp(dot(displacement, AreaRight.xyz),
                    -AreaCone.x * 0.5, AreaCone.x * 0.5);
            emitter += AreaUp.xyz * clamp(dot(displacement, AreaUp.xyz),
                    -AreaCone.y * 0.5, AreaCone.y * 0.5);
            float reach = max(AreaRight.w, 0.1);
            float axial = dot(LightDirectionType.xyz, displacement);
            float spread = radius * clamp(axial / reach, 0.0, 1.0);
            float halfWidth = AreaCone.x * 0.5 + spread;
            float halfHeight = AreaCone.y * 0.5 + spread;
            float feather = max(0.08, spread * 0.12);
            shape = (1.0 - smoothstep(halfWidth - feather, halfWidth + feather,
                    abs(dot(displacement, AreaRight.xyz))))
                    * (1.0 - smoothstep(halfHeight - feather, halfHeight + feather,
                    abs(dot(displacement, AreaUp.xyz))));
            shape *= smoothstep(0.0, max(0.1, reach * 0.025), axial)
                    * (1.0 - smoothstep(reach * 0.85, reach, axial));
            radial = 1.0 / (1.0 + 2.0 * (axial / reach) * (axial / reach));
        } else if (LightDirectionType.w > 1.5) {
            vec3 toPoint = point - center;
            float axial = dot(LightDirectionType.xyz, toPoint);
            float lateral = length(toPoint - LightDirectionType.xyz * axial);
            float outerTangent = sqrt(max(1.0 - AreaCone.w * AreaCone.w, 0.0))
                    / max(AreaCone.w, 0.017);
            float innerTangent = sqrt(max(1.0 - AreaCone.z * AreaCone.z, 0.0))
                    / max(AreaCone.z, 0.017);
            float outerWidth = max(axial, 0.0) * outerTangent;
            float innerWidth = max(axial, 0.0) * innerTangent;
            float feather = max(outerWidth - innerWidth, 0.01);
            shape = 1.0 - smoothstep(outerWidth - feather, outerWidth, lateral);
            shape *= smoothstep(0.0, max(radius * 0.025, 0.05), axial);
            shape *= 1.0 - smoothstep(radius * 0.88, radius, axial);
            radial = 1.0 / (1.0 + 2.0 * (axial / radius) * (axial / radius));
        } else {
            radial = max(0.0, 1.0 - length(point - center) / radius);
        }
        if (shape <= 0.0 || radial <= 0.0) continue;
        vec3 displacement = point - center;
        vec3 local = vec3(dot(displacement, AreaRight.xyz),
                dot(displacement, AreaUp.xyz), dot(displacement, LightDirectionType.xyz));
        float variation = 0.85 + 0.3 * fogNoise(local * 2.0);
        float fraction = clamp((distanceAlong - start) / max(end - start, 1e-4), 0.0, 1.0);
        float anchorPosition = clamp(fraction * float(ANCHORS) - 0.5, 0.0, float(ANCHORS - 1));
        int anchor = min(int(floor(anchorPosition)), ANCHORS - 2);
        float occlusion = mix(occlusionAnchors[anchor], occlusionAnchors[anchor + 1],
                anchorPosition - float(anchor));
        float visibility = 1.0 - Effects.y * occlusion;
        scattering += radial * radial * shape * variation * visibility * piece;
    }
    float lengthScale = LightDirectionType.w > 0.5 && LightDirectionType.w < 1.5
            ? max(AreaRight.w, 0.1) : max(radius, 0.1);
    scattering /= lengthScale;
    fragColor = vec4(LightColorIntensity.rgb * LightColorIntensity.a * Effects.x
            * scattering * 0.55 * (1.0 + Effects.z * 0.3), 1.0);
}
