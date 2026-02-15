package net.irisshaders.iris.gl.framebuffer;

import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan Framebuffer Wrapper for Iris.
 *
 * Replaces the OpenGL framebuffer object with a Vulkan-compatible abstraction.
 * In Vulkan, framebuffers are immutable objects tied to a VkRenderPass, containing
 * VkImageView attachments. This class tracks attachment configuration and will
 * be fully connected to VulkanMod's Framebuffer/RenderPass system in Phase 4.
 *
 * Key differences from OpenGL:
 * - No glDrawBuffers equivalent (MRT is configured in VkRenderPass subpass)
 * - No bind/unbind (framebuffer is bound when beginning a render pass)
 * - Attachments are VulkanImage objects, not GL texture IDs
 */
public class GlFramebuffer extends GlResource {
	private final Int2IntMap colorAttachments;
	private int depthAttachment;
	private boolean hasDepthAttachment;
	private final int maxDrawBuffers = 16;
	private final int maxColorAttachments = 16;
	private int[] currentDrawBuffers;

	// Vulkan framebuffer/render pass caching
	private Framebuffer vulkanFramebuffer;
	private RenderPass vulkanRenderPassClear; // CLEAR depth on first bind per frame
	private RenderPass vulkanRenderPassLoad;  // LOAD depth on subsequent binds

	// Frame tracking: CLEAR depth once per frame PER framebuffer.
	// Must be per-instance because different GlFramebuffers may have different depth textures.
	// A static variable would let the first framebuffer to bind claim the clear,
	// leaving other framebuffers' depth textures uninitialized (all zeros → all fragments fail LEQUAL).
	private int lastDepthClearFrame = -1;

	// Track last bound Iris framebuffer for color attachment layout management
	private static GlFramebuffer lastBoundIrisFramebuffer = null;

	// Configurable final layout for color attachments.
	// Default to SHADER_READ_ONLY_OPTIMAL because VulkanMod's createRenderPass() adds
	// an explicit subpass external dependency for this layout that properly synchronizes
	// color attachment writes with subsequent fragment shader reads. Without this dependency
	// (e.g., with COLOR_ATTACHMENT_OPTIMAL), render pass store operations are not guaranteed
	// to be visible to later commands, causing data loss (stored data invisible to blits/reads).
	// VulkanMod's endRenderPass() DOES update VulkanImage.currentLayout via setCurrentLayout(),
	// so the tracked layout stays in sync.
	private int colorFinalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

	public GlFramebuffer() {
		super(IrisRenderSystem.createFramebuffer());

		this.colorAttachments = new Int2IntArrayMap();
		this.hasDepthAttachment = false;
	}

	/**
	 * Sets the final layout for color attachments in this framebuffer's render passes.
	 * Use COLOR_ATTACHMENT_OPTIMAL for framebuffers targeting the swap chain.
	 * Use SHADER_READ_ONLY_OPTIMAL (default) for gbuffer framebuffers that will be sampled.
	 */
	public void setColorFinalLayout(int layout) {
		this.colorFinalLayout = layout;
		cleanUpVulkanResources(); // Force render pass recreation with new layout
	}

	public void addDepthAttachment(int texture) {
		this.depthAttachment = texture;
		this.hasDepthAttachment = true;
		// Invalidate cached Vulkan framebuffer since attachment changed
		cleanUpVulkanResources();
		IrisRenderSystem.framebufferTexture2D(getGlId(), 0, 0, 0, texture, 0);
	}

	public void addColorAttachment(int index, int texture) {
		colorAttachments.put(index, texture);
		// Invalidate cached Vulkan framebuffer since attachment changed
		cleanUpVulkanResources();
		IrisRenderSystem.framebufferTexture2D(getGlId(), 0, index, 0, texture, 0);
	}

	public void noDrawBuffers() {
		this.currentDrawBuffers = new int[0];
		IrisRenderSystem.drawBuffers(getGlId(), new int[]{0});
	}

