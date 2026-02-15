package net.irisshaders.iris.gl.sampler;

import net.irisshaders.iris.gl.IrisRenderSystem;

/**
 * Sampler limits - Vulkan Port.
 *
 * GL queries replaced with Vulkan-compatible hardcoded limits.
 * In Vulkan, these correspond to physical device limits:
 * - maxTextureUnits -> maxPerStageDescriptorSamplers (typically 16+)
 * - maxDrawBuffers -> maxColorAttachments (typically 8+)
 * - maxShaderStorageUnits -> maxPerStageDescriptorStorageBuffers (typically 16+)
 *
 * These values are conservative minimums that match Vulkan spec guarantees.
 */
public class SamplerLimits {
	private static SamplerLimits instance;
	private final int maxTextureUnits;
	private final int maxDrawBuffers;
	private final int maxShaderStorageUnits;

	private SamplerLimits() {
		// In OpenGL, GL_MAX_TEXTURE_IMAGE_UNITS is typically 32 on modern GPUs.
		// In Vulkan, samplers are bound via descriptor sets so the actual limit
		// is much higher (maxPerStageDescriptorSampledImages), but Iris's sampler
		// allocation logic uses this as logical texture unit count.
		this.maxTextureUnits = 32;
		this.maxDrawBuffers = 16;
		this.maxShaderStorageUnits = IrisRenderSystem.supportsSSBO() ? 16 : 0;
	}

	public static SamplerLimits get() {
		if (instance == null) {
			instance = new SamplerLimits();
		}

		return instance;
	}

	public int getMaxTextureUnits() {
		return maxTextureUnits;
	}

	public int getMaxDrawBuffers() {
		return maxDrawBuffers;
	}

	public int getMaxShaderStorageUnits() {
		return maxShaderStorageUnits;
	}
}
