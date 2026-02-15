package net.irisshaders.iris.gl.image;

import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;

/**
 * Image resource for shader image load/store - Vulkan Port.
 *
 * In OpenGL, this was a texture bound via glBindImageTexture for
 * imageLoad/imageStore operations in shaders (GL_ARB_shader_image_load_store).
 * In Vulkan, this uses VK_DESCRIPTOR_TYPE_STORAGE_IMAGE descriptors.
 *
 * Actual VulkanImage + storage image descriptor setup in Phase 3/8.
 * glClearTexImage is replaced by vkCmdClearColorImage or a compute clear.
 */
public class GlImage extends GlResource {
	protected final String name;
	protected final String samplerName;
	protected final TextureType target;
	protected final PixelFormat format;
	protected final InternalTextureFormat internalTextureFormat;
	protected final PixelType pixelType;
	private final boolean clear;
	protected int width;
	protected int height;
	protected int depth;

	public GlImage(String name, String samplerName, TextureType target, PixelFormat format, InternalTextureFormat internalFormat, PixelType pixelType, boolean clear, int width, int height, int depth) {
		super(IrisRenderSystem.createTexture(target.getGlType()));

		this.name = name;
		this.samplerName = samplerName;
		this.target = target;
		this.format = format;
		this.internalTextureFormat = internalFormat;
		this.pixelType = pixelType;
		this.clear = clear;
		this.width = width;
		this.height = height;
		this.depth = depth;

		GLDebug.nameObject(0x1502, getGlId(), name); // GL_TEXTURE constant placeholder

		IrisRenderSystem.bindTextureForSetup(target.getGlType(), getGlId());
		target.apply(getGlId(), width, height, depth, internalFormat.getGlFormat(), format.getGlFormat(), pixelType.getGlFormat(), null);

		int texture = getGlId();

		setup(texture, width, height, depth);

		IrisRenderSystem.bindTextureForSetup(target.getGlType(), 0);
	}

	protected void setup(int texture, int width, int height, int depth) {
		boolean isInteger = internalTextureFormat.getPixelFormat().isInteger();
		int filter = isInteger ? 0x2600 : 0x2601; // GL_NEAREST : GL_LINEAR
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x2801, filter); // GL_TEXTURE_MIN_FILTER
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x2800, filter); // GL_TEXTURE_MAG_FILTER
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x2802, 0x812F); // GL_TEXTURE_WRAP_S = GL_CLAMP_TO_EDGE

		if (height > 0) {
			IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x2803, 0x812F); // GL_TEXTURE_WRAP_T = GL_CLAMP_TO_EDGE
		}

		if (depth > 0) {
			IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x8072, 0x812F); // GL_TEXTURE_WRAP_R = GL_CLAMP_TO_EDGE
		}

		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x813D, 0); // GL_TEXTURE_MAX_LEVEL
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x813A, 0); // GL_TEXTURE_MIN_LOD
		IrisRenderSystem.texParameteri(texture, target.getGlType(), 0x813B, 0); // GL_TEXTURE_MAX_LOD
		IrisRenderSystem.texParameterf(texture, target.getGlType(), 0x8501, 0.0F); // GL_TEXTURE_LOD_BIAS

		// In Vulkan, clearing is done via vkCmdClearColorImage - scheduled in Phase 3
		// Original: ARBClearTexture.glClearTexImage(texture, 0, format, pixelType, null)
	}

	public String getName() {
		return name;
	}

	public String getSamplerName() {
		return samplerName;
	}

	public TextureType getTarget() {
		return target;
	}

	public boolean shouldClear() {
		return clear;
	}

	public int getId() {
		return getGlId();
	}

	/**
	 * This makes the image aware of a new render target. Depending on the image's properties, it may not follow these targets.
	 *
	 * @param width  The width of the main render target.
	 * @param height The height of the main render target.
	 */
	public void updateNewSize(int width, int height) {

	}

	@Override
	protected void destroyInternal() {
		// VulkanImage destruction handled in Phase 3
	}

	public InternalTextureFormat getInternalFormat() {
		return internalTextureFormat;
	}

	@Override
	public String toString() {
		return "GlImage name " + name + " format " + format + "internalformat " + internalTextureFormat + " pixeltype " + pixelType;
	}

	public PixelFormat getFormat() {
		return format;
	}

	public PixelType getPixelType() {
		return pixelType;
	}

	public static class Relative extends GlImage {

		private final float relativeHeight;
		private final float relativeWidth;

		public Relative(String name, String samplerName, PixelFormat format, InternalTextureFormat internalFormat, PixelType pixelType, boolean clear, float relativeWidth, float relativeHeight, int currentWidth, int currentHeight) {
			super(name, samplerName, TextureType.TEXTURE_2D, format, internalFormat, pixelType, clear, (int) (currentWidth * relativeWidth), (int) (currentHeight * relativeHeight), 0);

			this.relativeWidth = relativeWidth;
			this.relativeHeight = relativeHeight;
		}

		@Override
		public void updateNewSize(int width, int height) {
			IrisRenderSystem.bindTextureForSetup(target.getGlType(), getGlId());
			target.apply(getGlId(), (int) (width * relativeWidth), (int) (height * relativeHeight), 0, internalTextureFormat.getGlFormat(), format.getGlFormat(), pixelType.getGlFormat(), null);

			int texture = getGlId();

			setup(texture, width, height, 0);

			IrisRenderSystem.bindTextureForSetup(target.getGlType(), 0);
		}
	}
}
