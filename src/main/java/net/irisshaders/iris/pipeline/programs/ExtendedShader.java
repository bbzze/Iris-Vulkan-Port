package net.irisshaders.iris.pipeline.programs;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.program.IrisProgramTypes;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.irisshaders.iris.mixinterface.ShaderInstanceInterface;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.vulkan.shader.IrisSPIRVCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.vulkanmod.interfaces.ShaderMixed;
import net.irisshaders.iris.gl.uniform.IrisUniformBuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExtendedShader extends ShaderInstance implements ShaderInstanceInterface {
	// GL constants (inlined from KHRDebug/ARBTextureSwizzle/GL30C)
	private static final int GL_PROGRAM = 0x82E2;
	private static final int GL_SHADER = 0x82E1;
	private static final int GL_TEXTURE_SWIZZLE_RGBA = 0x8E46;
	private static final int GL_RED = 0x1903;

	private static final Matrix4f identity;
	private static ExtendedShader lastApplied;

	static {
		identity = new Matrix4f();
		identity.identity();
	}

	private final boolean intensitySwizzle;
	private final List<BufferBlendOverride> bufferBlendOverrides;
	private final boolean hasOverrides;
	private final Uniform modelViewInverse;
	private final Uniform projectionInverse;
	private final Uniform normalMatrix;
	private final CustomUniforms customUniforms;
	private final IrisRenderingPipeline parent;
	private final ProgramUniforms uniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;
	private final GlFramebuffer writingToBeforeTranslucent;
	private final GlFramebuffer writingToAfterTranslucent;
	private final BlendModeOverride blendModeOverride;
	private final float alphaTest;
	private final boolean usesTessellation;
	private final Matrix4f tempMatrix4f = new Matrix4f();
	private final Matrix3f tempMatrix3f = new Matrix3f();
	private final float[] tempFloats = new float[16];
	private final float[] tempFloats2 = new float[9];
	private Program geometry, tessControl, tessEval;

	// Phase 7: Vulkan uniform buffer for this shader
	private IrisUniformBuffer irisUniformBuffer;
	private ManualUBO irisManualUBO;
	private int irisProgramId;
	private List<IrisSPIRVCompiler.UniformField> sharedUniforms;

	public ExtendedShader(ResourceProvider resourceFactory, String string, VertexFormat vertexFormat, boolean usesTessellation,
						  GlFramebuffer writingToBeforeTranslucent, GlFramebuffer writingToAfterTranslucent,
						  BlendModeOverride blendModeOverride, AlphaTest alphaTest,
						  Consumer<DynamicLocationalUniformHolder> uniformCreator, BiConsumer<SamplerHolder, ImageHolder> samplerCreator, boolean isIntensity,
						  IrisRenderingPipeline parent, @Nullable List<BufferBlendOverride> bufferBlendOverrides, CustomUniforms customUniforms) throws IOException {
		super(resourceFactory, string, vertexFormat);

		// VulkanMod nulls out GL programs - guard debug naming calls
		if (this.getVertexProgram() != null) {
			GLDebug.nameObject(GL_SHADER, this.getVertexProgram().getId(), string + "_vertex.vsh");
		}
		if (this.getFragmentProgram() != null) {
			GLDebug.nameObject(GL_SHADER, this.getFragmentProgram().getId(), string + "_fragment.fsh");
		}

		int programId = this.getId();

		if (programId != 0) {
			GLDebug.nameObject(GL_PROGRAM, programId, string);
		}

		// Phase 7: Read shader sources early to determine UBO layout BEFORE ProgramUniforms
		// so that getUniformLocation() returns real byte offsets instead of -1
		this.irisProgramId = IrisRenderSystem.allocateIrisProgramId();
		String vshSrc = readShaderSource(resourceFactory, ".vsh");
		String fshSrc = readShaderSource(resourceFactory, ".fsh");
		if (vshSrc != null && fshSrc != null) {
			// Collect non-opaque uniforms from both shaders and merge into one list
			@SuppressWarnings("unchecked")
			List<IrisSPIRVCompiler.UniformField> merged = IrisSPIRVCompiler.mergeUniforms(
				IrisSPIRVCompiler.collectLooseUniforms(vshSrc),
				IrisSPIRVCompiler.collectLooseUniforms(fshSrc)
			);
			this.sharedUniforms = merged;

			// Preprocess one shader with the shared UBO to parse the std140 layout
			String sampleVulkan = IrisSPIRVCompiler.prepareForVulkan(fshSrc, merged);
			this.irisUniformBuffer = IrisUniformBuffer.fromVulkanGLSL(sampleVulkan);

			// Register so getUniformLocation(irisProgramId, name) returns byte offsets
			IrisRenderSystem.registerUniformBuffer(this.irisProgramId, this.irisUniformBuffer);
			// Register sampler names so getUniformLocation returns non-(-1) for samplers
			java.util.Set<String> samplerNames = new java.util.HashSet<>();
			collectSamplerNamesFromSource(vshSrc, samplerNames);
			collectSamplerNamesFromSource(fshSrc, samplerNames);
			IrisRenderSystem.registerSamplerNames(this.irisProgramId, samplerNames);

			Iris.logger.debug("Registered IrisUniformBuffer for {} with {} fields, {} bytes, {} samplers",
				string, irisUniformBuffer.getFields().size(), irisUniformBuffer.getUsedSize(), samplerNames.size());
		}

		// Use irisProgramId for BOTH uniform and sampler location lookups
		ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder(string, this.irisProgramId);
		ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(this.irisProgramId, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
		uniformCreator.accept(uniformBuilder);
		ProgramImages.Builder builder = ProgramImages.builder(this.irisProgramId);
		samplerCreator.accept(samplerBuilder, builder);
		customUniforms.mapholderToPass(uniformBuilder, this);
		this.usesTessellation = usesTessellation;

		uniforms = uniformBuilder.buildUniforms();
		this.customUniforms = customUniforms;

		// Get sampler name → texture unit mapping BEFORE building ProgramSamplers
		// This mapping is used by createVulkanPipeline to set correct imageIdx on ImageDescriptors
		java.util.Map<String, Integer> samplerUnitMap = samplerBuilder.getSamplerNameToUnit();

		samplers = samplerBuilder.build();
		images = builder.build();
		this.writingToBeforeTranslucent = writingToBeforeTranslucent;
		this.writingToAfterTranslucent = writingToAfterTranslucent;
		this.blendModeOverride = blendModeOverride;
		this.bufferBlendOverrides = bufferBlendOverrides;
		this.hasOverrides = bufferBlendOverrides != null && !bufferBlendOverrides.isEmpty();
		this.alphaTest = alphaTest.reference();
		this.parent = parent;

		this.modelViewInverse = this.getUniform("ModelViewMatInverse");
		this.projectionInverse = this.getUniform("ProjMatInverse");
		this.normalMatrix = this.getUniform("NormalMat");

		this.intensitySwizzle = isIntensity;

		// Create VulkanMod GraphicsPipeline AFTER ProgramSamplers is built
		// so we can use the actual sampler→textureUnit mapping for imageIdx
		createVulkanPipeline(resourceFactory, string, vertexFormat, samplerUnitMap);
	}

	public boolean isIntensitySwizzle() {
		return intensitySwizzle;
	}

	@Override
	public void clear() {
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		lastApplied = null;

		if (this.blendModeOverride != null || hasOverrides) {
			BlendModeOverride.restore();
		}

		Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
	}

	@Override
	public void apply() {
		CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alphaTest);

		// Phase 7: Set the active uniform buffer BEFORE any uniform updates
		// so that IrisRenderSystem.uniform*() writes to our buffer
		if (irisUniformBuffer != null) {
			IrisRenderSystem.setActiveUniformBuffer(irisUniformBuffer);
		}

		if (lastApplied != this) {
			lastApplied = this;
			ProgramManager.glUseProgram(this.getId());
		}

		if (intensitySwizzle) {
			IrisRenderSystem.texParameteriv(RenderSystem.getShaderTexture(0), TextureType.TEXTURE_2D.getGlType(), GL_TEXTURE_SWIZZLE_RGBA,
				new int[]{GL_RED, GL_RED, GL_RED, GL_RED});
		}

		IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), IrisSamplers.ALBEDO_TEXTURE_UNIT, RenderSystem.getShaderTexture(0));
		IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), IrisSamplers.OVERLAY_TEXTURE_UNIT, RenderSystem.getShaderTexture(1));
		IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), IrisSamplers.LIGHTMAP_TEXTURE_UNIT, RenderSystem.getShaderTexture(2));

		ImmediateState.usingTessellation = usesTessellation;

		if (PROJECTION_MATRIX != null) {
			// Always compute the inverse (needed for UBO even if MC Uniform is null)
			tempMatrix4f.set(PROJECTION_MATRIX.getFloatBuffer()).invert().get(tempFloats);
			if (projectionInverse != null) {
				projectionInverse.set(tempFloats);
			}
			if (irisUniformBuffer != null) {
				irisUniformBuffer.writeMat4f(irisUniformBuffer.getFieldOffset("iris_ProjMatInverse"), tempFloats);
			}
		} else {
			if (projectionInverse != null) {
				projectionInverse.set(identity);
			}
		}

		if (MODEL_VIEW_MATRIX != null) {
			// Always compute the inverse (needed for UBO even if MC Uniform is null)
			tempMatrix4f.set(MODEL_VIEW_MATRIX.getFloatBuffer()).invert().get(tempFloats);
			if (modelViewInverse != null) {
				modelViewInverse.set(tempFloats);
			}
			if (irisUniformBuffer != null) {
				irisUniformBuffer.writeMat4f(irisUniformBuffer.getFieldOffset("iris_ModelViewMatInverse"), tempFloats);
			}

			// Normal matrix = transpose(inverse(mat3(modelView)))
			tempMatrix3f.set(tempMatrix4f.set(MODEL_VIEW_MATRIX.getFloatBuffer())).invert().transpose().get(tempFloats2);
			if (normalMatrix != null) {
				normalMatrix.set(tempFloats2);
			}
			if (irisUniformBuffer != null) {
				irisUniformBuffer.writeMat3f(irisUniformBuffer.getFieldOffset("iris_NormalMat"), tempFloats2);
			}
		}

		uploadIfNotNull(projectionInverse);
		uploadIfNotNull(modelViewInverse);
		uploadIfNotNull(normalMatrix);

		// Phase 7: Write vanilla MC uniform values directly to UBO buffer
		// (VulkanMod's UniformM cancels Uniform.upload(), so we write manually)
		if (irisUniformBuffer != null) {
			writeMcUniformsToBuffer();
		}

		List<Uniform> uniformList = super.uniforms;
		for (Uniform uniform : uniformList) {
			uploadIfNotNull(uniform);
		}

		samplers.update();

		// This calls Iris Uniform.update() which now writes to the active buffer
		// via IrisRenderSystem.uniform*() methods
		uniforms.update();

		customUniforms.push(this);

		images.update();

		// Phase 7: Update ManualUBO source pointer so VulkanMod copies our data at draw time
		if (irisManualUBO != null && irisUniformBuffer != null) {
			irisManualUBO.setSrc(irisUniformBuffer.getPointer(), irisUniformBuffer.getUsedSize());
		}

		if (this.blendModeOverride != null) {
			this.blendModeOverride.apply();
		}

		if (hasOverrides) {
			bufferBlendOverrides.forEach(BufferBlendOverride::apply);
		}

		if (parent.isBeforeTranslucent) {
			writingToBeforeTranslucent.bind();
		} else {
			writingToAfterTranslucent.bind();
		}

		// Bind the Vulkan pipeline for entity/shader rendering.
		// Without this, entities render with the default VulkanMod pipeline instead
		// of the Iris shader pack pipeline, so they don't write to gbuffer correctly.
		GraphicsPipeline vulkanPipeline = ((ShaderMixed) (Object) this).getPipeline();
		if (vulkanPipeline != null) {
			net.vulkanmod.vulkan.Renderer renderer = net.vulkanmod.vulkan.Renderer.getInstance();
			renderer.bindGraphicsPipeline(vulkanPipeline);
			renderer.uploadAndBindUBOs(vulkanPipeline);
		}
	}

	/**
	 * Writes standard Minecraft uniform values directly to the IrisUniformBuffer.
	 * These uniforms (ModelViewMat, ProjMat, ColorModulator, etc.) may be declared
	 * in shader pack GLSL and thus present in the IrisUniforms UBO block.
	 * VulkanMod's UniformM cancels Uniform.upload(), so we write them manually.
	 */
	private static int mcUniformLogCounter = 0;

	private void writeMcUniformsToBuffer() {
		// The GLSL transformer renames gl_ModelViewMatrix → iris_ModelViewMat,
		// gl_ProjectionMatrix → iris_ProjMat, etc. So the UBO field may have
		// the iris_ prefix. Try both vanilla and iris-prefixed names.

		// ModelViewMat / iris_ModelViewMat
		if (MODEL_VIEW_MATRIX != null) {
			java.nio.FloatBuffer mvBuf = MODEL_VIEW_MATRIX.getFloatBuffer();
			writeToFirstMatch(mvBuf, "ModelViewMat", "iris_ModelViewMat", "iris_ModelViewMatrix");
		}
		// ProjMat / iris_ProjMat
		if (PROJECTION_MATRIX != null) {
			java.nio.FloatBuffer projBuf = PROJECTION_MATRIX.getFloatBuffer();
			writeToFirstMatch(projBuf, "ProjMat", "iris_ProjMat", "iris_ProjectionMatrix");
		}
		// ColorModulator / iris_ColorModulator
		if (COLOR_MODULATOR != null) {
			java.nio.FloatBuffer fb = COLOR_MODULATOR.getFloatBuffer();
			float r = fb.get(0), g = fb.get(1), b = fb.get(2), a = fb.get(3);
			for (String name : new String[]{"ColorModulator", "iris_ColorModulator"}) {
				int off = irisUniformBuffer.getFieldOffset(name);
				if (off >= 0) irisUniformBuffer.writeVec4f(off, r, g, b, a);
			}
		}
		// FogStart / iris_FogStart
		{
			float val = RenderSystem.getShaderFogStart();
			for (String name : new String[]{"FogStart", "iris_FogStart"}) {
				int off = irisUniformBuffer.getFieldOffset(name);
				if (off >= 0) irisUniformBuffer.writeFloat(off, val);
			}
		}
		// FogEnd / iris_FogEnd
		{
			float val = RenderSystem.getShaderFogEnd();
			for (String name : new String[]{"FogEnd", "iris_FogEnd"}) {
				int off = irisUniformBuffer.getFieldOffset(name);
				if (off >= 0) irisUniformBuffer.writeFloat(off, val);
			}
		}
		// FogColor / iris_FogColor — use Iris's captured fog color for correct timing
		{
			org.joml.Vector3d capturedFog = CapturedRenderingState.INSTANCE.getFogColor();
			float fogR = (float) capturedFog.x;
			float fogG = (float) capturedFog.y;
			float fogB = (float) capturedFog.z;
			for (String name : new String[]{"FogColor", "iris_FogColor"}) {
				int off = irisUniformBuffer.getFieldOffset(name);
				if (off >= 0) irisUniformBuffer.writeVec4f(off, fogR, fogG, fogB, 1.0f);
			}
		}
		// GameTime
		{
			float val = RenderSystem.getShaderGameTime();
			int off = irisUniformBuffer.getFieldOffset("GameTime");
			if (off >= 0) irisUniformBuffer.writeFloat(off, val);
		}

		// Write gbufferModelView, gbufferProjection, etc. from CapturedRenderingState
		// These are the OptiFine-compatible uniforms used by shader packs for
		// camera-space calculations in both gbuffer and composite shaders.
		writeGbufferUniforms();

		if (mcUniformLogCounter < 1) {
			boolean foundMV = irisUniformBuffer.getFieldOffset("iris_ModelViewMat") >= 0
				|| irisUniformBuffer.getFieldOffset("ModelViewMat") >= 0;
			boolean foundProj = irisUniformBuffer.getFieldOffset("iris_ProjMat") >= 0
				|| irisUniformBuffer.getFieldOffset("ProjMat") >= 0;
			boolean foundGbufMV = irisUniformBuffer.getFieldOffset("gbufferModelView") >= 0;
			boolean foundGbufProj = irisUniformBuffer.getFieldOffset("gbufferProjection") >= 0;
			boolean foundCamPos = irisUniformBuffer.getFieldOffset("cameraPosition") >= 0;
			Iris.logger.debug("[ExtendedShader] UBO field lookup: MV={} Proj={} gbufferMV={} gbufferProj={} camPos={} (buffer={}bytes)",
				foundMV, foundProj, foundGbufMV, foundGbufProj, foundCamPos, irisUniformBuffer.getUsedSize());
			mcUniformLogCounter++;
		}
	}

	/**
	 * Write a mat4 FloatBuffer to the first matching UBO field name.
	 */
	private void writeToFirstMatch(java.nio.FloatBuffer matrix, String... names) {
		for (String name : names) {
			int off = irisUniformBuffer.getFieldOffset(name);
			if (off >= 0) {
				irisUniformBuffer.writeMat4f(off, matrix);
			}
		}
	}

	/**
	 * Writes gbufferModelView, gbufferProjection, their inverses, cameraPosition,
	 * and other standard OptiFine/Iris uniforms to the UBO.
	 * In original Iris, these are set for the custom uniform expression engine but
	 * NOT for individual shader programs. In Vulkan, ALL uniforms live in one UBO,
	 * so we must write them explicitly.
	 */
	private void writeGbufferUniforms() {
		org.joml.Matrix4fc gbufferMV = CapturedRenderingState.INSTANCE.getGbufferModelView();
		org.joml.Matrix4fc gbufferProj = CapturedRenderingState.INSTANCE.getGbufferProjection();

		if (gbufferMV != null) {
			float[] arr = new float[16];
			gbufferMV.get(arr);
			for (String name : new String[]{"gbufferModelView"}) {
				int off = irisUniformBuffer.getFieldOffset(name);
				if (off >= 0) irisUniformBuffer.writeMat4f(off, arr);
			}
			// Inverse
			org.joml.Matrix4f inv = new org.joml.Matrix4f(gbufferMV);
			inv.invert();
			inv.get(arr);
			for (String name : new String[]{"gbufferModelViewInverse"}) {
				int off = irisUniformBuffer.getFieldOffset(name);
				if (off >= 0) irisUniformBuffer.writeMat4f(off, arr);
			}
		}

		if (gbufferProj != null) {
			// Convert from Vulkan [0,1] to OpenGL [-1,1] depth range.
			// VulkanMod's Matrix4fM mixin forces zZeroToOne=true on all projection
			// matrices. Shader packs expect OpenGL-style depth for position
			// reconstruction: clipZ = depth * 2.0 - 1.0, viewPos = inverse * clipPos.
			org.joml.Matrix4f proj = new org.joml.Matrix4f(gbufferProj);
			proj.m22(2.0f * proj.m22() - proj.m23());
			proj.m32(2.0f * proj.m32() - proj.m33());

			float[] arr = new float[16];
			proj.get(arr);
			for (String name : new String[]{"gbufferProjection"}) {
				int off = irisUniformBuffer.getFieldOffset(name);
				if (off >= 0) irisUniformBuffer.writeMat4f(off, arr);
			}
			// Inverse
			org.joml.Matrix4f inv = new org.joml.Matrix4f(proj);
			inv.invert();
			inv.get(arr);
			for (String name : new String[]{"gbufferProjectionInverse"}) {
				int off = irisUniformBuffer.getFieldOffset(name);
				if (off >= 0) irisUniformBuffer.writeMat4f(off, arr);
			}
		}

		// cameraPosition
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
			var camPos = mc.gameRenderer.getMainCamera().getPosition();
			writeFloatField("cameraPosition", (float) camPos.x, (float) camPos.y, (float) camPos.z);
		}
	}

	private void writeFloatField(String name, float x, float y, float z) {
		int off = irisUniformBuffer.getFieldOffset(name);
		if (off >= 0) irisUniformBuffer.writeVec3f(off, x, y, z);
	}

	@Nullable
	@Override
	public Uniform getUniform(@NotNull String name) {
		// Prefix all uniforms with Iris to help avoid conflicts with existing names within the shader.
		return super.getUniform("iris_" + name);
	}

	private void uploadIfNotNull(Uniform uniform) {
		if (uniform != null) {
			uniform.upload();
		}
	}

	@Override
	public void attachToProgram() {
		super.attachToProgram();
		if (this.geometry != null) {
			this.geometry.attachToShader(this);
		}
		if (this.tessControl != null) {
			this.tessControl.attachToShader(this);
		}
		if (this.tessEval != null) {
			this.tessEval.attachToShader(this);
		}
	}

	@Override
	public void iris$createExtraShaders(ResourceProvider factory, String name) {
		factory.getResource(ResourceLocation.fromNamespaceAndPath("minecraft", name + "_geometry.gsh")).ifPresent(geometry -> {
			try {
				this.geometry = Program.compileShader(IrisProgramTypes.GEOMETRY, name, geometry.open(), geometry.sourcePackId(), new GlslPreprocessor() {
					@Nullable
					@Override
					public String applyImport(boolean bl, String string) {
						return null;
					}
				});
				if (this.geometry != null) GLDebug.nameObject(GL_SHADER, this.geometry.getId(), name + "_geometry.gsh");
			} catch (IOException e) {
				Iris.logger.error("Failed to create shader program", e);
			}
		});
		factory.getResource(ResourceLocation.fromNamespaceAndPath("minecraft", name + "_tessControl.tcs")).ifPresent(tessControl -> {
			try {
				this.tessControl = Program.compileShader(IrisProgramTypes.TESS_CONTROL, name, tessControl.open(), tessControl.sourcePackId(), new GlslPreprocessor() {
					@Nullable
					@Override
					public String applyImport(boolean bl, String string) {
						return null;
					}
				});
				if (this.tessControl != null) GLDebug.nameObject(GL_SHADER, this.tessControl.getId(), name + "_tessControl.tcs");
			} catch (IOException e) {
				Iris.logger.error("Failed to create shader program", e);
			}
		});
		factory.getResource(ResourceLocation.fromNamespaceAndPath("minecraft", name + "_tessEval.tes")).ifPresent(tessEval -> {
			try {
				this.tessEval = Program.compileShader(IrisProgramTypes.TESS_EVAL, name, tessEval.open(), tessEval.sourcePackId(), new GlslPreprocessor() {
					@Nullable
					@Override
					public String applyImport(boolean bl, String string) {
						return null;
					}
				});
				if (this.tessEval != null) GLDebug.nameObject(GL_SHADER, this.tessEval.getId(), name + "_tessEval.tes");
			} catch (IOException e) {
				Iris.logger.error("Failed to create shader program", e);
			}
		});
	}

	public Program getGeometry() {
		return this.geometry;
	}

	public Program getTessControl() {
		return this.tessControl;
	}

	public Program getTessEval() {
		return this.tessEval;
	}

	public boolean hasActiveImages() {
		return images.getActiveImages() > 0;
	}

	// ==================== Vulkan Pipeline Creation ====================

	private static final Pattern SAMPLER_PATTERN = Pattern.compile(
		"^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+((?:sampler|isampler|usampler)\\w+)\\s+(\\w+)");

	/**
	 * Creates a VulkanMod GraphicsPipeline for this ExtendedShader.
	 * Uses the shared uniform list (computed earlier in constructor) to ensure
	 * vertex and fragment shaders share the same UBO layout.
	 * Uses ManualUBO for uniform data upload from our IrisUniformBuffer.
	 */
	private void createVulkanPipeline(ResourceProvider resourceFactory, String name, VertexFormat vertexFormat, java.util.Map<String, Integer> samplerUnitMap) {
		try {
			// 1. Read shader sources from resource provider
			String vshSrc = readShaderSource(resourceFactory, ".vsh");
			String fshSrc = readShaderSource(resourceFactory, ".fsh");
			if (vshSrc == null || fshSrc == null) {
				Iris.logger.warn("Could not read shader sources for Vulkan pipeline: {}", name);
				return;
			}

			// 2. Preprocess for Vulkan using SHARED uniform list (same UBO layout for both stages)
			String vshVulkan;
			String fshVulkan;
			if (this.sharedUniforms != null) {
				vshVulkan = IrisSPIRVCompiler.prepareForVulkan(vshSrc, this.sharedUniforms);
				fshVulkan = IrisSPIRVCompiler.prepareForVulkan(fshSrc, this.sharedUniforms);
			} else {
				vshVulkan = IrisSPIRVCompiler.prepareForVulkan(vshSrc);
				fshVulkan = IrisSPIRVCompiler.prepareForVulkan(fshSrc);
			}

			// 3. Collect all unique sampler names from both shaders (in declaration order)
			List<String> allSamplers = new ArrayList<>();
			collectSamplerNames(vshVulkan, allSamplers);
			collectSamplerNames(fshVulkan, allSamplers);
			List<String> uniqueSamplers = new ArrayList<>(new LinkedHashSet<>(allSamplers));

			// 4. Create sampler binding map: binding 1, 2, 3... (UBO is at binding 0)
			Map<String, Integer> samplerBindings = new LinkedHashMap<>();
			for (int i = 0; i < uniqueSamplers.size(); i++) {
				samplerBindings.put(uniqueSamplers.get(i), i + 1);
			}

			// 5. Add explicit binding annotations to both shaders
			String vshFinal = addExplicitBindings(vshVulkan, samplerBindings);
			String fshFinal = addExplicitBindings(fshVulkan, samplerBindings);

			// 6. Compile to SPIR-V (source is already preprocessed, use compilePreprocessed)
			ByteBuffer vertSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".vsh", vshFinal, ShaderType.VERTEX);
			ByteBuffer fragSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".fsh", fshFinal, ShaderType.FRAGMENT);

			// 7. Wrap in VulkanMod SPIRV objects
			SPIRVUtils.SPIRV vertSPIRV = new SPIRVUtils.SPIRV(0, vertSpirv);
			SPIRVUtils.SPIRV fragSPIRV = new SPIRVUtils.SPIRV(0, fragSpirv);

			// 8. Create descriptor layout: 1 ManualUBO + N samplers
			List<UBO> ubos = new ArrayList<>();
			List<ImageDescriptor> imageDescriptors = new ArrayList<>();

			// Phase 7: Use ManualUBO instead of empty UBO - this copies our IrisUniformBuffer
			// data to the GPU each frame via ManualUBO.update()
			int vkShaderStageAllGraphics = 0x0000001F; // VK_SHADER_STAGE_ALL_GRAPHICS
			int uboSizeInBytes = (irisUniformBuffer != null) ? Math.max(irisUniformBuffer.getUsedSize(), 16) : 4096;
			// ManualUBO constructor takes size in 4-byte units (multiplies by 4 internally)
			int uboSizeInWords = (uboSizeInBytes + 3) / 4;
			this.irisManualUBO = new ManualUBO(0, vkShaderStageAllGraphics, uboSizeInWords);
			if (irisUniformBuffer != null) {
				this.irisManualUBO.setSrc(irisUniformBuffer.getPointer(), irisUniformBuffer.getUsedSize());
			}
			ubos.add(this.irisManualUBO);

			// Sampler ImageDescriptors at bindings 1, 2, 3...
			for (int i = 0; i < uniqueSamplers.size(); i++) {
				String samplerName = uniqueSamplers.get(i);
				int textureIdx = samplerUnitMap.getOrDefault(samplerName, mapSamplerToTextureIndex(samplerName));
				imageDescriptors.add(new ImageDescriptor(i + 1, "sampler2D", samplerName, textureIdx));
			}

			// 9. Build the VulkanMod GraphicsPipeline
			Pipeline.Builder builder = new Pipeline.Builder(vertexFormat, name);
			builder.setUniforms(ubos, imageDescriptors);
			builder.setSPIRVs(vertSPIRV, fragSPIRV);
			GraphicsPipeline pipeline = builder.createGraphicsPipeline();

			// 10. Set pipeline on this shader instance (via VulkanMod's ShaderMixed mixin)
			((ShaderMixed) (Object) this).setPipeline(pipeline);

			Iris.logger.info("Created Vulkan pipeline for ExtendedShader: {} ({} samplers, {} UBO bytes)",
				name, uniqueSamplers.size(), uboSizeInBytes);
		} catch (Exception e) {
			Iris.logger.error("Failed to create Vulkan pipeline for ExtendedShader {}: {}", name, e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Reads shader source from the resource provider by file extension.
	 * IrisProgramResourceFactory serves source based on extension only.
	 */
	private static String readShaderSource(ResourceProvider provider, String extension) {
		try {
			Optional<Resource> resource = provider.getResource(
				ResourceLocation.fromNamespaceAndPath("minecraft", "shaders/core/iris_dummy" + extension));
			if (resource.isPresent()) {
				try (InputStream is = resource.get().open()) {
					return new String(is.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		} catch (Exception e) {
			// Source not available
		}
		return null;
	}

	/**
	 * Finds all sampler declarations in preprocessed GLSL and collects their names.
	 */
	private static void collectSamplerNames(String source, List<String> names) {
		for (String line : source.split("\n")) {
			Matcher m = SAMPLER_PATTERN.matcher(line);
			if (m.find()) {
				String samplerName = m.group(2);
				if (!names.contains(samplerName)) {
					names.add(samplerName);
				}
			}
		}
	}

	/**
	 * Collects sampler names from raw GLSL source into a Set.
	 * Used to register sampler names with IrisRenderSystem for getUniformLocation() checks.
	 */
	private static void collectSamplerNamesFromSource(String source, java.util.Set<String> names) {
		for (String line : source.split("\n")) {
			Matcher m = SAMPLER_PATTERN.matcher(line.trim());
			if (m.find()) {
				names.add(m.group(2));
			}
		}
	}

	/**
	 * Post-processes Vulkan GLSL to add explicit binding annotations.
	 * - Adds binding=0 to the IrisUniforms UBO block
	 * - Adds binding=N to each sampler declaration from the binding map
	 */
	private static String addExplicitBindings(String source, Map<String, Integer> samplerBindings) {
		String[] lines = source.split("\n", -1);
		List<String> output = new ArrayList<>();

		for (String line : lines) {
			String trimmed = line.trim();

			// Add binding=0 to IrisUniforms UBO
			if (trimmed.equals("layout(std140) uniform IrisUniforms {")) {
				output.add("layout(std140, binding = 0) uniform IrisUniforms {");
				continue;
			}

			// Check for sampler declaration and add binding
			Matcher m = SAMPLER_PATTERN.matcher(trimmed);
			if (m.find()) {
				String samplerType = m.group(1);
				String samplerName = m.group(2);
				Integer binding = samplerBindings.get(samplerName);
				if (binding != null) {
					// Extract any array suffix or trailing content after the name
					String fullLine = trimmed.substring(m.end());
					output.add("layout(binding = " + binding + ") uniform " + samplerType + " " + samplerName + fullLine);
					continue;
				}
			}

			output.add(line);
		}

		return String.join("\n", output);
	}

	/**
	 * Maps Iris/OptiFine sampler names to VulkanMod texture slot indices.
	 * VTextureSelector supports indices 0-11.
	 */
	private static int mapSamplerToTextureIndex(String name) {
		return switch (name) {
			case "gtexture", "gcolor", "colortex0", "tex" -> 0;
			case "overlay" -> 1;
			case "lightmap" -> 2;
			case "normals", "gnormal", "colortex1" -> 3;
			case "specular", "gspecular", "colortex2" -> 4;
			case "shadow", "watershadow", "shadowtex0" -> 5;
			case "shadowtex1" -> 6;
			case "shadowcolor0", "shadowcolor", "colortex3" -> 7;
			case "shadowcolor1", "colortex4" -> 8;
			case "noisetex" -> 9;
			case "depthtex0", "colortex5" -> 10;
			case "depthtex1", "colortex6" -> 11;
			default -> 0; // fallback to main texture slot
		};
	}
}
