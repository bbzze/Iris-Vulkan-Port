package net.irisshaders.iris.gl.blending;

import net.irisshaders.iris.Iris;

import java.util.Optional;

/**
 * Blend mode functions - Vulkan Port.
 *
 * GL constants inlined to remove LWJGL OpenGL dependency.
 * In Vulkan, these map to VkBlendFactor values in VkPipelineColorBlendAttachmentState.
 */
public enum BlendModeFunction {
	ZERO(0),                         // GL_ZERO
	ONE(1),                          // GL_ONE
	SRC_COLOR(0x0300),               // GL_SRC_COLOR
	ONE_MINUS_SRC_COLOR(0x0301),     // GL_ONE_MINUS_SRC_COLOR
	DST_COLOR(0x0306),               // GL_DST_COLOR
	ONE_MINUS_DST_COLOR(0x0307),     // GL_ONE_MINUS_DST_COLOR
	SRC_ALPHA(0x0302),               // GL_SRC_ALPHA
	ONE_MINUS_SRC_ALPHA(0x0303),     // GL_ONE_MINUS_SRC_ALPHA
	DST_ALPHA(0x0304),               // GL_DST_ALPHA
	ONE_MINUS_DST_ALPHA(0x0305),     // GL_ONE_MINUS_DST_ALPHA
	SRC_ALPHA_SATURATE(0x0308);      // GL_SRC_ALPHA_SATURATE

	private final int glId;

	BlendModeFunction(int glFormat) {
		this.glId = glFormat;
	}

	public static Optional<BlendModeFunction> fromString(String name) {
		try {
			return Optional.of(BlendModeFunction.valueOf(name));
		} catch (IllegalArgumentException e) {
			Iris.logger.warn("Invalid blend mode! " + name);
			return Optional.empty();
		}
	}

	public int getGlId() {
		return glId;
	}
}
