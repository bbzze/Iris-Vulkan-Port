package net.irisshaders.iris.texture.util;

import net.irisshaders.iris.gl.IrisRenderSystem;
import net.minecraft.client.Minecraft;

/**
 * Texture manipulation utility - Vulkan Port.
 *
 * All GlStateManager calls replaced with IrisRenderSystem stubs.
 * GL11/GL30 constants inlined as hex values.
 * In Vulkan, the fillWithColor operation will use vkCmdClearColorImage
 * or a render pass with loadOp=CLEAR instead of framebuffer-based clearing.
 */
public class TextureManipulationUtil {
	// GL constants (inlined from GL11/GL30)
	private static final int GL_FRAMEBUFFER = 0x8D40;
	private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
	private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
	private static final int GL_COLOR_CLEAR_VALUE = 0x0C22;
	private static final int GL_TEXTURE_BINDING_2D = 0x8069;
	private static final int GL_VIEWPORT = 0x0BA2;
	private static final int GL_TEXTURE_2D = 0x0DE1;
	private static final int GL_TEXTURE_WIDTH = 0x1000;
	private static final int GL_TEXTURE_HEIGHT = 0x1001;
	private static final int GL_COLOR_BUFFER_BIT = 0x4000;

	private static int colorFillFBO = -1;

	public static void fillWithColor(int textureId, int maxLevel, int rgba) {
		if (colorFillFBO == -1) {
			colorFillFBO = IrisRenderSystem.createFramebuffer();
		}

		int previousFramebufferId = IrisRenderSystem.getInteger(GL_FRAMEBUFFER_BINDING);
		float[] previousClearColor = new float[4];
		IrisRenderSystem.getFloatv(GL_COLOR_CLEAR_VALUE, previousClearColor);
		int previousTextureId = IrisRenderSystem.getInteger(GL_TEXTURE_BINDING_2D);
		int[] previousViewport = new int[4];
		IrisRenderSystem.getIntegerv(GL_VIEWPORT, previousViewport);

		IrisRenderSystem.bindFramebuffer(GL_FRAMEBUFFER, colorFillFBO);
		IrisRenderSystem.clearColor(
			(rgba >> 24 & 0xFF) / 255.0f,
			(rgba >> 16 & 0xFF) / 255.0f,
			(rgba >> 8 & 0xFF) / 255.0f,
			(rgba & 0xFF) / 255.0f
		);
		IrisRenderSystem.bindTexture(textureId);
		for (int level = 0; level <= maxLevel; ++level) {
			int width = IrisRenderSystem.getTexLevelParameter(GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH);
			int height = IrisRenderSystem.getTexLevelParameter(GL_TEXTURE_2D, level, GL_TEXTURE_HEIGHT);
			IrisRenderSystem.setViewport(0, 0, width, height);
			IrisRenderSystem.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureId, level);
			IrisRenderSystem.clear(GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
			IrisRenderSystem.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, level);
		}

		IrisRenderSystem.bindFramebuffer(GL_FRAMEBUFFER, previousFramebufferId);
		IrisRenderSystem.clearColor(previousClearColor[0], previousClearColor[1], previousClearColor[2], previousClearColor[3]);
		IrisRenderSystem.bindTexture(previousTextureId);
		IrisRenderSystem.setViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
	}
}
