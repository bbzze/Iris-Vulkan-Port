// This file is based on code from Sodium by JellySquid, licensed under the LGPLv3 license.

package net.irisshaders.iris.gl.shader;

/**
 * An enumeration over the supported shader types - Vulkan Port.
 *
 * GL constants inlined to remove LWJGL OpenGL dependency.
 * In Vulkan, these map to VkShaderStageFlagBits and shaderc ShaderKind.
 */
public enum ShaderType {
	VERTEX(0x8B31),               // GL_VERTEX_SHADER
	GEOMETRY(0x8DD9),             // GL_GEOMETRY_SHADER
	FRAGMENT(0x8B30),             // GL_FRAGMENT_SHADER
	COMPUTE(0x91B9),              // GL_COMPUTE_SHADER
	TESSELATION_CONTROL(0x8E88),  // GL_TESS_CONTROL_SHADER
	TESSELATION_EVAL(0x8E87);     // GL_TESS_EVALUATION_SHADER

	public final int id;

	ShaderType(int id) {
		this.id = id;
	}
}
