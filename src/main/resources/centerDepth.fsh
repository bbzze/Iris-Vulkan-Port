#version 460

layout(binding = 1) uniform sampler2D depth;
layout(binding = 2) uniform sampler2D altDepth;

layout(std140, binding = 0) uniform UniformBlock {
    mat4 projection;
    float lastFrameTime;
    float decay;
};

layout(location = 0) out float iris_fragColor;

void main() {
    float currentDepth = texture(depth, vec2(0.5)).r;
    float decay2 = 1.0 - exp(-decay * lastFrameTime);
    float oldDepth = texture(altDepth, vec2(0.5)).r;

    if (isnan(oldDepth)) {
       oldDepth = currentDepth;
    }

    iris_fragColor = mix(oldDepth, currentDepth, decay2);
}
