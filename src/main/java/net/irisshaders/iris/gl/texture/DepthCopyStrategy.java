package net.irisshaders.iris.gl.texture;

import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;

/**
 * Depth copy strategy - Vulkan Port.
 *
 * In OpenGL, depth copying had three strategies depending on GPU capability:
 * - GL 4.3: glCopyImageSubData (fastest, T->T)
 * - GL 3.0: glBlitFramebuffer (FB->FB, needed for stencil)
 * - GL 2.0: glCopyTexSubImage2D (FB->T, slowest fallback)
 *
 * In Vulkan, depth copying uses vkCmdCopyImage or vkCmdBlitImage.
 * For the Vulkan port, we always use the VulkanCopy strategy which will
 * use vkCmdCopyImage in Phase 4. This is always available in Vulkan.
 */
public interface DepthCopyStrategy {
	static DepthCopyStrategy fastest(boolean combinedStencilRequired) {
		// In Vulkan, vkCmdCopyImage is always available and handles all cases
		return new VulkanCopy();
	}

	boolean needsDestFramebuffer();

	/**
	 * Executes the depth copy.
	 *
	 * In Vulkan, this will transition image layouts, perform vkCmdCopyImage,
	 * and transition back. Implemented in Phase 4.
	 */
	void copy(GlFramebuffer sourceFb, int sourceTexture, GlFramebuffer destFb, int destTexture, int width, int height);

	/**
	 * Vulkan depth copy strategy using vkCmdCopyImage.
	 * This replaces all three OpenGL strategies (Gl43CopyImage, Gl30BlitFb, Gl20CopyTexture).
	 */
	class VulkanCopy implements DepthCopyStrategy {
		@Override
		public boolean needsDestFramebuffer() {
			return false; // Vulkan copies between images directly
		}

		@Override
		public void copy(GlFramebuffer sourceFb, int sourceTexture, GlFramebuffer destFb, int destTexture, int width, int height) {
			IrisRenderSystem.copyDepthImage(sourceTexture, destTexture, width, height);
		}
	}
}
