package net.irisshaders.iris.targets.backed;

import com.mojang.blaze3d.platform.NativeImage;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Native image backed custom texture - Vulkan Port.
 *
 * GL constants inlined to remove LWJGL OpenGL dependency.
 */
public class NativeImageBackedCustomTexture extends DynamicTexture implements TextureAccess {
	// GL constants inlined
	private static final int GL_TEXTURE_2D = 0x0DE1;

	public NativeImageBackedCustomTexture(CustomTextureData.PngData textureData) throws IOException {
		super(create(textureData.getContent()));

		// By default, images are unblurred and not clamped.

		if (textureData.getFilteringData().shouldBlur()) {
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2801, 0x2601); // MIN_FILTER = LINEAR
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2800, 0x2601); // MAG_FILTER = LINEAR
		}

		if (textureData.getFilteringData().shouldClamp()) {
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2802, 0x812F); // WRAP_S = CLAMP_TO_EDGE
			IrisRenderSystem.texParameteri(getId(), GL_TEXTURE_2D, 0x2803, 0x812F); // WRAP_T = CLAMP_TO_EDGE
		}
	}

	private static NativeImage create(byte[] content) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocateDirect(content.length);
		buffer.put(content);
		buffer.flip();

		return NativeImage.read(buffer);
	}

	@Override
	public void upload() {
		NativeImage image = Objects.requireNonNull(getPixels());

		bind();
		image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), false, false, false, false);
	}

	@Override
	public TextureType getType() {
		return TextureType.TEXTURE_2D;
	}

	@Override
	public IntSupplier getTextureId() {
		return this::getId;
	}
}
