package net.irisshaders.iris.pipeline;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.samplers.IrisImages;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.BufferFlipper;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Composite pass renderer - Vulkan Port.
 *
 * GlStateManager/GlStateManagerAccessor calls replaced with IrisRenderSystem.
 * GL15C/GL20C/GL30C/GL43C constants inlined as hex values.
 */
public class CompositeRenderer {
	// GL constants (inlined)
	private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
	private static final int GL_TEXTURE_2D = 0x0DE1;
	private static final int GL_LINEAR_MIPMAP_LINEAR = 0x2703;
	private static final int GL_NEAREST_MIPMAP_NEAREST = 0x2700;
	private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
	private static final int GL_TEXTURE0 = 0x84C0;
	private static final int GL_SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x00000020;
	private static final int GL_TEXTURE_FETCH_BARRIER_BIT = 0x00000008;
	private static final int GL_SHADER_STORAGE_BARRIER_BIT = 0x00002000;

	private final RenderTargets renderTargets;

	private final ImmutableList<Pass> passes;
	private final TextureAccess noiseTexture;
	private final FrameUpdateNotifier updateNotifier;
	private final CenterDepthSampler centerDepthSampler;
	private final Object2ObjectMap<String, TextureAccess> customTextureIds;
	private final ImmutableSet<Integer> flippedAtLeastOnceFinal;
	private final CustomUniforms customUniforms;
	private final Object2ObjectMap<String, TextureAccess> irisCustomTextures;
	private final Set<GlImage> customImages;
	private final TextureStage textureStage;

	// Diagnostic: log composite texture/uniform state for first few frames
	private static int diagFrameCount = 0;
	private static int lastDiagFrame = -1;
	private final WorldRenderingPipeline pipeline;

