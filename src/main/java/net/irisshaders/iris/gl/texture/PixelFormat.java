package net.irisshaders.iris.gl.texture;

import net.irisshaders.iris.gl.GlVersion;

import java.util.Locale;
import java.util.Optional;

/**
 * Pixel format enum - Vulkan Port.
 *
 * GL constants inlined as integer values to remove LWJGL OpenGL dependency.
 * These format values are used as tracking IDs during the porting process
 * and will be mapped to VkFormat values in Phase 3.
 */
public enum PixelFormat {
	RED(0x1903, GlVersion.GL_11, false),           // GL_RED
	RG(0x8227, GlVersion.GL_30, false),            // GL_RG
	RGB(0x1907, GlVersion.GL_11, false),           // GL_RGB
	BGR(0x80E0, GlVersion.GL_12, false),           // GL_BGR
	RGBA(0x1908, GlVersion.GL_11, false),          // GL_RGBA
	BGRA(0x80E1, GlVersion.GL_12, false),          // GL_BGRA
	RED_INTEGER(0x8D94, GlVersion.GL_30, true),    // GL_RED_INTEGER
	RG_INTEGER(0x8228, GlVersion.GL_30, true),     // GL_RG_INTEGER
	RGB_INTEGER(0x8D98, GlVersion.GL_30, true),    // GL_RGB_INTEGER
	BGR_INTEGER(0x8D9A, GlVersion.GL_30, true),    // GL_BGR_INTEGER
	RGBA_INTEGER(0x8D99, GlVersion.GL_30, true),   // GL_RGBA_INTEGER
	BGRA_INTEGER(0x8D9B, GlVersion.GL_30, true);   // GL_BGRA_INTEGER

	private final int glFormat;
	private final GlVersion minimumGlVersion;
	private final boolean isInteger;

	PixelFormat(int glFormat, GlVersion minimumGlVersion, boolean isInteger) {
		this.glFormat = glFormat;
		this.minimumGlVersion = minimumGlVersion;
		this.isInteger = isInteger;
	}

	public static Optional<PixelFormat> fromString(String name) {
		try {
			return Optional.of(PixelFormat.valueOf(name.toUpperCase(Locale.US)));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public int getGlFormat() {
		return glFormat;
	}

	public GlVersion getMinimumGlVersion() {
		return minimumGlVersion;
	}

	public boolean isInteger() {
		return isInteger;
	}
}
