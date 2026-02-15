package net.irisshaders.iris.targets;

import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;

/**
 * Depth texture resource - Vulkan Port.
 *
 * In OpenGL, this was a depth texture created via glTexImage2D with depth formats
 * (GL_DEPTH_COMPONENT32F, GL_DEPTH24_STENCIL8, etc.).
 * In Vulkan, depth textures are VulkanImage objects with depth-specific VkFormat
 * (VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT) and VK_IMAGE_ASPECT_DEPTH_BIT.
 *
 * Actual VulkanImage creation with depth format in Phase 3/4.
 */
public class DepthTexture extends GlResource {
	private int width;
	private int height;
	private DepthBufferFormat format;

	public DepthTexture(String name, int width, int height, DepthBufferFormat format) {
		super(IrisRenderSystem.createTexture(0x0DE1)); // GL_TEXTURE_2D placeholder
		this.width = width;
		this.height = height;
		this.format = format;

		int texture = getGlId();

		resize(width, height, format);
		GLDebug.nameObject(0x1502, texture, name); // GL_TEXTURE constant placeholder

		// Depth textures use NEAREST filtering and CLAMP_TO_EDGE by default
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2801, 0x2600); // MIN_FILTER = NEAREST
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2800, 0x2600); // MAG_FILTER = NEAREST
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2802, 0x812F); // WRAP_S = CLAMP_TO_EDGE
		IrisRenderSystem.texParameteri(texture, 0x0DE1, 0x2803, 0x812F); // WRAP_T = CLAMP_TO_EDGE
	}

	void resize(int width, int height, DepthBufferFormat format) {
		this.width = width;
		this.height = height;
		this.format = format;
		IrisRenderSystem.texImage2D(getTextureId(), 0x0DE1, 0, format.getGlInternalFormat(), width, height, 0,
			format.getGlType(), format.getGlFormat(), null);
	}

	public int getTextureId() {
		return getGlId();
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public DepthBufferFormat getFormat() {
		return format;
	}

	@Override
	protected void destroyInternal() {
		// VulkanImage depth texture destruction in Phase 3/4
	}
}
