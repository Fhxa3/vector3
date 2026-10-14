#version 330
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

// One texel of the visibility map: the way out of the light in this direction, traced through the voxel copy.
void main() {
    vec3 origin = voxelFromView(LightPositionRadius.xyz);
    vec3 direction = octDecode(texCoord);
    float range = LightPositionRadius.w * 1.8;
    float glassStart, glassTransmit;
    float hit = voxelDistance(origin, direction, range, glassStart, glassTransmit);
    hit = min(hit, voxelEntityDistance(origin, direction, hit));
    fragColor = vec4(hit, min(glassStart, hit), glassTransmit, 1.0);
}
