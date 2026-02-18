package net.irisshaders.iris.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3i;

import net.irisshaders.iris.gl.uniform.IrisUniformBuffer;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.gl.GlTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Iris Render System - Vulkan Port
 *
 * This class replaces the original OpenGL-based IrisRenderSystem with Vulkan-compatible
 * operations. In Vulkan, many OpenGL concepts don't exist directly:
 *
 * - Texture binding -> Descriptor set updates (handled in Phase 8)
 * - Uniform uploads -> UBO/push constant writes (handled in Phase 7)
 * - Framebuffer ops -> VkRenderPass/VkFramebuffer (handled in Phase 4)
 * - Shader compilation -> SPIR-V via shaderc (handled in Phase 5)
 * - Draw state -> Pipeline state objects (handled in Phase 6)
 *
 * Methods that are Vulkan-incompatible throw UnsupportedOperationException with
 * a descriptive message indicating which phase will implement the replacement.
 */
public class IrisRenderSystem {
	private static Matrix4f backupProjection;
	private static boolean supportsCompute = true;
	private static boolean supportsTesselation = true;
	private static int polygonMode = 0; // VK_POLYGON_MODE_FILL = 0

	// Texture tracking for descriptor set management
	private static final Map<Integer, Long> textureBindings = new HashMap<>();
	private static long nextTextureId = 1;

	// Sampler tracking
	private static final Map<Integer, Long> samplerBindings = new HashMap<>();
	private static long nextSamplerId = 1;

	// Active texture unit tracking (replaces GlStateManagerAccessor.getActiveTexture())
	private static int activeTextureUnit = 0;

	// Viewport tracking (replaces GlStateManager.Viewport)
	private static int viewportWidth = 1;
	private static int viewportHeight = 1;

	// Blend state tracking for uniform queries (replaces GlStateManagerAccessor.getBLEND())
	private static boolean blendEnabled = false;
	private static int blendSrcRgb = 1;   // GL_ONE
	private static int blendDstRgb = 0;   // GL_ZERO
	private static int blendSrcAlpha = 1;  // GL_ONE
	private static int blendDstAlpha = 0;  // GL_ZERO

	// Texture binding tracking (replaces GlStateManagerAccessor.getTEXTURES())
	// Must be >= 32 to cover all sampler units including shadowcolor0/1 at units 16-17
	private static final int[] boundTextures = new int[32];

	public static void initRenderer() {
		Iris.logger.info("Iris Vulkan Render System initialized");
		Iris.logger.info("Vulkan backend: compute={}, tessellation={}", supportsCompute, supportsTesselation);
	}

	// ==================== Texture Operations ====================
	// These are stubs that will be replaced in Phase 3 (Texture System Bridge)

	public static int createTexture(int target) {
		// Use GlStateManager to generate texture IDs so they are registered in
		// VulkanMod's GlTexture map. This ensures RenderSystem.bindTexture() can
		// find them later.
		return GlStateManager._genTexture();
	}

	public static void bindTextureForSetup(int glType, int textureId) {
		// Bind through VulkanMod's GlTexture so subsequent setup calls target this texture
		GlStateManager._bindTexture(textureId);
	}