	public void drawBuffers(int[] buffers) {
		if (buffers.length > maxDrawBuffers) {
			throw new IllegalArgumentException("Cannot write to more than " + maxDrawBuffers + " draw buffers on this GPU");
		}

		for (int buffer : buffers) {
			if (buffer >= maxColorAttachments) {
				throw new IllegalArgumentException("Only " + maxColorAttachments + " color attachments are supported on this GPU, but an attempt was made to write to a color attachment with index " + buffer);
			}
		}

		this.currentDrawBuffers = buffers.clone();
		IrisRenderSystem.drawBuffers(getGlId(), buffers);
	}

	public void readBuffer(int buffer) {
		IrisRenderSystem.readBuffer(getGlId(), buffer);
	}

	public int getColorAttachment(int index) {
		return colorAttachments.get(index);
	}

	public Int2IntMap getColorAttachments() {
		return colorAttachments;
	}

	public boolean hasDepthAttachment() {
		return hasDepthAttachment;
	}

	public int getDepthAttachment() {
		return depthAttachment;
	}

	public int[] getCurrentDrawBuffers() {
		return currentDrawBuffers;
	}

	// Track which attachment set is cached so we know when to recreate
	private int cachedAttachmentHash = 0;

	public void bind() {
		if (!Renderer.isRecording()) {
			Iris.logger.warn("GlFramebuffer.bind(): Renderer not recording, skipping bind for FB {}", getGlId());
			return;
		}

		if (colorAttachments.isEmpty() && !hasDepthAttachment) {
			Iris.logger.warn("GlFramebuffer.bind(): No color or depth attachments for FB {}", getGlId());
			return;
		}

		// Get depth attachment ID (may be 0 if no depth)
		int depthTexId = hasDepthAttachment ? depthAttachment : 0;

		// Compute a hash of current attachments INCLUDING VkImage handles to detect
		// VulkanImage replacement (GlTexture.allocateImage() creates new VkImage on resize
		// but keeps the same texture ID, so texture-ID-only hash misses the change).
		int attachmentHash = colorAttachments.hashCode() * 31 + depthTexId;
		for (Int2IntMap.Entry entry : colorAttachments.int2IntEntrySet()) {
			GlTexture glTex = GlTexture.getTexture(entry.getIntValue());
			if (glTex != null && glTex.getVulkanImage() != null) {
				attachmentHash = attachmentHash * 31 + Long.hashCode(glTex.getVulkanImage().getId());
			}
		}
		if (hasDepthAttachment && depthTexId > 0) {
			GlTexture glDepthTex = GlTexture.getTexture(depthTexId);
			if (glDepthTex != null && glDepthTex.getVulkanImage() != null) {
				attachmentHash = attachmentHash * 31 + Long.hashCode(glDepthTex.getVulkanImage().getId());
			}
		}

		// Recreate VulkanMod framebuffer if attachments or underlying VkImages changed
		if (vulkanFramebuffer == null || cachedAttachmentHash != attachmentHash) {
			cleanUpVulkanResources();

			// Collect VulkanImages for ALL color attachments (MRT)
			List<VulkanImage> colorImages = new ArrayList<>();
			int maxIndex = 0;
			for (Int2IntMap.Entry entry : colorAttachments.int2IntEntrySet()) {
				if (entry.getIntKey() > maxIndex) maxIndex = entry.getIntKey();
			}

			for (int i = 0; i <= maxIndex; i++) {
				if (!colorAttachments.containsKey(i)) continue;
				int texId = colorAttachments.get(i);
				if (texId <= 0) continue;

				GlTexture glTex = GlTexture.getTexture(texId);
				if (glTex == null || glTex.getVulkanImage() == null) {
					Iris.logger.warn("GlFramebuffer.bind(): No VulkanImage for color attachment {} (tex {}) in FB {}",
						i, texId, getGlId());
					continue;
				}
				colorImages.add(glTex.getVulkanImage());
			}

			// Look up VulkanImage for depth attachment (optional)
			VulkanImage depthImage = null;
			if (hasDepthAttachment && depthTexId > 0) {
				GlTexture glDepthTex = GlTexture.getTexture(depthTexId);
				if (glDepthTex != null) {
					depthImage = glDepthTex.getVulkanImage();
				}
			}

			if (colorImages.isEmpty() && depthImage == null) {
				Iris.logger.warn("GlFramebuffer.bind(): No valid VulkanImages for FB {}", getGlId());
				return;
			}

			// Create VulkanMod Framebuffer with MRT support
			if (colorImages.size() == 1) {
				vulkanFramebuffer = Framebuffer.builder(colorImages.get(0), depthImage).build();
			} else {
				vulkanFramebuffer = Framebuffer.builder(colorImages, depthImage).build();
			}

			// Create TWO render pass variants: one with CLEAR depth, one with LOAD depth.
			// Color attachments always use LOAD (preserve existing content).
			// CLEAR variant is used on first bind per frame to initialize depth to 1.0.
			// LOAD variant is used on subsequent binds to preserve accumulated depth.

			// --- CLEAR depth variant ---
			RenderPass.Builder rpClearBuilder = RenderPass.builder(vulkanFramebuffer);
			configureColorAttachments(rpClearBuilder);
			if (depthImage != null) {
				rpClearBuilder.getDepthAttachmentInfo()
					.setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE)
					.setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			}
			vulkanRenderPassClear = rpClearBuilder.build();

			// --- LOAD depth variant ---
			RenderPass.Builder rpLoadBuilder = RenderPass.builder(vulkanFramebuffer);
			configureColorAttachments(rpLoadBuilder);
			if (depthImage != null) {
				rpLoadBuilder.getDepthAttachmentInfo()
					.setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE)
					.setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			}
			vulkanRenderPassLoad = rpLoadBuilder.build();

