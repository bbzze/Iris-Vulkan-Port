package net.irisshaders.iris.gl.texture;

import net.irisshaders.iris.gl.GlVersion;

import java.util.Locale;
import java.util.Optional;

/**
 * Pixel data type enum - Vulkan Port.
 *
 * GL constants inlined as integer values to remove LWJGL OpenGL dependency.
 * These type values are used as tracking IDs during the porting process.
 */
public enum PixelType {
	BYTE(0x1400, GlVersion.GL_11),                          // GL_BYTE
	SHORT(0x1402, GlVersion.GL_11),                         // GL_SHORT
	INT(0x1404, GlVersion.GL_11),                           // GL_INT
	HALF_FLOAT(0x140B, GlVersion.GL_30),                    // GL_HALF_FLOAT
	FLOAT(0x1406, GlVersion.GL_11),                         // GL_FLOAT
	UNSIGNED_BYTE(0x1401, GlVersion.GL_11),                 // GL_UNSIGNED_BYTE
	UNSIGNED_BYTE_3_3_2(0x8032, GlVersion.GL_12),           // GL_UNSIGNED_BYTE_3_3_2
	UNSIGNED_BYTE_2_3_3_REV(0x8362, GlVersion.GL_12),       // GL_UNSIGNED_BYTE_2_3_3_REV
	UNSIGNED_SHORT(0x1403, GlVersion.GL_11),                // GL_UNSIGNED_SHORT
	UNSIGNED_SHORT_5_6_5(0x8363, GlVersion.GL_12),          // GL_UNSIGNED_SHORT_5_6_5
	UNSIGNED_SHORT_5_6_5_REV(0x8364, GlVersion.GL_12),      // GL_UNSIGNED_SHORT_5_6_5_REV
	UNSIGNED_SHORT_4_4_4_4(0x8033, GlVersion.GL_12),        // GL_UNSIGNED_SHORT_4_4_4_4
	UNSIGNED_SHORT_4_4_4_4_REV(0x8365, GlVersion.GL_12),    // GL_UNSIGNED_SHORT_4_4_4_4_REV
	UNSIGNED_SHORT_5_5_5_1(0x8034, GlVersion.GL_12),        // GL_UNSIGNED_SHORT_5_5_5_1
	UNSIGNED_SHORT_1_5_5_5_REV(0x8366, GlVersion.GL_12),    // GL_UNSIGNED_SHORT_1_5_5_5_REV
	UNSIGNED_INT(0x1405, GlVersion.GL_11),                  // GL_UNSIGNED_INT
	UNSIGNED_INT_8_8_8_8(0x8035, GlVersion.GL_12),          // GL_UNSIGNED_INT_8_8_8_8
	UNSIGNED_INT_8_8_8_8_REV(0x8367, GlVersion.GL_12),      // GL_UNSIGNED_INT_8_8_8_8_REV
	UNSIGNED_INT_10_10_10_2(0x8036, GlVersion.GL_12),        // GL_UNSIGNED_INT_10_10_10_2
	UNSIGNED_INT_2_10_10_10_REV(0x8368, GlVersion.GL_12);   // GL_UNSIGNED_INT_2_10_10_10_REV

	private final int glFormat;
	private final GlVersion minimumGlVersion;

	PixelType(int glFormat, GlVersion minimumGlVersion) {
		this.glFormat = glFormat;
		this.minimumGlVersion = minimumGlVersion;
	}

	public static Optional<PixelType> fromString(String name) {
		try {
			return Optional.of(PixelType.valueOf(name.toUpperCase(Locale.US)));
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
}
