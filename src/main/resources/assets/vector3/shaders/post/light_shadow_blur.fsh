#version 330
uniform sampler2D InSampler;
uniform sampler2D DistanceSampler;
layout(std140) uniform ShadowBlur {
    vec4 Direction; // uv step of one shadow texel along the blur axis
};
in vec2 texCoord;
out vec4 fragColor;

bool usable(float z) {
    return z > 0.01 && z < 50000.0;
}

void main() {
    float z = texture(DistanceSampler, texCoord).r;
    if (!usable(z)) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    // 1/z is affine across a plane in screen space; the smaller one-sided difference is the slope that
    // is not corrupted by a depth discontinuity on the other side.
    float invZ = 1.0 / z;
    float ahead = texture(DistanceSampler, texCoord + Direction.xy).r;
    float behind = texture(DistanceSampler, texCoord - Direction.xy).r;
    float forwardStep = usable(ahead) ? 1.0 / ahead - invZ : 1e9;
    float backwardStep = usable(behind) ? invZ - 1.0 / behind : 1e9;
    float slope = abs(forwardStep) < abs(backwardStep) ? forwardStep : backwardStep;
    if (abs(slope) > 1e8) slope = 0.0;
    float tolerance = 0.03 * z + 0.05;
    float sum = 0.0;
    float total = 0.0;
    for (int i = -3; i <= 3; i++) {
        vec2 uv = texCoord + Direction.xy * float(i);
        float zq = texture(DistanceSampler, uv).r;
        if (!usable(zq)) continue;
        float expected = 1.0 / max(invZ + slope * float(i), 1e-5);
        float weight = (1.0 - abs(float(i)) * 0.25) * exp(-abs(zq - expected) / tolerance);
        sum += texture(InSampler, uv).r * weight;
        total += weight;
    }
    fragColor = vec4(total > 1e-5 ? sum / total : texture(InSampler, texCoord).r, 0.0, 0.0, 1.0);
}
