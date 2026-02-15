package net.irisshaders.iris.targets.backed;

import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.TextureUploadHelper;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Noise texture - Vulkan Port.
 *
 * An extremely simple noise texture. Each color channel contains a uniform random
 * value from 0 to 255. Essentially just dumps an array of random bytes into a
 * texture and calls it a day.
 *
 * In Vulkan, this will be a VulkanImage with VK_FORMAT_R8G8B8_UNORM (or R8G8B8A8
 * with alpha=255 if 3-component formats aren't supported).
 *
 * Actual VulkanImage creation in Phase 3.
 */
public class NoiseTexture extends GlResource {
	int width;
	int height;

	public NoiseTexture(int width, int height) {
		super(IrisRenderSystem.createTexture(0x0DE1)); // GL_TEXTURE_2D placeholder
		this.width = width;
		this.height = height;

		int texture = getGlId();

		// LINEAR filter, REPEAT wrap
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2801, 0x2601); // MIN_FILTER = LINEAR
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2800, 0x2601); // MAG_FILTER = LINEAR
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2802, 0x2901); // WRAP_S = REPEAT
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2803, 0x2901); // WRAP_T = REPEAT

		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x813D, 0); // GL_TEXTURE_MAX_LEVEL
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x813A, 0); // GL_TEXTURE_MIN_LOD
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x813B, 0); // GL_TEXTURE_MAX_LOD
		IrisRenderSystem.texParameterf(texture, 0x0DE1, 0x8501, 0.0F); // GL_TEXTURE_LOD_BIAS

		resize(texture, width, height);

		GLDebug.nameObject(0x1502, texture, "noise texture");
	}

	void resize(int texture, int width, int height) {
		this.width = width;
		this.height = height;

		ByteBuffer pixels = generateNoise();

		TextureUploadHelper.resetTextureUploadState();

		// GL_RGB = 0x1907, GL_UNSIGNED_BYTE = 0x1401
		IrisRenderSystem.texImage2D(texture, 0x0DE1, 0, 0x1907, width, height, 0, 0x1907, 0x1401, pixels);
	}

	private ByteBuffer generateNoise() {
		byte[] pixels = new byte[3 * width * height];

		Random random = new Random(0);
		random.nextBytes(pixels);

		ByteBuffer buffer = ByteBuffer.allocateDirect(pixels.length);
		buffer.put(pixels);
		buffer.flip();

		return buffer;
	}

	public int getTextureId() {
		return getGlId();
	}

	@Override
	protected void destroyInternal() {
		// VulkanImage destruction in Phase 3
	}
}
