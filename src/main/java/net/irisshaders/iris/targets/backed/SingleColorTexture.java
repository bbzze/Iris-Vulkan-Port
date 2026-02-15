package net.irisshaders.iris.targets.backed;

import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.TextureUploadHelper;

import java.nio.ByteBuffer;

/**
 * Single-color 1x1 texture - Vulkan Port.
 *
 * In OpenGL, this created a 1x1 RGBA texture via glTexImage2D.
 * In Vulkan, this will be a 1x1 VulkanImage with VK_FORMAT_R8G8B8A8_UNORM.
 *
 * Actual VulkanImage creation in Phase 3.
 */
public class SingleColorTexture extends GlResource {
	private final int red;
	private final int green;
	private final int blue;
	private final int alpha;

	public SingleColorTexture(int red, int green, int blue, int alpha) {
		super(IrisRenderSystem.createTexture(0x0DE1)); // GL_TEXTURE_2D placeholder
		this.red = red;
		this.green = green;
		this.blue = blue;
		this.alpha = alpha;

		ByteBuffer pixel = ByteBuffer.allocateDirect(4);
		pixel.put((byte) red);
		pixel.put((byte) green);
		pixel.put((byte) blue);
		pixel.put((byte) alpha);
		pixel.position(0);

		int texture = getGlId();

		GLDebug.nameObject(0x1502, texture, "single color (" + red + ", " + green + "," + blue + "," + alpha + ")");

		// LINEAR filter, REPEAT wrap
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2801, 0x2601); // MIN_FILTER = LINEAR
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2800, 0x2601); // MAG_FILTER = LINEAR
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2802, 0x2901); // WRAP_S = REPEAT
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2803, 0x2901); // WRAP_T = REPEAT

		TextureUploadHelper.resetTextureUploadState();
		// GL_RGBA = 0x1908, GL_UNSIGNED_BYTE = 0x1401
		IrisRenderSystem.texImage2D(texture, 0x0DE1, 0, 0x1908, 1, 1, 0, 0x1908, 0x1401, pixel);
	}

	public int getRed() {
		return red;
	}

	public int getGreen() {
		return green;
	}

	public int getBlue() {
		return blue;
	}

	public int getAlpha() {
		return alpha;
	}

	public int getTextureId() {
		return getGlId();
	}

	@Override
	protected void destroyInternal() {
		// VulkanImage destruction in Phase 3
	}
}