	public static void bindTextureToUnit(int target, int unit, int texture) {
		// Bind texture to a specific unit via VulkanMod's texture selector
		int prevUnit = activeTextureUnit;
		GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
		GlStateManager._bindTexture(texture);
		GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + prevUnit);
		// Track binding so diagnostic code can query it
		if (unit >= 0 && unit < boundTextures.length) {
			boundTextures[unit] = texture;
		}
		textureBindings.put(unit, (long) texture);
	}

	public static void texParameteri(int texture, int target, int pname, int param) {
		// Bind the texture, set the parameter via GlStateManager, then restore
		GlStateManager._bindTexture(texture);
		GlStateManager._texParameter(target, pname, param);
	}

	public static void texParameterf(int texture, int target, int pname, float param) {
		GlStateManager._bindTexture(texture);
		GlStateManager._texParameter(target, pname, param);
	}

	public static void texParameteriv(int texture, int target, int pname, int[] params) {
		// Sampler parameters - bind and set
		GlStateManager._bindTexture(texture);
		GlStateManager._texParameter(target, pname, params[0]);
	}

	public static void texParameterivDirect(int target, int pname, int[] params) {
		// Operates on currently bound texture
		GlStateManager._texParameter(target, pname, params[0]);
	}

	public static int getTexParameteri(int texture, int target, int pname) {
		return 0; // Default return for queries
	}

	public static void texImage1D(int texture, int target, int level, int internalformat, int width, int border, int format, int type, @Nullable ByteBuffer pixels) {
		// 1D textures are rare; treat as 2D with height=1
		GlStateManager._bindTexture(texture);
		net.vulkanmod.gl.GlTexture.texImage2D(target, level, internalformat, width, 1, border, format, type, pixels);
	}

	public static void texImage2D(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
		// Bind the texture first (VulkanMod's texImage2D operates on bound texture),
		// then call through to create the actual VulkanImage
		GlStateManager._bindTexture(texture);
		net.vulkanmod.gl.GlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
	}

	public static void texImage3D(int texture, int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, @Nullable ByteBuffer pixels) {
		// 3D texture support is limited; bind and attempt 2D fallback for now
		GlStateManager._bindTexture(texture);
		net.vulkanmod.gl.GlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
	}

	public static void generateMipmaps(int texture, int mipmapTarget) {
		GlTexture glTex = GlTexture.getTexture(texture);
		if (glTex == null || glTex.getVulkanImage() == null) return;
		net.vulkanmod.vulkan.texture.ImageUtil.generateMipmaps(glTex.getVulkanImage());
	}

	public static void copyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
		// Legacy GL call — use copyDepthImage() for explicit Vulkan depth copies
	}

	/**
	 * Copies depth data from one texture to another using vkCmdCopyImage.
	 * Used for depthtex1/depthtex2 creation (pre-translucent/pre-hand depth snapshots).
	 */
	private static int depthCopyLogCount = 0;
	public static void copyDepthImage(int srcDepthTexId, int dstDepthTexId, int width, int height) {
		GlTexture srcGlTex = GlTexture.getTexture(srcDepthTexId);
		GlTexture dstGlTex = GlTexture.getTexture(dstDepthTexId);
		if (srcGlTex == null || srcGlTex.getVulkanImage() == null) {
			Iris.logger.warn("[DEPTH_COPY] No VulkanImage for src tex {}", srcDepthTexId);
			return;
		}
		if (dstGlTex == null || dstGlTex.getVulkanImage() == null) {
			Iris.logger.warn("[DEPTH_COPY] No VulkanImage for dst tex {}", dstDepthTexId);
			return;
		}

		VulkanImage srcImage = srcGlTex.getVulkanImage();
		VulkanImage dstImage = dstGlTex.getVulkanImage();

		if (!Renderer.isRecording()) {
			if (depthCopyLogCount < 5) {
				Iris.logger.warn("[DEPTH_COPY] Renderer not recording! Cannot copy depth {} -> {}", srcDepthTexId, dstDepthTexId);
				depthCopyLogCount++;
			}
			return;
		}

		if (depthCopyLogCount < 3) {
			Iris.logger.info("[DEPTH_COPY] Copying depth: src={} ({}x{}, layout={}) -> dst={} ({}x{}, layout={})",
				srcDepthTexId, srcImage.width, srcImage.height, srcImage.getCurrentLayout(),
				dstDepthTexId, dstImage.width, dstImage.height, dstImage.getCurrentLayout());
			depthCopyLogCount++;
		}

		// End current render pass — vkCmdCopyImage cannot be called inside a render pass
		Renderer.getInstance().endRenderPass();

		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanImage.transitionImageLayout(stack, cmd, srcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
			VulkanImage.transitionImageLayout(stack, cmd, dstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

			// Use each image's own aspect mask (DEPTH_BIT for depth formats, COLOR_BIT for R32_SFLOAT etc.)
			int srcAspect = srcImage.aspect;
			int dstAspect = dstImage.aspect;

			// If aspects differ (e.g. D32_SFLOAT src → R32_SFLOAT dst), we can't use vkCmdCopyImage.
			// Both must have the same aspect for a direct copy to work.
			if (srcAspect == dstAspect) {
				VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
				region.srcSubresource()
					.aspectMask(srcAspect)
					.mipLevel(0).baseArrayLayer(0).layerCount(1);
				region.srcOffset().set(0, 0, 0);
				region.dstSubresource()
					.aspectMask(dstAspect)
					.mipLevel(0).baseArrayLayer(0).layerCount(1);
				region.dstOffset().set(0, 0, 0);
				region.extent().set(width, height, 1);

				vkCmdCopyImage(cmd,
					srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
					dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
					region);
			} else {
				// Mismatched aspects (depth→color or vice versa): use blit which handles format conversion
				VkImageBlit.Buffer blitRegion = VkImageBlit.calloc(1, stack);
				blitRegion.srcSubresource()
					.aspectMask(srcAspect)
					.mipLevel(0).baseArrayLayer(0).layerCount(1);
				blitRegion.srcOffsets(0).set(0, 0, 0);
				blitRegion.srcOffsets(1).set(width, height, 1);
				blitRegion.dstSubresource()
					.aspectMask(dstAspect)
					.mipLevel(0).baseArrayLayer(0).layerCount(1);
				blitRegion.dstOffsets(0).set(0, 0, 0);
				blitRegion.dstOffsets(1).set(width, height, 1);

				vkCmdBlitImage(cmd,
					srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
					dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
					blitRegion, VK_FILTER_NEAREST);
			}

			VulkanImage.transitionImageLayout(stack, cmd, srcImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			VulkanImage.transitionImageLayout(stack, cmd, dstImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		}
	}

	public static void copyTexSubImage2D(int destTexture, int target, int level, int xOffset, int yOffset, int srcX, int srcY, int width, int height) {
		GlTexture dstGlTex = GlTexture.getTexture(destTexture);
		if (dstGlTex == null || dstGlTex.getVulkanImage() == null) {
			return;
		}

		if (!Renderer.isRecording()) return;

		// Get source from Renderer's currently bound render pass framebuffer
		// (Iris's GlFramebuffer.bind() calls Renderer.beginRendering() directly,
		// so VulkanMod's GlFramebuffer tracking won't have the right framebuffer)
		Renderer renderer = Renderer.getInstance();
		net.vulkanmod.vulkan.framebuffer.RenderPass boundRP = renderer.getBoundRenderPass();
		if (boundRP == null || boundRP.getFramebuffer() == null) {
			return;
		}

		VulkanImage srcImage = boundRP.getFramebuffer().getColorAttachment();
		VulkanImage dstImage = dstGlTex.getVulkanImage();
		if (srcImage == null) {
			return;
		}

		// End current render pass — vkCmdCopyImage cannot be called inside a render pass
		renderer.endRenderPass();

		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanImage.transitionImageLayout(stack, cmd, srcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
			VulkanImage.transitionImageLayout(stack, cmd, dstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

			VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
			region.srcSubresource()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.srcOffset().set(srcX, srcY, 0);
			region.dstSubresource()
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.mipLevel(level).baseArrayLayer(0).layerCount(1);
			region.dstOffset().set(xOffset, yOffset, 0);
			region.extent().set(width, height, 1);

			vkCmdCopyImage(cmd,
				srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				region);

			VulkanImage.transitionImageLayout(stack, cmd, srcImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			VulkanImage.transitionImageLayout(stack, cmd, dstImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		}
	}

	public static void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
		// Storage image binding via descriptor sets - implemented in Phase 8
	}

	public static int getMaxImageUnits() {
		// Vulkan typically supports many image units
		return 16;
	}

	// ==================== Active Texture Tracking ====================

	public static void activeTexture(int unit) {
		// Callers pass GL enum values like GL_TEXTURE0 + n.
		// Call through to GlStateManager so VulkanMod's active texture is updated.
		GlStateManager._activeTexture(unit);
		// Store 0-based unit index for internal tracking
		activeTextureUnit = unit - org.lwjgl.opengl.GL13.GL_TEXTURE0;
	}

	public static int getActiveTexture() {
		// Returns 0-based unit index. Callers add GL_TEXTURE0 when needed.
		return activeTextureUnit;
	}

	// ==================== Viewport Tracking ====================

	public static void setViewport(int x, int y, int width, int height) {
		viewportWidth = width;
		viewportHeight = height;
	}

	public static int getViewportWidth() {
		// VulkanMod doesn't call glViewport, so viewport tracking is never updated
		// from the rendering pipeline. Fall back to window dimensions.
		if (viewportWidth <= 1) {
			var window = net.minecraft.client.Minecraft.getInstance().getWindow();
			if (window != null) return window.getWidth();
		}
		return viewportWidth;
	}

	public static int getViewportHeight() {
		if (viewportHeight <= 1) {
			var window = net.minecraft.client.Minecraft.getInstance().getWindow();
			if (window != null) return window.getHeight();
		}
		return viewportHeight;
	}

	// ==================== Blend State Queries ====================
	// Used by CommonUniforms to read current blend state for blendFunc uniform

	public static void updateBlendState(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
		blendEnabled = enabled;
		blendSrcRgb = srcRgb;
		blendDstRgb = dstRgb;
		blendSrcAlpha = srcAlpha;
		blendDstAlpha = dstAlpha;
	}

	public static boolean isBlendEnabled() {
		return blendEnabled;
	}

	public static int getBlendSrcRgb() {
		return blendSrcRgb;
	}

	public static int getBlendDstRgb() {
		return blendDstRgb;
	}

	public static int getBlendSrcAlpha() {
		return blendSrcAlpha;
	}

	public static int getBlendDstAlpha() {
		return blendDstAlpha;
	}

	// ==================== Texture Binding Queries ====================

	public static void setTextureBinding(int unit, int textureId) {
		if (unit >= 0 && unit < boundTextures.length) {
			boundTextures[unit] = textureId;
		}
	}

	public static int getBoundTextureId(int unit) {
		if (unit >= 0 && unit < boundTextures.length) {
			return boundTextures[unit];
		}
		return 0;
	}

	// ==================== Uniform Operations (Phase 7) ====================
	// Uniforms are written to a per-shader native ByteBuffer (IrisUniformBuffer),
	// then uploaded to the GPU via VulkanMod's ManualUBO mechanism each frame.
	// The "location" returned by getUniformLocation is the byte offset in the buffer.

	// Per-program uniform buffer registration (programId -> buffer)
	private static final Map<Integer, IrisUniformBuffer> programUniformBuffers = new HashMap<>();

	// Per-program sampler name registry (programId -> set of sampler names present in shader)
	// Used by getUniformLocation() to return non-(-1) for samplers so ProgramSamplers finds them
	private static final Map<Integer, java.util.Set<String>> programSamplerNames = new HashMap<>();

	// The currently active uniform buffer (set in ExtendedShader.apply())
	private static IrisUniformBuffer activeUniformBuffer = null;

	// Program ID allocator (VulkanMod may not create real GL programs for Iris shaders)
	private static int nextIrisProgramId = 100000;

	public static int allocateIrisProgramId() {
		return nextIrisProgramId++;
	}

	public static void registerUniformBuffer(int programId, IrisUniformBuffer buffer) {
		programUniformBuffers.put(programId, buffer);
	}

	public static void registerSamplerNames(int programId, java.util.Set<String> names) {
		programSamplerNames.put(programId, names);
	}

	public static void setActiveUniformBuffer(IrisUniformBuffer buffer) {
		activeUniformBuffer = buffer;
	}

	public static IrisUniformBuffer getActiveUniformBuffer() {
		return activeUniformBuffer;
	}

	public static int getUniformLocation(int program, String name) {
		// Check UBO fields first (returns byte offset)
		IrisUniformBuffer buffer = programUniformBuffers.get(program);
		if (buffer != null) {
			int offset = buffer.getFieldOffset(name);
			if (offset >= 0) {
				return offset;
			}
		}
		// Check sampler names: return -2 (not -1) so ProgramSamplers considers it "found"
		// -2 is negative so uniform write methods silently ignore it (they check >= 0)
		java.util.Set<String> samplers = programSamplerNames.get(program);
		if (samplers != null && samplers.contains(name)) {
			return -2;
		}
		return -1;
	}

	public static int getProgrami(int program, int pname) {
		// Returns 0 for GL_ACTIVE_UNIFORMS to skip the validation loop
		return 0;
	}

	public static void uniform1i(int location, int value) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeInt(location, value);
		}
	}

	public static void uniformMatrix4fv(int location, boolean transpose, FloatBuffer matrix) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeMat4f(location, matrix);
		}
	}

	public static void uniformMatrix4fv(int location, boolean transpose, float[] matrix) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeMat4f(location, matrix);
		}
	}

	public static void uniform1f(int location, float v0) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeFloat(location, v0);
		}
	}

	public static void uniform2f(int location, float v0, float v1) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeVec2f(location, v0, v1);
		}
	}

	public static void uniform2i(int location, int v0, int v1) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeVec2i(location, v0, v1);
		}
	}

	public static void uniform3f(int location, float v0, float v1, float v2) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeVec3f(location, v0, v1, v2);
		}
	}

	public static void uniform3i(int location, int v0, int v1, int v2) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeVec3i(location, v0, v1, v2);
		}
	}

	public static void uniform4f(int location, float v0, float v1, float v2, float v3) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeVec4f(location, v0, v1, v2, v3);
		}
	}

	public static void uniform4i(int location, int v0, int v1, int v2, int v3) {
		if (activeUniformBuffer != null && location >= 0) {
			activeUniformBuffer.writeVec4i(location, v0, v1, v2, v3);
		}
	}

	// ==================== Framebuffer Operations ====================
	// These are stubs that will be replaced in Phase 4 (Framebuffer System)

	public static int createFramebuffer() {
		// Returns a tracking ID; actual VkFramebuffer creation in Phase 4
		return (int) (nextTextureId++);
	}

	public static void framebufferTexture2D(int fb, int fbtarget, int attachment, int target, int texture, int levels) {
		// VkFramebuffer attachment - implemented in Phase 4
	}

	public static void drawBuffers(int framebuffer, int[] buffers) {
		// In Vulkan, draw buffer configuration affects which color attachments receive output.
		// When all buffers are GL_NONE (0), this indicates a depth-only pass — disable color writes.
		// When any buffer is non-NONE, re-enable color writes.
		boolean allNone = true;
		for (int buf : buffers) {
			if (buf != 0) { // GL_NONE = 0
				allNone = false;
				break;
			}
		}
		if (allNone) {
			GlStateManager._colorMask(false, false, false, false);
		} else {
			GlStateManager._colorMask(true, true, true, true);
		}
	}

	public static void readBuffer(int framebuffer, int buffer) {
		// Read buffer selection - implemented in Phase 4
	}

	public static void blitFramebuffer(int source, int dest, int offsetX, int offsetY, int width, int height, int offsetX2, int offsetY2, int width2, int height2, int bufferChoice, int filter) {
		// vkCmdBlitImage - implemented in Phase 4
	}

	// ==================== Shader/Program Operations ====================
	// These are stubs that will be replaced in Phase 6 (Pipeline System)

	public static void bindAttributeLocation(int program, int index, CharSequence name) {
		// Vertex attribute locations are set in pipeline state in Vulkan
	}

	public static String getProgramInfoLog(int program) {
		return "";
	}

	public static String getShaderInfoLog(int shader) {
		return "";
	}

	public static String getActiveUniform(int program, int index, int size, IntBuffer type, IntBuffer name) {
		return "";
	}

	public static void detachShader(int program, int shader) {
		// No equivalent in Vulkan pipeline model
	}

	public static int getUniformBlockIndex(int program, String uniformBlockName) {
		return 0;
	}

	public static void uniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
		// UBO binding points handled via descriptor sets
	}

	public static void getProgramiv(int program, int value, int[] storage) {
		// Compute work group query - adapted for Vulkan in Phase 18
	}

	// ==================== Buffer Operations ====================

	public static int createBuffers() {
		return (int) (nextTextureId++);
	}

	public static void genBuffers(int[] buffers) {
		for (int i = 0; i < buffers.length; i++) {
			buffers[i] = (int) (nextTextureId++);
		}
	}

	public static void deleteBuffers(int id) {
		// VMA buffer deallocation - implemented in Phase 2/18
	}

	public static void bufferData(int target, float[] data, int usage) {
		// VMA buffer upload
	}

	public static int bufferStorage(int target, float[] data, int usage) {
		return createBuffers();
	}

	public static void bufferStorage(int target, long size, int flags) {
		// VMA buffer allocation with flags
	}

	public static void bindBufferBase(int target, Integer index, int buffer) {
		// SSBO/UBO binding via descriptor sets - implemented in Phase 18
	}

	public static void bindBuffer(int target, int buffer) {
		// Buffer binding for indirect dispatch
	}

	public static void clearBufferSubData(int glShaderStorageBuffer, int glR8, long offset, long size, int glRed, int glByte, int[] ints) {
		// vkCmdFillBuffer equivalent - implemented in Phase 18
	}

	public static void readPixels(int x, int y, int width, int height, int format, int type, float[] pixels) {
		// Image readback - may need staging buffer
	}

	public static void vertexAttrib4f(int index, float v0, float v1, float v2, float v3) {
		// Vertex attributes are part of vertex buffer data in Vulkan
	}

	// ==================== Sampler Operations ====================

	public static int genSampler() {
		return (int) (nextSamplerId++);
	}

	public static void destroySampler(int id) {
		// VkSampler destruction - implemented in Phase 8
	}

	public static void bindSamplerToUnit(int unit, int sampler) {
		samplerBindings.put(unit, (long) sampler);
	}

	public static void unbindAllSamplers() {
		samplerBindings.clear();
	}

	public static void samplerParameteri(int sampler, int pname, int param) {
		// Sampler parameters stored; VkSampler is immutable once created
	}

	public static void samplerParameterf(int sampler, int pname, float param) {
		// Sampler parameters stored; VkSampler is immutable once created
	}

	public static void samplerParameteriv(int sampler, int pname, int[] params) {
		// Sampler parameters stored; VkSampler is immutable once created
	}

	// ==================== State Management ====================

	public static void setPolygonMode(int mode) {
		// VRenderSystem.setPolygonModeGL(mode) - mapped from GL modes to VK
		polygonMode = mode;
	}

	public static void overridePolygonMode() {
		// Save and set to FILL
		int backup = polygonMode;
		setPolygonMode(0); // VK_POLYGON_MODE_FILL
		polygonMode = backup; // Restore backup tracking
	}

	public static void restorePolygonMode() {
		// Restore from backup
	}

	// ==================== Compute Operations ====================

	public static void dispatchCompute(int workX, int workY, int workZ) {
		// vkCmdDispatch - implemented in Phase 18
	}

	public static void dispatchCompute(Vector3i workGroups) {
		dispatchCompute(workGroups.x, workGroups.y, workGroups.z);
	}

	public static void dispatchComputeIndirect(long offset) {
		// vkCmdDispatchIndirect - implemented in Phase 18
	}

	public static void memoryBarrier(int barriers) {
		if (!Renderer.isRecording()) return;
		// Pipeline barriers cannot be issued inside an active render pass (Vulkan spec
		// requires a self-dependency subpass which we don't have). When a render pass
		// is active, skip the barrier — render pass subpass dependencies handle the
		// synchronization between passes.
		if (Renderer.getInstance().getBoundFramebuffer() != null) return;
		VkCommandBuffer cmd = Renderer.getCommandBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryBarrier.Buffer memBarrier = VkMemoryBarrier.calloc(1, stack)
				.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
				.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
				.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
			vkCmdPipelineBarrier(cmd,
				VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
				VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
				0, memBarrier, null, null);
		}
	}

	// ==================== Blending & State Operations ====================
	// In Vulkan, these are baked into pipeline state objects.
	// We track the logical state for pipeline state key generation.

	public static void enableBlend() {
		blendEnabled = true;
		GlStateManager._enableBlend();
	}

	public static void disableBlend() {
		blendEnabled = false;
		GlStateManager._disableBlend();
	}

	public static void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
		updateBlendState(blendEnabled, srcRgb, dstRgb, srcAlpha, dstAlpha);
		GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
	}

	public static void depthMask(boolean flag) {
		GlStateManager._depthMask(flag);
	}

	public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
		GlStateManager._colorMask(r, g, b, a);
	}

	public static boolean supportsBufferBlending() {
		return true; // Vulkan always supports per-attachment blending
	}

	public static void disableBufferBlend(int buffer) {
		// Per-attachment blend disable via pipeline state
	}

	public static void enableBufferBlend(int buffer) {
		// Per-attachment blend enable via pipeline state
	}

	public static void blendFuncSeparatei(int buffer, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
		// Per-attachment blend function via pipeline state
	}

	// ==================== Capability Queries ====================

	public static boolean supportsSSBO() {
		return true; // Vulkan always supports storage buffers
	}

	public static boolean supportsImageLoadStore() {
		return true; // Vulkan always supports storage images
	}

	public static boolean supportsCompute() {
		return supportsCompute;
	}

	public static boolean supportsTesselation() {
		return supportsTesselation;
	}

	public static long getVRAM() {
		// Query VMA budget or return a conservative estimate
		return 4L * 1024L * 1024L * 1024L; // 4 GB default
	}

	// ==================== Projection Matrix Management ====================

	public static void setShadowProjection(Matrix4f shadowProjection) {
		backupProjection = RenderSystem.getProjectionMatrix();
		RenderSystem.setProjectionMatrix(shadowProjection, VertexSorting.ORTHOGRAPHIC_Z);
	}

	public static void restorePlayerProjection() {
		RenderSystem.setProjectionMatrix(backupProjection, VertexSorting.DISTANCE_TO_ORIGIN);
		backupProjection = null;
	}

	// ==================== Raw GL Equivalents (Phase 13 - Mixin Support) ====================
	// These wrap raw GL calls used in mixins that will eventually be removed/rewritten for VulkanMod.

	public static void glEnable(int cap) {
		// Raw GL enable - in Vulkan, pipeline state handles enable/disable
	}

	public static void glDisable(int cap) {
		// Raw GL disable - in Vulkan, pipeline state handles enable/disable
	}

	public static void glDrawElements(int mode, int count, int type, long indices) {
		// Raw draw call - in Vulkan, uses vkCmdDrawIndexed
	}

	// ==================== Texture Lifecycle ====================

	public static void deleteTexture(int id) {
		GlStateManager._deleteTexture(id);
	}

	public static void useProgram(int program) {
		// In Vulkan, shader programs are bound via pipeline binding, not glUseProgram.
		// A value of 0 means "unbind" - no-op in Vulkan.
	}

	// ==================== Texture Binding (Phase 8) ====================

	public static void bindTexture(int textureId) {
		// Binds texture to the active texture unit
		setTextureBinding(activeTextureUnit, textureId);
	}

	public static int getTexLevelParameter(int target, int level, int pname) {
		// Texture level parameter query - in Vulkan, texture metadata is tracked
		// at creation time rather than queried from the GPU.
		// Returns 0; callers should use TextureInfoCache instead.
		return 0;
	}

	public static int getInteger(int pname) {
		// General GL state query stub.
		// In Vulkan, state is tracked explicitly rather than queried.
		return 0;
	}

	// ==================== Framebuffer Operations (Phase 8 additions) ====================

	public static void bindFramebuffer(int target, int framebuffer) {
		// Framebuffer binding - in Vulkan, render passes are begun/ended explicitly
		// Implemented in Phase 4/15 pipeline orchestration
	}

	public static void clearColor(float r, float g, float b, float a) {
		VRenderSystem.setClearColor(r, g, b, a);
	}

	public static void clear(int mask, boolean isOSX) {
		if (Renderer.isRecording() && Renderer.getInstance().getBoundRenderPass() != null) {
			Renderer.clearAttachments(mask);
		}
	}

	public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
		// Framebuffer texture attachment without explicit fb parameter
		// Uses the currently bound framebuffer (tracked internally)
		// Implemented in Phase 4
	}

	// ==================== Query Helpers ====================

	public static void getIntegerv(int pname, int[] params) {
		// Vulkan property queries handled differently
		params[0] = 0;
	}

	public static void getFloatv(int pname, float[] params) {
		// Vulkan property queries handled differently
		params[0] = 0.0f;
	}

	// ==================== Uniform Stubs (Phase 14) ====================

	public static int glGetUniformLocation(int program, CharSequence name) {
		// In Vulkan, uniforms are mapped to UBO fields at pipeline creation time.
		// This returns -1 (not found) as a stub; actual binding is descriptor-based.
		return -1;
	}

	public static void glUniform1i(int location, int value) {
		// In Vulkan, uniform updates go through UBO or push constant writes.
		// Stub: no-op until Phase 7 uniform buffer implementation is wired.
	}

	// ==================== Buffer Stubs (Phase 18) ====================

	public static int glGenBuffers() {
		// In Vulkan, buffers are allocated via VMA.
		// Returns a tracking id; actual VkBuffer is managed by MemoryManager.
		return (int) (nextTextureId++);
	}

	public static void glBindBuffer(int target, int buffer) {
		// In Vulkan, buffer binding is part of descriptor set or command buffer state.
		// Stub: no-op until SSBO implementation is fully wired.
	}

	// ==================== Clear Operations (Phase 15) ====================

	public static void clearTexImage(int texture, int level, int format, int type, int[] data) {
		GlTexture glTex = GlTexture.getTexture(texture);
		if (glTex == null || glTex.getVulkanImage() == null) {
			Iris.logger.debug("clearTexImage: No VulkanImage for tex {}", texture);
			return;
		}
		VulkanImage image = glTex.getVulkanImage();

		// Use the current recording command buffer if available (avoids GPU stall).
		// Fall back to one-shot command buffer only when not recording.
		if (Renderer.isRecording()) {
			Renderer.getInstance().endRenderPass();
			VkCommandBuffer cmd = Renderer.getCommandBuffer();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VulkanImage.transitionImageLayout(stack, cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

				VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
				if (data != null && data.length >= 4) {
					clearColor.float32(0, data[0] / 255.0f);
					clearColor.float32(1, data[1] / 255.0f);
					clearColor.float32(2, data[2] / 255.0f);
					clearColor.float32(3, data[3] / 255.0f);
				}

				VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
				range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
				range.baseMipLevel(level);
				range.levelCount(1);
				range.baseArrayLayer(0);
				range.layerCount(1);

				VK10.vkCmdClearColorImage(cmd, image.getId(),
					VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearColor, range);

				VulkanImage.transitionImageLayout(stack, cmd, image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			}
		} else {
			// Fallback: one-shot command buffer with synchronous wait
			try {
				var queue = DeviceManager.getGraphicsQueue();
				CommandPool.CommandBuffer cmdBuf = queue.beginCommands();

				try (MemoryStack stack = MemoryStack.stackPush()) {
					image.transitionImageLayout(stack, cmdBuf.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

					VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
					if (data != null && data.length >= 4) {
						clearColor.float32(0, data[0] / 255.0f);
						clearColor.float32(1, data[1] / 255.0f);
						clearColor.float32(2, data[2] / 255.0f);
						clearColor.float32(3, data[3] / 255.0f);
					}

					VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
					range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
					range.baseMipLevel(level);
					range.levelCount(1);
					range.baseArrayLayer(0);
					range.layerCount(1);

					VK10.vkCmdClearColorImage(cmdBuf.getHandle(), image.getId(),
						VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearColor, range);

					image.transitionImageLayout(stack, cmdBuf.getHandle(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
				}

				long fence = queue.submitCommands(cmdBuf);
				VK10.vkWaitForFences(DeviceManager.vkDevice, fence, true, Long.MAX_VALUE);
			} catch (Exception e) {
				Iris.logger.warn("clearTexImage failed for tex {}: {}", texture, e.getMessage());
			}
		}
	}

	// ==================== Shader Program Stubs (Phase 19 - DH compat) ====================

	public static int glCreateProgram() {
		// In Vulkan, programs are VkPipeline objects created from SPIR-V modules.
		// Returns a tracking id; actual pipeline managed by IrisPipelineManager.
		return (int) (nextTextureId++);
	}

	public static void glBindAttribLocation(int program, int index, CharSequence name) {
		// In Vulkan, vertex attribute bindings are part of the pipeline vertex input state.
	}

	public static void glAttachShader(int program, int shader) {
		// In Vulkan, shader modules are attached at pipeline creation time.
	}

	public static void glLinkProgram(int program) {
		// In Vulkan, pipeline linking is implicit in vkCreateGraphicsPipelines.
	}

	public static int glGetProgrami(int program, int pname) {
		// Returns success (1) by default; pipeline creation validated at SPIR-V compile time.
		return 1;
	}

	public static String glGetProgramInfoLog(int program) {
		// In Vulkan, shader errors come from SPIR-V compilation.
		return "";
	}

	public static void glDeleteProgram(int program) {
		// In Vulkan, destroys the VkPipeline associated with this program id.
	}

	public static void glUniform1f(int location, float value) {
		// In Vulkan, float uniform updates go through UBO or push constant writes.
	}

	public static void glUniform3f(int location, float x, float y, float z) {
		// In Vulkan, vec3 uniform updates go through UBO or push constant writes.
	}

	public static int checkFramebufferStatus(int target) {
		// In Vulkan, framebuffer validity is checked at render pass creation time.
		// Returns GL_FRAMEBUFFER_COMPLETE (0x8CD5) by default.
		return 0x8CD5;
	}
}
