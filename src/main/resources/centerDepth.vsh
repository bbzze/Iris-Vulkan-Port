#version 460

layout(location = 0) in vec3 iris_Position;

layout(std140, binding = 0) uniform UniformBlock {
    mat4 projection;
    float lastFrameTime;
    float decay;
};

void main() {
    gl_Position = projection * vec4(iris_Position, 1.0);
}
