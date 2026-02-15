package net.irisshaders.iris.gl.texture;

import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;

import java.nio.ByteBuffer;
import java.util.function.IntSupplier;

/**
 * Texture resource wrapper - Vulkan Port.
 *
 * In OpenGL, this created a GL texture with glGenTexture + glTexImage + glTexParameter.
 * In Vulkan, textures are VulkanImage objects with VkImageView and VkSampler.
 *
 * Actual VulkanImage creation will happen in Phase 3 (Texture System Bridge).
 * This class stores configuration metadata for deferred Vulkan resource creation.
 */
public class GlTexture extends GlResource implements TextureAccess {
	private final TextureType target;
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int internalFormat;
	private final boolean blur;
	private final boolean clamp;
	private byte[] pixelData;

	public GlTexture(TextureType target, int sizeX, int sizeY, int sizeZ, int internalFormat, int format, int pixelType, byte[] pixels, TextureFilteringData filteringData) {
		super(IrisRenderSystem.createTexture(target.getGlType()));

		this.target = target;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.internalFormat = internalFormat;
		this.blur = filteringData.shouldBlur();
		this.clamp = filteringData.shouldClamp();

		// Store pixel data for later upload via VulkanImage in Phase 3
		if (pixels != null) {
			this.pixelData = pixels.clone();
		}

		IrisRenderSystem.bindTextureForSetup(target.getGlType(), getGlId());

		TextureUploadHelper.resetTextureUploadState();

		ByteBuffer buffer = null;
		if (pixels != null) {
			buffer = ByteBuffer.allocateDirect(pixels.length);
			buffer.put(pixels);
			buffer.flip();
		}
		target.apply(this.getGlId(), sizeX, sizeY, sizeZ, internalFormat, format, pixelType, buffer);

		int texture = this.getGlId();

		// Filtering: LINEAR or NEAREST
		int minFilter = blur ? 0x2601 : 0x2600; // GL_LINEAR : GL_NEAREST
		int magFilter = blur ? 0x2601 : 0x2600;
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x2801, minFilter); // GL_TEXTURE_MIN_FILTER
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x2800, magFilter); // GL_TEXTURE_MAG_FILTER

		// Wrap: CLAMP_TO_EDGE or REPEAT
		int wrapMode = clamp ? 0x812F : 0x2901; // GL_CLAMP_TO_EDGE : GL_REPEAT
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x2802, wrapMode); // GL_TEXTURE_WRAP_S

		if (sizeY > 0) {
			IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x2803, wrapMode); // GL_TEXTURE_WRAP_T
		}

		if (sizeZ > 0) {
			IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x8072, wrapMode); // GL_TEXTURE_WRAP_R
		}

		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x813D, 0); // GL_TEXTURE_MAX_LEVEL
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x813A, 0); // GL_TEXTURE_MIN_LOD
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x813B, 0); // GL_TEXTURE_MAX_LOD
		IrisRenderSystem.texParameterf(texture, target.getGlType(), 0x8501, 0.0F); // GL_TEXTURE_LOD_BIAS

		IrisRenderSystem.bindTextureForSetup(target.getGlType(), 0);
	}

	public TextureType getTarget() {
		return target;
	}

	public int getSizeX() {
		return sizeX;
	}

	public int getSizeY() {
		return sizeY;
	}

	public int getSizeZ() {
		return sizeZ;
	}

	public boolean isBlur() {
		return blur;
	}

	public boolean isClamp() {
		return clamp;
	}

	public void bind(int unit) {
		IrisRenderSystem.bindTextureToUnit(target.getGlType(), unit, getGlId());
	}

	@Override
	public TextureType getType() {
		return target;
	}

	@Override
	public IntSupplier getTextureId() {
		return this::getGlId;
	}

	@Override
	protected void destroyInternal() {
		// VulkanImage destruction will be handled in Phase 3
	}
}