			cachedAttachmentHash = attachmentHash;
		}

		// Determine which render pass to use: CLEAR depth on first bind per frame, LOAD otherwise
		int currentFrame = Renderer.getCurrentFrame();
		boolean clearDepth = hasDepthAttachment && (currentFrame != lastDepthClearFrame);
		if (clearDepth) {
			lastDepthClearFrame = currentFrame;
		}
		RenderPass renderPass = clearDepth ? vulkanRenderPassClear : vulkanRenderPassLoad;

		// Let VulkanMod handle everything: endRenderPass, layout transitions, viewport, scissor.
		// VulkanMod's RenderPass.beginRenderPass() already transitions color attachments to
		// COLOR_ATTACHMENT_OPTIMAL and depth to DEPTH_STENCIL_ATTACHMENT_OPTIMAL before
		// calling vkCmdBeginRenderPass. Renderer.beginRendering() calls endRenderPass()
		// on the previous render pass if the framebuffer changed.
		Renderer renderer = Renderer.getInstance();
		renderer.beginRendering(renderPass, vulkanFramebuffer);
	}

	public void bindAsReadBuffer() {
		// In Vulkan, there's no separate "read framebuffer" concept.
		// However, callers (like FinalPassRenderer's no-final-pass path) expect that
		// binding as read makes this framebuffer's attachments the source for subsequent
		// copyTexSubImage2D calls. We bind the full render pass so the copy reads
		// from the correct color attachment.
		bind();
	}

	public void bindAsDrawBuffer() {
		bind();
	}

	private void configureColorAttachments(RenderPass.Builder rpBuilder) {
		if (rpBuilder.getColorAttachmentInfos() != null) {
			for (RenderPass.AttachmentInfo info : rpBuilder.getColorAttachmentInfos()) {
				info.setLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
					.setFinalLayout(colorFinalLayout);
			}
		} else if (rpBuilder.getColorAttachmentInfo() != null) {
			rpBuilder.getColorAttachmentInfo()
				.setLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
				.setFinalLayout(colorFinalLayout);
		}
	}

	/**
	 * Updates attachment layouts after a render pass ends.
	 * Color attachments: render pass finalLayout already transitioned them to colorFinalLayout.
	 * Depth attachment: explicitly transition from DEPTH_STENCIL_ATTACHMENT_OPTIMAL to
	 * SHADER_READ_ONLY_OPTIMAL so composite/deferred passes can sample depthtex0.
	 * VulkanMod's beginRenderPass() will transition depth back to ATTACHMENT_OPTIMAL
	 * when the next render pass starts.
	 */
	private void updateColorLayoutAfterRenderPass() {
		if (vulkanFramebuffer == null) return;
		int count = vulkanFramebuffer.getColorAttachmentCount();
		for (int i = 0; i < count; i++) {
			VulkanImage img = vulkanFramebuffer.getColorAttachment(i);
			if (img != null) {
				img.setCurrentLayout(colorFinalLayout);
			}
		}
		// Explicitly transition depth to SHADER_READ_ONLY_OPTIMAL for sampling.
		// The render pass left it in DEPTH_STENCIL_ATTACHMENT_OPTIMAL (default finalLayout).
		// VulkanMod's transitionImageLayout supports ATTACHMENT→SHADER_READ_ONLY transition.
		VulkanImage depthImg = vulkanFramebuffer.getDepthAttachment();
		if (hasDepthAttachment && depthImg != null) {
			VkCommandBuffer cmd = Renderer.getCommandBuffer();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				if (depthImg.getCurrentLayout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
					depthImg.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
				}
			}
		}
	}

	/**
	 * Transitions this framebuffer's color attachments to COLOR_ATTACHMENT_OPTIMAL.
	 * Required because VulkanMod's render pass initialLayout expects COLOR_ATTACHMENT_OPTIMAL
	 * but doesn't perform the transition itself (unlike depth, which is explicitly
	 * transitioned in RenderPass.beginRenderPass()).
	 */
	private void transitionColorAttachmentsForRendering() {
		if (vulkanFramebuffer == null) return;
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		int count = vulkanFramebuffer.getColorAttachmentCount();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int i = 0; i < count; i++) {
				VulkanImage img = vulkanFramebuffer.getColorAttachment(i);
				if (img != null && img.getCurrentLayout() != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
					img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
				}
			}
		}
	}

	/**
	 * Clears all color attachments to the specified color using vkCmdClearColorImage.
	 * Must be called OUTSIDE of a render pass. Ends the current render pass if one is active.
	 * This is the Vulkan-native equivalent of glClear(GL_COLOR_BUFFER_BIT) for Iris render targets.
	 */
	public void clearColorAttachments(float r, float g, float b, float a) {
		if (!Renderer.isRecording()) return;
		if (colorAttachments.isEmpty()) return;

		// End any active render pass — vkCmdClearColorImage cannot be inside a render pass
		Renderer renderer = Renderer.getInstance();
		renderer.endRenderPass();

		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
			clearColor.float32(0, r).float32(1, g).float32(2, b).float32(3, a);

			VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0)
				.levelCount(1)
				.baseArrayLayer(0)
				.layerCount(1);

			for (Int2IntMap.Entry entry : colorAttachments.int2IntEntrySet()) {
				int texId = entry.getIntValue();
				if (texId <= 0) continue;

				GlTexture glTex = GlTexture.getTexture(texId);
				if (glTex == null || glTex.getVulkanImage() == null) continue;

				VulkanImage img = glTex.getVulkanImage();

				// Transition to TRANSFER_DST for clear, then back to SHADER_READ_ONLY
				img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
				vkCmdClearColorImage(cmd, img.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
					clearColor, range);
				img.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			}
		}
	}

	private void cleanUpVulkanResources() {
		if (vulkanRenderPassClear != null) {
			vulkanRenderPassClear.cleanUp();
			vulkanRenderPassClear = null;
		}
		if (vulkanRenderPassLoad != null) {
			vulkanRenderPassLoad.cleanUp();
			vulkanRenderPassLoad = null;
		}
		if (vulkanFramebuffer != null) {
			vulkanFramebuffer.cleanUp(false); // Don't free images - owned by Iris RenderTargets
			vulkanFramebuffer = null;
		}
		cachedAttachmentHash = 0;
	}

	@Override
	protected void destroyInternal() {
		cleanUpVulkanResources();
	}

	public int getStatus() {
		// Vulkan framebuffers are always "complete" if creation succeeded
		return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
	}

	public int getId() {
		return getGlId();
	}
}
