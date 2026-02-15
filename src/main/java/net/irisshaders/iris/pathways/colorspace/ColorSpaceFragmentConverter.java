package net.irisshaders.iris.pathways.colorspace;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.apache.commons.io.IOUtils;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Color space fragment converter - Vulkan Port.
 *
 * GlStateManager calls replaced with IrisRenderSystem.
 * GL11C/GL30C constants inlined as hex values.
 */
public class ColorSpaceFragmentConverter implements ColorSpaceConverter {
	// GL constants (inlined from GL11C/GL30C)
	private static final int GL_TEXTURE_2D = 0x0DE1;
	private static final int GL_RGBA8 = 0x8058;
	private static final int GL_RGBA = 0x1908;
	private static final int GL_UNSIGNED_BYTE = 0x1401;

	private int width;
	private int height;
	private ColorSpace colorSpace;
	private Program program;
	private GlFramebuffer framebuffer;
	private int swapTexture;

	private int target;

	public ColorSpaceFragmentConverter(int width, int height, ColorSpace colorSpace) {
		rebuildProgram(width, height, colorSpace);
	}

	public void rebuildProgram(int width, int height, ColorSpace colorSpace) {
		if (program != null) {
			program.destroy();
			program = null;
			framebuffer.destroy();
			framebuffer = null;
			IrisRenderSystem.deleteTexture(swapTexture);
			swapTexture = 0;
		}

		this.width = width;
		this.height = height;
		this.colorSpace = colorSpace;

		String vertexSource;
		String source;
		try {
			vertexSource = new String(IOUtils.toByteArray(Objects.requireNonNull(getClass().getResourceAsStream("/colorSpace.vsh"))), StandardCharsets.UTF_8);
			source = new String(IOUtils.toByteArray(Objects.requireNonNull(getClass().getResourceAsStream("/colorSpace.csh"))), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		List<StringPair> defineList = new ArrayList<>();
		defineList.add(new StringPair("CURRENT_COLOR_SPACE", String.valueOf(colorSpace.ordinal())));

		for (ColorSpace space : ColorSpace.values()) {
			defineList.add(new StringPair(space.name(), String.valueOf(space.ordinal())));
		}
		source = JcppProcessor.glslPreprocessSource(source, defineList);

		ProgramBuilder builder = ProgramBuilder.begin("colorSpaceFragment", vertexSource, null, source, ImmutableSet.of());

		builder.uniformMatrix(UniformUpdateFrequency.ONCE, "projection", () -> new Matrix4f(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, -1, -1, 0, 1));
		builder.addDynamicSampler(() -> target, "readImage");

		swapTexture = IrisRenderSystem.createTexture(GL_TEXTURE_2D);
		IrisRenderSystem.texImage2D(swapTexture, GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);

		this.framebuffer = new GlFramebuffer();
		framebuffer.addColorAttachment(0, swapTexture);
		this.program = builder.build();
	}

	public void process(int targetImage) {
		if (colorSpace == ColorSpace.SRGB) return;

		this.target = targetImage;
		program.use();
		framebuffer.bind();
		FullScreenQuadRenderer.INSTANCE.render();
		Program.unbind();
		framebuffer.bindAsReadBuffer();
		IrisRenderSystem.copyTexSubImage2D(targetImage, GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
	}
}
