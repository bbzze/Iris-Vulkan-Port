package net.irisshaders.iris.targets;

import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import org.joml.Vector2i;

import java.nio.ByteBuffer;

/**
 * Render target (color attachment) - Vulkan Port.
 *
 * Each render target holds a main and alt texture for ping-pong rendering.
 * In Vulkan, these will be VulkanImage objects with appropriate VkFormat.
 *
 * GlStateManager._genTextures replaced with IrisRenderSystem.createTexture.
 */
public class RenderTarget {
	private static final ByteBuffer NULL_BUFFER = null;

	// GL constant inlines
	private static final int GL_TEXTURE_2D = 0x0DE1;

	private final InternalTextureFormat internalFormat;
	private final PixelFormat format;
	private final PixelType type;
	private final int mainTexture;
	private final int altTexture;
	private int width;
	private int height;
	private boolean isValid;

	public RenderTarget(Builder builder) {
		this.isValid = true;

		this.internalFormat = builder.internalFormat;
		this.format = builder.format;
		this.type = builder.type;

		this.width = builder.width;
		this.height = builder.height;

		this.mainTexture = IrisRenderSystem.createTexture(GL_TEXTURE_2D);
		this.altTexture = IrisRenderSystem.createTexture(GL_TEXTURE_2D);

		boolean isPixelFormatInteger = builder.internalFormat.getPixelFormat().isInteger();
		setupTexture(mainTexture, builder.width, builder.height, !isPixelFormatInteger);
		setupTexture(altTexture, builder.width, builder.height, !isPixelFormatInteger);

		if (builder.name != null) {
			GLDebug.nameObject(0x1502, mainTexture, builder.name + " main");
			GLDebug.nameObject(0x1502, altTexture, builder.name + " alt");
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	private void setupTexture(int texture, int width, int height, boolean allowsLinear) {
		resizeTexture(texture, width, height);

		int filter = allowsLinear ? 0x2601 : 0x2600; // GL_LINEAR : GL_NEAREST
		IrisRenderSystem.texParameteri(texture, GL_TEXTURE_2D, 0x2801, filter); // MIN_FILTER
		IrisRenderSystem.texParameteri(texture, GL_TEXTURE_2D, 0x2800, filter); // MAG_FILTER
		IrisRenderSystem.texParameteri(texture, GL_TEXTURE_2D, 0x2802, 0x812F); // WRAP_S = CLAMP_TO_EDGE
		IrisRenderSystem.texParameteri(texture, GL_TEXTURE_2D, 0x2803, 0x812F); // WRAP_T = CLAMP_TO_EDGE
	}

	private void resizeTexture(int texture, int width, int height) {
		IrisRenderSystem.texImage2D(texture, GL_TEXTURE_2D, 0, internalFormat.getGlFormat(), width, height, 0, format.getGlFormat(), type.getGlFormat(), NULL_BUFFER);
	}

	void resize(Vector2i textureScaleOverride) {
		this.resize(textureScaleOverride.x, textureScaleOverride.y);
	}

	// Package private, call CompositeRenderTargets#resizeIfNeeded instead.
	void resize(int width, int height) {
		requireValid();

		this.width = width;
		this.height = height;

		resizeTexture(mainTexture, width, height);

		resizeTexture(altTexture, width, height);
	}

	public InternalTextureFormat getInternalFormat() {
		return internalFormat;
	}

	public int getMainTexture() {
		requireValid();

		return mainTexture;
	}

	public int getAltTexture() {
		requireValid();

		return altTexture;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public void destroy() {
		requireValid();
		isValid = false;

		// VulkanImage destruction handled in Phase 3/4
	}

	private void requireValid() {
		if (!isValid) {
			throw new IllegalStateException("Attempted to use a deleted composite render target");
		}
	}

	public static class Builder {
		private InternalTextureFormat internalFormat = InternalTextureFormat.RGBA8;
		private int width = 0;
		private int height = 0;
		private PixelFormat format = PixelFormat.RGBA;
		private PixelType type = PixelType.UNSIGNED_BYTE;
		private String name = null;

		private Builder() {
			// No-op
		}

		public Builder setName(String name) {
			this.name = name;

			return this;
		}

		public Builder setInternalFormat(InternalTextureFormat format) {
			this.internalFormat = format;

			return this;
		}

		public Builder setDimensions(int width, int height) {
			if (width <= 0) {
				throw new IllegalArgumentException("Width must be greater than zero");
			}

			if (height <= 0) {
				throw new IllegalArgumentException("Height must be greater than zero");
			}

			this.width = width;
			this.height = height;

			return this;
		}

		public Builder setPixelFormat(PixelFormat pixelFormat) {
			this.format = pixelFormat;

			return this;
		}

		public Builder setPixelType(PixelType pixelType) {
			this.type = pixelType;

			return this;
		}

		public RenderTarget build() {
			return new RenderTarget(this);
		}
	}
}
