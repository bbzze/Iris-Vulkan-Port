package net.irisshaders.iris.gl.texture;

import net.irisshaders.iris.gl.IrisRenderSystem;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Texture dimensionality types - Vulkan Port.
 *
 * GL constants inlined as integer values to remove LWJGL OpenGL dependency.
 * These map to Vulkan VkImageType / VkImageViewType:
 * - TEXTURE_1D -> VK_IMAGE_TYPE_1D / VK_IMAGE_VIEW_TYPE_1D
 * - TEXTURE_2D -> VK_IMAGE_TYPE_2D / VK_IMAGE_VIEW_TYPE_2D
 * - TEXTURE_3D -> VK_IMAGE_TYPE_3D / VK_IMAGE_VIEW_TYPE_3D
 * - TEXTURE_RECTANGLE -> VK_IMAGE_TYPE_2D (no direct Vulkan equivalent)
 */
public enum TextureType {
	TEXTURE_1D(0x0DE0),    // GL_TEXTURE_1D
	TEXTURE_2D(0x0DE1),    // GL_TEXTURE_2D
	TEXTURE_3D(0x806F),    // GL_TEXTURE_3D
	TEXTURE_RECTANGLE(0x806F); // GL_TEXTURE_3D (maps to 2D in Vulkan)

	private final int glType;

	TextureType(int glType) {
		this.glType = glType;
	}

	public static Optional<TextureType> fromString(String name) {
		try {
			return Optional.of(TextureType.valueOf(name));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public int getGlType() {
		return glType;
	}

	public void apply(int texture, int sizeX, int sizeY, int sizeZ, int internalFormat, int format, int pixelType, ByteBuffer pixels) {
		switch (this) {
			case TEXTURE_1D:
				IrisRenderSystem.texImage1D(texture, getGlType(), 0, internalFormat, sizeX, 0, format, pixelType, pixels);
				break;
			case TEXTURE_2D, TEXTURE_RECTANGLE:
				IrisRenderSystem.texImage2D(texture, getGlType(), 0, internalFormat, sizeX, sizeY, 0, format, pixelType, pixels);
				break;
			case TEXTURE_3D:
				IrisRenderSystem.texImage3D(texture, getGlType(), 0, internalFormat, sizeX, sizeY, sizeZ, 0, format, pixelType, pixels);
				break;
		}
	}
}
