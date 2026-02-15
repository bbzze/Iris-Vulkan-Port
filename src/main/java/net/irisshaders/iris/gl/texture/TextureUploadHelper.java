package net.irisshaders.iris.gl.texture;

/**
 * Texture upload state helper - Vulkan Port.
 *
 * In OpenGL, pixel store parameters (GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_ROWS, etc.)
 * affected how texture data was read from client memory during upload.
 * In Vulkan, texture uploads go through staging buffers with explicit layout control,
 * so these parameters don't exist.
 *
 * This class is now a no-op but kept for API compatibility.
 */
public class TextureUploadHelper {
	private TextureUploadHelper() {
		// no construction
	}

	public static void resetTextureUploadState() {
		// No-op in Vulkan - pixel store parameters don't exist.
		// Texture upload layout is controlled explicitly via buffer-to-image copy commands.
	}
}
