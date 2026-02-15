package net.irisshaders.iris.gl.sampler;

import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;

/**
 * Sampler resource wrapper - Vulkan Port.
 *
 * In OpenGL, samplers were separate objects that override texture parameters.
 * In Vulkan, VkSampler is an immutable object created with VkSamplerCreateInfo
 * specifying min/mag filter, mipmap mode, address mode, compare op, etc.
 *
 * Actual VkSampler creation will happen in Phase 8 (Sampler & Image Binding).
 * This class stores the sampler configuration for deferred creation.
 */
public class GlSampler extends GlResource {
	private final boolean linear;
	private final boolean mipmapped;
	private final boolean shadow;
	private final boolean hardwareShadow;

	public GlSampler(boolean linear, boolean mipmapped, boolean shadow, boolean hardwareShadow) {
		super(IrisRenderSystem.genSampler());

		this.linear = linear;
		this.mipmapped = mipmapped;
		this.shadow = shadow;
		this.hardwareShadow = hardwareShadow;

		// Store parameters via IrisRenderSystem stubs for later VkSampler creation
		int minFilter = linear ? 0x2601 : 0x2600; // GL_LINEAR : GL_NEAREST
		int magFilter = linear ? 0x2601 : 0x2600;
		IrisRenderSystem.samplerParameteri(getId(), 0x2801, minFilter); // GL_TEXTURE_MIN_FILTER
		IrisRenderSystem.samplerParameteri(getId(), 0x2800, magFilter); // GL_TEXTURE_MAG_FILTER
		IrisRenderSystem.samplerParameteri(getId(), 0x2802, 0x812F); // GL_TEXTURE_WRAP_S = GL_CLAMP_TO_EDGE
		IrisRenderSystem.samplerParameteri(getId(), 0x2803, 0x812F); // GL_TEXTURE_WRAP_T = GL_CLAMP_TO_EDGE

		if (mipmapped) {
			int mipmapFilter = linear ? 0x2703 : 0x2700; // GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST
			IrisRenderSystem.samplerParameteri(getId(), 0x2801, mipmapFilter); // GL_TEXTURE_MIN_FILTER
		}

		if (hardwareShadow) {
			IrisRenderSystem.samplerParameteri(getId(), 0x884C, 0x884E); // GL_TEXTURE_COMPARE_MODE = GL_COMPARE_REF_TO_TEXTURE
		}
	}

	public boolean isLinear() {
		return linear;
	}

	public boolean isMipmapped() {
		return mipmapped;
	}

	public boolean isShadow() {
		return shadow;
	}

	public boolean isHardwareShadow() {
		return hardwareShadow;
	}

	@Override
	protected void destroyInternal() {
		IrisRenderSystem.destroySampler(getGlId());
	}

	public int getId() {
		return getGlId();
	}
}
