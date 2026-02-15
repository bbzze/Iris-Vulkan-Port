#version 460

layout(location = 0) in vec3 iris_Position;
layout(location = 1) in vec2 iris_UV0;

layout(std140, binding = 0) uniform UniformBlock {
    mat4 projection;
};

layout(location = 0) out vec2 uv;

void main() {
    gl_Position = projection * vec4(iris_Position, 1.0);
    uv = iris_UV0;
}
