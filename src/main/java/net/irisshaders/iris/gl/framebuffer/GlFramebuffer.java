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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

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

	// Frame tracking: CLEAR depth once per frame PER DEPTH TEXTURE (not per GlFramebuffer).
	// Multiple GlFramebuffers may share the same depth texture with different color attachments
	// (e.g., terrain vs entity DRAWBUFFERS). If each independently clears depth, the second
	// clear wipes existing depth data (terrain depth wiped by entity clear → entity triangles
	// overwrite terrain everywhere in the gbuffer, producing full-screen artifacts).
	// Using per-depth-texture tracking ensures only the first bind clears depth.
	private static final it.unimi.dsi.fastutil.ints.Int2IntMap depthTexClearFrame =
		new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

	// Track last bound Iris framebuffer for color attachment layout management
	private static GlFramebuffer lastBoundIrisFramebuffer = null;

	// Global cache: VulkanMod Framebuffer objects keyed by attachment hash.
	// Multiple GlFramebuffer instances with identical attachments (same VulkanImages)
	// share the SAME VulkanMod Framebuffer. This is critical because VulkanMod's
	// Renderer.beginRendering() uses Java object identity to detect framebuffer changes —
	// if terrain and entity GlFramebuffers each create their own Framebuffer, the renderer
	// sees "different" objects and restarts the render pass on every entity draw.
	// With sharing, beginRendering() detects same object and keeps the pass active.
	private static final Map<Integer, CachedFramebuffer> framebufferCache = new HashMap<>();

	private static class CachedFramebuffer {
		final Framebuffer framebuffer;
		final RenderPass renderPassClear;
		final RenderPass renderPassLoad;
		final int colorFinalLayout;
		int refCount; // Track how many GlFramebuffers reference this

		CachedFramebuffer(Framebuffer fb, RenderPass clear, RenderPass load, int colorFinalLayout) {
			this.framebuffer = fb;
			this.renderPassClear = clear;
			this.renderPassLoad = load;
			this.colorFinalLayout = colorFinalLayout;
			this.refCount = 1;
		}

		void cleanUp() {
			renderPassClear.cleanUp();
			renderPassLoad.cleanUp();
			framebuffer.cleanUp(false); // Don't free images - owned by Iris RenderTargets
		}
	}

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

	/**
	 * Returns the cached VulkanMod Framebuffer, or null if not yet created.
	 * Used by ExtendedShader to check if the target framebuffer is already bound.
	 */
	public Framebuffer getVulkanFramebuffer() {
		return vulkanFramebuffer;
	}

	// Track which attachment set is cached so we know when to recreate
	private int cachedAttachmentHash = 0;
	private static int diagBindCount = 0;
	private static final int DIAG_BIND_MAX = 60;

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

		// Include colorFinalLayout in hash so framebuffers with different final layouts
		// (e.g., SHADER_READ_ONLY vs COLOR_ATTACHMENT_OPTIMAL) get different cache entries.
		int cacheKey = attachmentHash * 31 + colorFinalLayout;

		// Look up or create VulkanMod framebuffer from global cache.
		// Multiple GlFramebuffer instances with identical attachments share the SAME
		// VulkanMod Framebuffer, so Renderer.beginRendering()'s identity check
		// (this.boundFramebuffer != framebuffer) detects no change and skips
		// the render pass restart. This eliminates the terrain→entity ping-pong.
		if (vulkanFramebuffer == null || cachedAttachmentHash != attachmentHash) {
			// Detach from previous cache entry if any
			if (cachedAttachmentHash != 0) {
				CachedFramebuffer prev = framebufferCache.get(cachedAttachmentHash * 31 + colorFinalLayout);
				if (prev != null) {
					prev.refCount--;
					if (prev.refCount <= 0) {
						framebufferCache.remove(cachedAttachmentHash * 31 + colorFinalLayout);
						prev.cleanUp();
					}
				}
			}
			// Clear per-instance references (don't clean up — cache owns them)
			vulkanFramebuffer = null;
			vulkanRenderPassClear = null;
			vulkanRenderPassLoad = null;

			// Check global cache
			CachedFramebuffer cached = framebufferCache.get(cacheKey);
			if (cached != null && cached.colorFinalLayout == colorFinalLayout) {
				// Reuse cached framebuffer — this is the key optimization
				vulkanFramebuffer = cached.framebuffer;
				vulkanRenderPassClear = cached.renderPassClear;
				vulkanRenderPassLoad = cached.renderPassLoad;
				cached.refCount++;
			} else {
				// Cache miss — create new VulkanMod Framebuffer

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
				Framebuffer newFb;
				if (colorImages.size() == 1) {
					newFb = Framebuffer.builder(colorImages.get(0), depthImage).build();
				} else {
					newFb = Framebuffer.builder(colorImages, depthImage).build();
				}

				// Create TWO render pass variants: CLEAR depth and LOAD depth
				RenderPass.Builder rpClearBuilder = RenderPass.builder(newFb);
				configureColorAttachments(rpClearBuilder);
				if (depthImage != null) {
					rpClearBuilder.getDepthAttachmentInfo()
						.setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE)
						.setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
				}
				RenderPass rpClear = rpClearBuilder.build();

				RenderPass.Builder rpLoadBuilder = RenderPass.builder(newFb);
				configureColorAttachments(rpLoadBuilder);
				if (depthImage != null) {
					rpLoadBuilder.getDepthAttachmentInfo()
						.setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE)
						.setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
				}
				RenderPass rpLoad = rpLoadBuilder.build();

				// Store in cache
				CachedFramebuffer newCached = new CachedFramebuffer(newFb, rpClear, rpLoad, colorFinalLayout);
				// Remove stale entry if present
				CachedFramebuffer stale = framebufferCache.put(cacheKey, newCached);
				if (stale != null && stale.refCount <= 0) {
					stale.cleanUp();
				}

				vulkanFramebuffer = newFb;
				vulkanRenderPassClear = rpClear;
				vulkanRenderPassLoad = rpLoad;
			}

			cachedAttachmentHash = attachmentHash;
		}

		// Determine which render pass to use: CLEAR depth on first bind per frame, LOAD otherwise.
		// Track per DEPTH TEXTURE ID (not per GlFramebuffer instance) to prevent terrain/entity
		// framebuffers sharing the same depth from double-clearing.
		int currentFrame = Renderer.getCurrentFrame();
		boolean clearDepth = false;
		if (hasDepthAttachment && depthTexId > 0) {
			int lastClear = depthTexClearFrame.getOrDefault(depthTexId, -1);
			if (lastClear != currentFrame) {
				clearDepth = true;
				depthTexClearFrame.put(depthTexId, currentFrame);
			}
		}
		RenderPass renderPass = clearDepth ? vulkanRenderPassClear : vulkanRenderPassLoad;

		// End current render pass if switching to a different framebuffer.
		// Layout transitions are now handled by the render pass itself:
		// initialLayout = finalLayout (e.g. SHADER_READ_ONLY) matches the actual
		// image layout, and the render pass internally transitions to the subpass
		// layout (COLOR_ATTACHMENT_OPTIMAL). An input subpass dependency ensures
		// proper synchronization. No explicit barriers needed.
		Renderer renderer = Renderer.getInstance();
		Framebuffer currentBound = renderer.getBoundFramebuffer();
		if (currentBound != vulkanFramebuffer) {
			renderer.endRenderPass();
		}
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
	 * Transitions this framebuffer's depth attachment to DEPTH_STENCIL_ATTACHMENT_OPTIMAL.
	 * After a render pass with finalLayout=SHADER_READ_ONLY_OPTIMAL (for composite sampling),
	 * the depth image must be transitioned back before the next render pass that writes depth.
	 */
	private void transitionDepthAttachmentForRendering() {
		if (vulkanFramebuffer == null) return;
		VulkanImage depthImg = vulkanFramebuffer.getDepthAttachment();
		if (depthImg != null && depthImg.getCurrentLayout() != VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
			VkCommandBuffer cmd = Renderer.getCommandBuffer();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				depthImg.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
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
		// Detach from cache entry — cache manages actual cleanup via refCount
		if (cachedAttachmentHash != 0) {
			int cacheKey = cachedAttachmentHash * 31 + colorFinalLayout;
			CachedFramebuffer cached = framebufferCache.get(cacheKey);
			if (cached != null) {
				cached.refCount--;
				if (cached.refCount <= 0) {
					framebufferCache.remove(cacheKey);
					cached.cleanUp();
				}
			}
		}
		vulkanFramebuffer = null;
		vulkanRenderPassClear = null;
		vulkanRenderPassLoad = null;
		cachedAttachmentHash = 0;
	}

	/**
	 * Clears the global framebuffer cache. Call on pipeline reload / shader pack change.
	 */
	public static void clearFramebufferCache() {
		for (CachedFramebuffer cached : framebufferCache.values()) {
			cached.cleanUp();
		}
		framebufferCache.clear();
		depthTexClearFrame.clear();
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

	private static String layoutName(int layout) {
		return switch (layout) {
			case VK_IMAGE_LAYOUT_UNDEFINED -> "UNDEFINED";
			case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> "COLOR_ATTACHMENT_OPTIMAL";
			case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> "SHADER_READ_ONLY_OPTIMAL";
			case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> "TRANSFER_DST_OPTIMAL";
			case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> "TRANSFER_SRC_OPTIMAL";
			case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> "DEPTH_STENCIL_ATTACHMENT_OPTIMAL";
			case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> "PRESENT_SRC_KHR";
			default -> "UNKNOWN(" + layout + ")";
		};
	}
}