	public CompositeRenderer(WorldRenderingPipeline pipeline, PackDirectives packDirectives, ProgramSource[] sources, ComputeSource[][] computes, RenderTargets renderTargets, ShaderStorageBufferHolder holder,
							 TextureAccess noiseTexture, FrameUpdateNotifier updateNotifier,
							 CenterDepthSampler centerDepthSampler, BufferFlipper bufferFlipper,
							 Supplier<ShadowRenderTargets> shadowTargetsSupplier, TextureStage textureStage,
							 Object2ObjectMap<String, TextureAccess> customTextureIds, Object2ObjectMap<String, TextureAccess> irisCustomTextures, Set<GlImage> customImages, ImmutableMap<Integer, Boolean> explicitPreFlips,
							 CustomUniforms customUniforms) {
		this.pipeline = pipeline;
		this.noiseTexture = noiseTexture;
		this.updateNotifier = updateNotifier;
		this.centerDepthSampler = centerDepthSampler;
		this.renderTargets = renderTargets;
		this.customTextureIds = customTextureIds;
		this.customUniforms = customUniforms;
		this.irisCustomTextures = irisCustomTextures;
		this.customImages = customImages;
		this.textureStage = textureStage;

		final PackRenderTargetDirectives renderTargetDirectives = packDirectives.getRenderTargetDirectives();
		final Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> renderTargetSettings =
			renderTargetDirectives.getRenderTargetSettings();

		final ImmutableList.Builder<Pass> passes = ImmutableList.builder();
		final ImmutableSet.Builder<Integer> flippedAtLeastOnce = new ImmutableSet.Builder<>();

		explicitPreFlips.forEach((buffer, shouldFlip) -> {
			if (shouldFlip) {
				bufferFlipper.flip(buffer);
				// NB: Flipping deferred_pre or composite_pre does NOT cause the "flippedAtLeastOnce" flag to trigger
			}
		});

		for (int i = 0; i < sources.length; i++) {
			ProgramSource source = sources[i];

			ImmutableSet<Integer> flipped = bufferFlipper.snapshot();
			ImmutableSet<Integer> flippedAtLeastOnceSnapshot = flippedAtLeastOnce.build();

			if (source == null || !source.isValid()) {
				if (computes[i] != null) {
					ComputeOnlyPass pass = new ComputeOnlyPass();
					pass.computes = createComputes(computes[i], flipped, flippedAtLeastOnceSnapshot, shadowTargetsSupplier, holder);
					passes.add(pass);
				}
				continue;
			}

			Pass pass = new Pass();
			ProgramDirectives directives = source.getDirectives();

			pass.program = createProgram(source, flipped, flippedAtLeastOnceSnapshot, shadowTargetsSupplier);
			pass.blendModeOverride = source.getDirectives().getBlendModeOverride().orElse(null);
			pass.computes = createComputes(computes[i], flipped, flippedAtLeastOnceSnapshot, shadowTargetsSupplier, holder);
			int[] drawBuffers = directives.getDrawBuffers();


			int passWidth = 0, passHeight = 0;
			// Flip the buffers that this shader wrote to, and set pass width and height
			ImmutableMap<Integer, Boolean> explicitFlips = directives.getExplicitFlips();

			GlFramebuffer framebuffer = renderTargets.createColorFramebuffer(flipped, drawBuffers);

			for (int buffer : drawBuffers) {
				RenderTarget target = renderTargets.get(buffer);
				if ((passWidth > 0 && passWidth != target.getWidth()) || (passHeight > 0 && passHeight != target.getHeight())) {
					throw new IllegalStateException("Pass sizes must match for drawbuffers " + Arrays.toString(drawBuffers) + "\nOriginal width: " + passWidth + " New width: " + target.getWidth() + " Original height: " + passHeight + " New height: " + target.getHeight());
				}
				passWidth = target.getWidth();
				passHeight = target.getHeight();

				// compare with boxed Boolean objects to avoid NPEs
				if (explicitFlips.get(buffer) == Boolean.FALSE) {
					continue;
				}

				bufferFlipper.flip(buffer);
				flippedAtLeastOnce.add(buffer);
			}

			explicitFlips.forEach((buffer, shouldFlip) -> {
				if (shouldFlip) {
					bufferFlipper.flip(buffer);
					flippedAtLeastOnce.add(buffer);
				}
			});

			pass.drawBuffers = directives.getDrawBuffers();
			pass.viewWidth = passWidth;
			pass.viewHeight = passHeight;
			pass.stageReadsFromAlt = flipped;
			pass.framebuffer = framebuffer;
			pass.viewportScale = directives.getViewportScale();
			pass.mipmappedBuffers = directives.getMipmappedBuffers();
			pass.flippedAtLeastOnce = flippedAtLeastOnceSnapshot;

			passes.add(pass);
		}

		this.passes = passes.build();
		this.flippedAtLeastOnceFinal = flippedAtLeastOnce.build();

		IrisRenderSystem.bindFramebuffer(GL_READ_FRAMEBUFFER, 0);
	}

