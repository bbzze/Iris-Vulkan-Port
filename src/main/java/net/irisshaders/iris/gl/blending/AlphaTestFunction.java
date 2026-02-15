package net.irisshaders.iris.gl.blending;

import java.util.Optional;

/**
 * Alpha test functions - Vulkan Port.
 *
 * GL constants inlined to remove LWJGL OpenGL dependency.
 * In Vulkan, alpha test is handled via VkCompareOp in the pipeline state.
 */
public enum AlphaTestFunction {
	NEVER(0x0200, null),          // GL_NEVER
	LESS(0x0201, "<"),            // GL_LESS
	EQUAL(0x0202, "=="),          // GL_EQUAL
	LEQUAL(0x0203, "<="),         // GL_LEQUAL
	GREATER(0x0204, ">"),         // GL_GREATER
	NOTEQUAL(0x0205, "!="),       // GL_NOTEQUAL
	GEQUAL(0x0206, ">="),         // GL_GEQUAL
	ALWAYS(0x0207, null);         // GL_ALWAYS

	// GL constants inlined
	private static final int GL_NEVER = 0x0200;
	private static final int GL_LESS = 0x0201;
	private static final int GL_EQUAL = 0x0202;
	private static final int GL_LEQUAL = 0x0203;
	private static final int GL_GREATER = 0x0204;
	private static final int GL_NOTEQUAL = 0x0205;
	private static final int GL_GEQUAL = 0x0206;
	private static final int GL_ALWAYS = 0x0207;

	private final int glId;
	private final String expression;

	AlphaTestFunction(int glFormat, String expression) {
		this.glId = glFormat;
		this.expression = expression;
	}

	public static Optional<AlphaTestFunction> fromGlId(int glId) {
		return switch (glId) {
			case GL_NEVER -> Optional.of(NEVER);
			case GL_LESS -> Optional.of(LESS);
			case GL_EQUAL -> Optional.of(EQUAL);
			case GL_LEQUAL -> Optional.of(LEQUAL);
			case GL_GREATER -> Optional.of(GREATER);
			case GL_NOTEQUAL -> Optional.of(NOTEQUAL);
			case GL_GEQUAL -> Optional.of(GEQUAL);
			case GL_ALWAYS -> Optional.of(ALWAYS);
			default -> Optional.empty();
		};
	}

	public static Optional<AlphaTestFunction> fromString(String name) {
		if ("GL_ALWAYS".equals(name)) {
			// shaders.properties states that GL_ALWAYS is the name to use, but I haven't verified that this actually
			// matches the implementation... All of the other names do not have the GL_ prefix.
			//
			// We'll support it here just to be safe, even though just a plain ALWAYS seems more likely to be what it
			// parses.
			return Optional.of(AlphaTestFunction.ALWAYS);
		}

		try {
			return Optional.of(AlphaTestFunction.valueOf(name));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public int getGlId() {
		return glId;
	}

	public String getExpression() {
		return expression;
	}
}