	private static void setupMipmapping(net.irisshaders.iris.targets.RenderTarget target, boolean readFromAlt) {
		if (target == null) return;

		int texture = readFromAlt ? target.getAltTexture() : target.getMainTexture();

		// TODO: Only generate the mipmap if a valid mipmap hasn't been generated or if we've written to the buffer
		// (since the last mipmap was generated)
		//
		// NB: We leave mipmapping enabled even if the buffer is written to again, this appears to match the
		// behavior of ShadersMod/OptiFine, however I'm not sure if it's desired behavior. It's possible that a
		// program could use mipmapped sampling with a stale mipmap, which probably isn't great. However, the
		// sampling mode is always reset between frames, so this only persists after the first program to use
		// mipmapping on this buffer.
		//
		// Also note that this only applies to one of the two buffers in a render target buffer pair - making it
		// unlikely that this issue occurs in practice with most shader packs.
		IrisRenderSystem.generateMipmaps(texture, GL_TEXTURE_2D);

		int filter = GL_LINEAR_MIPMAP_LINEAR;
		if (target.getInternalFormat().getPixelFormat().isInteger()) {
			filter = GL_NEAREST_MIPMAP_NEAREST;
		}

		IrisRenderSystem.texParameteri(texture, GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
	}

	public ImmutableSet<Integer> getFlippedAtLeastOnceFinal() {
		return this.flippedAtLeastOnceFinal;
	}

	public void recalculateSizes() {
		for (Pass pass : passes) {
			if (pass instanceof ComputeOnlyPass) {
				continue;
			}
			int passWidth = 0, passHeight = 0;
			for (int buffer : pass.drawBuffers) {
				RenderTarget target = renderTargets.get(buffer);
				if ((passWidth > 0 && passWidth != target.getWidth()) || (passHeight > 0 && passHeight != target.getHeight())) {
					throw new IllegalStateException("Pass widths must match");
				}
				passWidth = target.getWidth();
				passHeight = target.getHeight();
			}
			renderTargets.destroyFramebuffer(pass.framebuffer);
			pass.framebuffer = renderTargets.createColorFramebuffer(pass.stageReadsFromAlt, pass.drawBuffers);
			pass.viewWidth = passWidth;
			pass.viewHeight = passHeight;
		}
	}

	public void renderAll() {
		RenderSystem.disableBlend();

		FullScreenQuadRenderer.INSTANCE.begin();
		com.mojang.blaze3d.pipeline.RenderTarget main = Minecraft.getInstance().getMainRenderTarget();

		int passIdx = 0;
		for (Pass renderPass : passes) {
			boolean ranCompute = false;
			for (ComputeProgram computeProgram : renderPass.computes) {
				if (computeProgram != null) {
					ranCompute = true;
					computeProgram.use();
					this.customUniforms.push(computeProgram);
					computeProgram.dispatch(main.width, main.height);
				}
			}

			if (ranCompute) {
				IrisRenderSystem.memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
			}

			Program.unbind();

			if (renderPass instanceof ComputeOnlyPass) {
				continue;
			}

			if (!renderPass.mipmappedBuffers.isEmpty()) {
				RenderSystem.activeTexture(GL_TEXTURE0);

				for (int index : renderPass.mipmappedBuffers) {
					setupMipmapping(CompositeRenderer.this.renderTargets.get(index), renderPass.stageReadsFromAlt.contains(index));
				}
			}

			renderPass.framebuffer.bind();

			float scaledWidth = renderPass.viewWidth * renderPass.viewportScale.scale();
			float scaledHeight = renderPass.viewHeight * renderPass.viewportScale.scale();
			int beginWidth = (int) (renderPass.viewWidth * renderPass.viewportScale.viewportX());
			int beginHeight = (int) (renderPass.viewHeight * renderPass.viewportScale.viewportY());
			RenderSystem.viewport(beginWidth, beginHeight, (int) scaledWidth, (int) scaledHeight);

			// In Vulkan, blend state is baked into the pipeline. Set blend state
			// BEFORE program.use() so bindGraphicsPipeline() captures the correct state.
			if (renderPass.blendModeOverride != null) {
				renderPass.blendModeOverride.apply();
			} else {
				RenderSystem.disableBlend();
			}

			renderPass.program.use();

			// program is the identifier for composite :shrug:
			this.customUniforms.push(renderPass.program);

			// === DIAGNOSTIC: Log texture binding state for first 3 frames ===
			if (passIdx == 0) {
				logCompositeTextureDiag();
			}

			FullScreenQuadRenderer.INSTANCE.renderQuad();

			BlendModeOverride.restore();
			passIdx++;
		}

		FullScreenQuadRenderer.INSTANCE.end();

		// Reset the viewport to full screen dimensions. Composite passes may have set
		// a scaled viewport (viewportScale) that would corrupt subsequent terrain/entity draws.
		com.mojang.blaze3d.pipeline.RenderTarget mainRT = Minecraft.getInstance().getMainRenderTarget();
		mainRT.bindWrite(true);
		RenderSystem.viewport(0, 0, mainRT.width, mainRT.height);
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		IrisRenderSystem.useProgram(0);

		// NB: Unbinding all of these textures is necessary for proper shaderpack reloading.
		for (int i = 0; i < SamplerLimits.get().getMaxTextureUnits(); i++) {
			// Unbind all textures that we may have used.
			// NB: This is necessary for shader pack reloading to work propely
			if (IrisRenderSystem.getBoundTextureId(i) != 0) {
				RenderSystem.activeTexture(GL_TEXTURE0 + i);
				RenderSystem.bindTexture(0);
			}
		}

		RenderSystem.activeTexture(GL_TEXTURE0);
	}

	// TODO: Don't just copy this from DeferredWorldRenderingPipeline
	private Program createProgram(ProgramSource source, ImmutableSet<Integer> flipped, ImmutableSet<Integer> flippedAtLeastOnceSnapshot,
								  Supplier<ShadowRenderTargets> shadowTargetsSupplier) {
		// TODO: Properly handle empty shaders
		Map<PatchShaderType, String> transformed = TransformPatcher.patchComposite(
			source.getName(),
			source.getVertexSource().orElseThrow(NullPointerException::new),
			source.getGeometrySource().orElse(null),
			source.getFragmentSource().orElseThrow(NullPointerException::new), textureStage, pipeline.getTextureMap());
		String vertex = transformed.get(PatchShaderType.VERTEX);
		String geometry = transformed.get(PatchShaderType.GEOMETRY);
		String fragment = transformed.get(PatchShaderType.FRAGMENT);

		ShaderPrinter.printProgram(source.getName()).addSources(transformed).print();

		Objects.requireNonNull(flipped);
		ProgramBuilder builder;

		try {
			builder = ProgramBuilder.begin(source.getName(), vertex, geometry, fragment,
				IrisSamplers.COMPOSITE_RESERVED_TEXTURE_UNITS);
		} catch (ShaderCompileException e) {
			throw e;
		} catch (RuntimeException e) {
			// TODO: Better error handling
			throw new RuntimeException("Shader compilation failed for " + source.getName() + "!", e);
		}


		CommonUniforms.addDynamicUniforms(builder, FogMode.OFF);
		this.customUniforms.assignTo(builder);

		ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor = ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureIds, flippedAtLeastOnceSnapshot);

		IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, () -> flipped, renderTargets, true, pipeline);
		IrisSamplers.addCustomTextures(builder, irisCustomTextures);
		IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);

		IrisImages.addRenderTargetImages(builder, () -> flipped, renderTargets);
		IrisImages.addCustomImages(builder, customImages);

		IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, noiseTexture);
		IrisSamplers.addCompositeSamplers(customTextureSamplerInterceptor, renderTargets);

		if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
			IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowTargetsSupplier.get(), null, pipeline.hasFeature(FeatureFlags.SEPARATE_HARDWARE_SAMPLERS));
			IrisImages.addShadowColorImages(builder, shadowTargetsSupplier.get(), null);
		}

		// TODO: Don't duplicate this with FinalPassRenderer
		centerDepthSampler.setUsage(builder.addDynamicSampler(centerDepthSampler::getCenterDepthTexture, "iris_centerDepthSmooth"));

		Program build = builder.build();

		// tell the customUniforms that those locations belong to this pass
		// this is just an object to index the internal map
		this.customUniforms.mapholderToPass(builder, build);

		return build;
	}

	private ComputeProgram[] createComputes(ComputeSource[] compute, ImmutableSet<Integer> flipped, ImmutableSet<Integer> flippedAtLeastOnceSnapshot, Supplier<ShadowRenderTargets> shadowTargetsSupplier, ShaderStorageBufferHolder holder) {
		ComputeProgram[] programs = new ComputeProgram[compute.length];
		for (int i = 0; i < programs.length; i++) {
			ComputeSource source = compute[i];
			if (source == null || !source.getSource().isPresent()) {
				continue;
			} else {
				// TODO: Properly handle empty shaders
				Objects.requireNonNull(flipped);
				ProgramBuilder builder;

				try {
					String transformed = TransformPatcher.patchCompute(source.getName(), source.getSource().orElse(null), textureStage, pipeline.getTextureMap());

					ShaderPrinter.printProgram(source.getName()).addSource(PatchShaderType.COMPUTE, transformed).print();

					builder = ProgramBuilder.beginCompute(source.getName(), transformed, IrisSamplers.COMPOSITE_RESERVED_TEXTURE_UNITS);
				} catch (ShaderCompileException e) {
					throw e;
				} catch (RuntimeException e) {
					// TODO: Better error handling
					throw new RuntimeException("Shader compilation failed for compute " + source.getName() + "!", e);
				}

				ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor = ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureIds, flippedAtLeastOnceSnapshot);

				CommonUniforms.addDynamicUniforms(builder, FogMode.OFF);

				customUniforms.assignTo(builder);

				IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, () -> flipped, renderTargets, true, pipeline);
				IrisSamplers.addCustomTextures(builder, irisCustomTextures);
				IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);

				IrisImages.addRenderTargetImages(builder, () -> flipped, renderTargets);
				IrisImages.addCustomImages(builder, customImages);

				IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, noiseTexture);
				IrisSamplers.addCompositeSamplers(customTextureSamplerInterceptor, renderTargets);

				if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
					IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowTargetsSupplier.get(), null, pipeline.hasFeature(FeatureFlags.SEPARATE_HARDWARE_SAMPLERS));
					IrisImages.addShadowColorImages(builder, shadowTargetsSupplier.get(), null);
				}

				// TODO: Don't duplicate this with FinalPassRenderer
				centerDepthSampler.setUsage(builder.addDynamicSampler(centerDepthSampler::getCenterDepthTexture, "iris_centerDepthSmooth"));

				programs[i] = builder.buildCompute();

				customUniforms.mapholderToPass(builder, programs[i]);

				programs[i].setWorkGroupInfo(source.getWorkGroupRelative(), source.getWorkGroups(), FilledIndirectPointer.basedOff(holder, source.getIndirectPointer()));
			}
		}


		return programs;
	}

	public void destroy() {
		for (Pass renderPass : passes) {
			renderPass.destroy();
		}
	}

	private static class Pass {
		int[] drawBuffers;
		int viewWidth;
		int viewHeight;
		Program program;
		BlendModeOverride blendModeOverride;
		ComputeProgram[] computes;
		GlFramebuffer framebuffer;
		ImmutableSet<Integer> flippedAtLeastOnce;
		ImmutableSet<Integer> stageReadsFromAlt;
		ImmutableSet<Integer> mipmappedBuffers;
		ViewportData viewportScale;

		protected void destroy() {
			this.program.destroy();
			for (ComputeProgram compute : this.computes) {
				if (compute != null) {
					compute.destroy();
				}
			}
		}
	}

	private static class ComputeOnlyPass extends Pass {
		@Override
		protected void destroy() {
			for (ComputeProgram compute : this.computes) {
				if (compute != null) {
					compute.destroy();
				}
			}
		}
	}

	/**
	 * Diagnostic: Log texture binding state for the first composite/deferred pass.
	 * Fires once per frame for the first 5 frames only. Shows:
	 * - Which texture units have textures bound
	 * - Whether each texture has a valid VulkanImage
	 * - VulkanImage dimensions and format
	 * This tells us if the gbuffer data is actually accessible to composite shaders.
	 */
	private void logCompositeTextureDiag() {
		int frame = net.vulkanmod.vulkan.Renderer.getCurrentFrame();
		if (frame == lastDiagFrame) return;
		lastDiagFrame = frame;
		if (diagFrameCount++ > 5) return;

		StringBuilder sb = new StringBuilder();
		sb.append("\n[DIAG] === Composite Pass Texture State (frame ").append(frame).append(") ===\n");

		// Log bound textures on units 0-17
		// Actual ProgramSamplers unit mapping (with reserved units {1,2} skipped):
		// 0=colortex0, 3=colortex1, 4=colortex2, 5=colortex3, 6=colortex4,
		// 7=colortex5, 8=colortex6, 9=colortex7, 10=colortex8, 11=noisetex,
		// 12=depthtex0, 13=depthtex1, 14=shadowtex0, 15=shadowtex1,
		// 16=shadowcolor0, 17=shadowcolor1
		String[] unitNames = {
			"colortex0", "(reserved1)", "(reserved2)", "colortex1",
			"colortex2", "colortex3", "colortex4", "colortex5",
			"colortex6", "colortex7", "colortex8", "noisetex",
			"depthtex0", "depthtex1", "shadowtex0", "shadowtex1",
			"shadowcolor0", "shadowcolor1"
		};

		int nullCount = 0;
		for (int unit = 0; unit < 18; unit++) {
			int texId = IrisRenderSystem.getBoundTextureId(unit);
			if (texId == 0) continue;

			String name = (unit < unitNames.length) ? unitNames[unit] : "unit" + unit;
			GlTexture glTex = GlTexture.getTexture(texId);
			VulkanImage vkImg = (glTex != null) ? glTex.getVulkanImage() : null;

			sb.append("  [").append(unit).append("] ").append(name).append(": texId=").append(texId);
			if (vkImg != null) {
				sb.append(" ").append(vkImg.width).append("x").append(vkImg.height);
				sb.append(" fmt=0x").append(Integer.toHexString(vkImg.format));
				sb.append(" layout=").append(vkImg.getCurrentLayout());
			} else {
				sb.append(" VkImage=NULL!");
				nullCount++;
			}
			sb.append("\n");
		}

		if (nullCount > 0) {
			sb.append("  WARNING: ").append(nullCount).append(" textures have NULL VulkanImage!\n");
		}

		// Log gbuffer uniform values from CapturedRenderingState
		var capturedProj = net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getGbufferProjection();
		var capturedMV = net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getGbufferModelView();
		sb.append("  gbufferProjection: ").append(capturedProj != null ? "set" : "NULL");
		if (capturedProj != null) {
			sb.append(" m00=").append(String.format("%.4f", capturedProj.m00()));
			sb.append(" m11=").append(String.format("%.4f", capturedProj.m11()));
			sb.append(" m22=").append(String.format("%.4f", capturedProj.m22()));
			sb.append(" m23=").append(String.format("%.4f", capturedProj.m23()));
		}
		sb.append("\n");
		sb.append("  gbufferModelView: ").append(capturedMV != null ? "set" : "NULL").append("\n");

		// Camera position
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
			var camPos = mc.gameRenderer.getMainCamera().getPosition();
			sb.append("  cameraPosition: ").append(String.format("%.1f, %.1f, %.1f", camPos.x, camPos.y, camPos.z)).append("\n");
		}

		// Shadow matrices (critical for shadow lookups)
		org.joml.Matrix4f shadowProj = net.irisshaders.iris.shadows.ShadowRenderer.PROJECTION;
		org.joml.Matrix4f shadowMV = net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW;
		if (shadowProj != null) {
			sb.append("  shadowProjection: m00=").append(String.format("%.6f", shadowProj.m00()));
			sb.append(" m11=").append(String.format("%.6f", shadowProj.m11()));
			sb.append(" m22=").append(String.format("%.6f", shadowProj.m22()));
			sb.append(" m32=").append(String.format("%.6f", shadowProj.m32()));
			sb.append(" m33=").append(String.format("%.6f", shadowProj.m33()));
			sb.append("\n");
		} else {
			sb.append("  shadowProjection: NULL\n");
		}
		if (shadowMV != null) {
			sb.append("  shadowModelView: m00=").append(String.format("%.4f", shadowMV.m00()));
			sb.append(" m11=").append(String.format("%.4f", shadowMV.m11()));
			sb.append(" m22=").append(String.format("%.4f", shadowMV.m22()));
			sb.append("\n");
		}

		// Log starter and temporal values from the active UBO
		var activeBuf = IrisRenderSystem.getActiveUniformBuffer();
		if (activeBuf != null) {
			int starterOff = activeBuf.getFieldOffset("starter");
			int framemod8Off = activeBuf.getFieldOffset("framemod8");
			int frameCounterOff = activeBuf.getFieldOffset("frameCounter");
			sb.append("  UBO starter=").append(starterOff >= 0 ? String.format("%.4f", activeBuf.readFloat(starterOff)) : "N/A");
			sb.append(" framemod8=").append(framemod8Off >= 0 ? String.format("%.1f", activeBuf.readFloat(framemod8Off)) : "N/A");
			sb.append(" frameCounter=").append(frameCounterOff >= 0 ? String.format("%.0f", activeBuf.readFloat(frameCounterOff)) : "N/A");
			sb.append("\n");
		}

		sb.append("[DIAG] === End Composite Texture State ===");
		Iris.logger.info(sb.toString());
	}
}
