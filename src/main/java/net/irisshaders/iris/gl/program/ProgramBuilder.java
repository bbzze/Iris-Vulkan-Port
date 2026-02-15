package net.irisshaders.iris.gl.program;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ProgramCreator;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.uniform.IrisUniformBuffer;
import net.irisshaders.iris.vulkan.shader.IrisSPIRVCompiler;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProgramBuilder extends ProgramUniforms.Builder implements SamplerHolder, ImageHolder {
	private static final Pattern SAMPLER_PATTERN = Pattern.compile(
		"^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+((?:sampler|isampler|usampler)\\w+)\\s+(\\w+)");

	private final int program;
	private final ProgramSamplers.Builder samplers;
	private final ProgramImages.Builder images;

	// Vulkan pipeline data (populated in begin(), used in build())
	private String name;
	private ByteBuffer vertSpirv;
	private ByteBuffer fragSpirv;
	private String vshVulkan;
	private String fshVulkan;
	private IrisUniformBuffer uniformBuffer;
	private List<IrisSPIRVCompiler.UniformField> sharedUniforms;

	private ProgramBuilder(String name, int program, ImmutableSet<Integer> reservedTextureUnits) {
		super(name, program);

		this.program = program;
		this.name = name;
		this.samplers = ProgramSamplers.builder(program, reservedTextureUnits);
		this.images = ProgramImages.builder(program);
	}

	public static ProgramBuilder begin(String name, @Nullable String vertexSource, @Nullable String geometrySource,
									   @Nullable String fragmentSource, ImmutableSet<Integer> reservedTextureUnits) {
		RenderSystem.assertOnRenderThread();

		int programId;
		ByteBuffer vertSpirv = null;
		ByteBuffer fragSpirv = null;
		String vshVulkan = null;
		String fshVulkan = null;
		IrisUniformBuffer uniformBuffer = null;
		List<IrisSPIRVCompiler.UniformField> sharedUniforms = null;

		try {
			if (vertexSource != null && fragmentSource != null) {
				// Allocate a unique program ID for Vulkan uniform/sampler tracking
				programId = IrisRenderSystem.allocateIrisProgramId();

				// Collect and merge uniforms from both shader stages
				@SuppressWarnings("unchecked")
				List<IrisSPIRVCompiler.UniformField> merged = IrisSPIRVCompiler.mergeUniforms(
					IrisSPIRVCompiler.collectLooseUniforms(vertexSource),
					IrisSPIRVCompiler.collectLooseUniforms(fragmentSource)
				);
				// Inject standard Iris uniforms that may not be declared in shader source.
				// In OpenGL Iris, these are injected at runtime via glGetUniformLocation.
				// In Vulkan, they must be in the UBO text for fromVulkanGLSL() to find them.
				int collectedCount = merged.size();
				List<String> injected = IrisSPIRVCompiler.ensureStandardIrisUniforms(merged);
				if (!injected.isEmpty()) {
					Iris.logger.info("[ProgramBuilder] '{}': collected {} uniforms from source, injected {} standard: {}",
						name, collectedCount, injected.size(), String.join(", ", injected));
				}

				sharedUniforms = merged;

				// Preprocess both shaders for Vulkan with shared UBO layout
				vshVulkan = IrisSPIRVCompiler.prepareForVulkan(vertexSource, merged);
				fshVulkan = IrisSPIRVCompiler.prepareForVulkan(fragmentSource, merged);

				// Fix vertex input locations to match POSITION_TEX format:
				// location 0 = Position (vec3), location 1 = UV0 (vec2)
				// The LayoutTransformer does NOT process vertex shader inputs (only
				// cross-stage out→in), so shaderc's auto-map assigns by declaration
				// order which may not match the vertex buffer layout.
				vshVulkan = fixCompositeVertexInputLocations(vshVulkan);

				// Parse IrisUniforms block to create uniform buffer with std140 layout
				uniformBuffer = IrisUniformBuffer.fromVulkanGLSL(fshVulkan);
				IrisRenderSystem.registerUniformBuffer(programId, uniformBuffer);

				// Register sampler names so getUniformLocation returns non-(-1) for them
				Set<String> samplerNames = new HashSet<>();
				collectSamplerNamesFromGLSL(vshVulkan, samplerNames);
				collectSamplerNamesFromGLSL(fshVulkan, samplerNames);
				IrisRenderSystem.registerSamplerNames(programId, samplerNames);

				// Dump composite shader GLSL to iris-debug/ for inspection
				dumpCompositeShader(name + ".vsh", vshVulkan);
				dumpCompositeShader(name + ".fsh", fshVulkan);

				// Compile preprocessed GLSL to SPIR-V
				vertSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".vsh", vshVulkan, ShaderType.VERTEX);
				fragSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".fsh", fshVulkan, ShaderType.FRAGMENT);

				Iris.logger.info("[ProgramBuilder] '{}' UBO layout: {} bytes, {} fields, {} samplers | sunPosition={} gbufferProjection={}",
					name, uniformBuffer.getUsedSize(), uniformBuffer.getFields().size(), samplerNames.size(),
					uniformBuffer.getFieldOffset("sunPosition"), uniformBuffer.getFieldOffset("gbufferProjection"));
			} else {
				// Fallback: use ProgramCreator for incomplete shader sets
				GlShader vertex = buildShader(ShaderType.VERTEX, name + ".vsh", vertexSource);
				GlShader fragment = buildShader(ShaderType.FRAGMENT, name + ".fsh", fragmentSource);
				programId = ProgramCreator.create(name, vertex, fragment);
				vertex.destroy();
				fragment.destroy();
			}
		} catch (Exception e) {
			Iris.logger.error("Vulkan pipeline setup failed for composite program {}, using fallback", name, e);
			// Fallback: allocate a basic ID so the builder still works
			programId = IrisRenderSystem.allocateIrisProgramId();
			vertSpirv = null;
			fragSpirv = null;
		}

		ProgramBuilder builder = new ProgramBuilder(name, programId, reservedTextureUnits);
		builder.vertSpirv = vertSpirv;
		builder.fragSpirv = fragSpirv;
		builder.vshVulkan = vshVulkan;
		builder.fshVulkan = fshVulkan;
		builder.uniformBuffer = uniformBuffer;
		builder.sharedUniforms = sharedUniforms;
		return builder;
	}

	public static ProgramBuilder beginCompute(String name, @Nullable String source, ImmutableSet<Integer> reservedTextureUnits) {
		RenderSystem.assertOnRenderThread();

		if (!IrisRenderSystem.supportsCompute()) {
			throw new IllegalStateException("This PC does not support compute shaders, but it's attempting to be used???");
		}

		GlShader compute = buildShader(ShaderType.COMPUTE, name + ".csh", source);

		int programId = ProgramCreator.create(name, compute);

		compute.destroy();

		return new ProgramBuilder(name, programId, reservedTextureUnits);
	}

	private static GlShader buildShader(ShaderType shaderType, String name, @Nullable String source) {
		try {
			return new GlShader(shaderType, name, source);
		} catch (ShaderCompileException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new RuntimeException("Failed to compile " + shaderType + " shader for program " + name, e);
		}
	}

	private static void collectSamplerNamesFromGLSL(String source, Set<String> names) {
		for (String line : source.split("\n")) {
			Matcher m = SAMPLER_PATTERN.matcher(line.trim());
			if (m.find()) {
				names.add(m.group(2));
			}
		}
	}

	public void bindAttributeLocation(int index, String name) {
		IrisRenderSystem.bindAttributeLocation(program, index, name);
	}

	public Program build() {
		GraphicsPipeline pipeline = null;
		ManualUBO manualUBO = null;

		if (vertSpirv != null && fragSpirv != null) {
			try {
				pipeline = createVulkanPipeline();
				if (pipeline != null && uniformBuffer != null) {
					// ManualUBO is created inside createVulkanPipeline - get reference from field
					manualUBO = this.builtManualUBO;
				}
			} catch (Exception e) {
				Iris.logger.error("Failed to create Vulkan pipeline for composite program {}", name, e);
			}
		}

		return new Program(program, name, super.buildUniforms(), this.samplers.build(), this.images.build(),
			pipeline, uniformBuffer, manualUBO);
	}

	// Stored during createVulkanPipeline for retrieval
	private ManualUBO builtManualUBO;

	/**
	 * Creates a VulkanMod GraphicsPipeline for this composite program.
	 * Uses POSITION_TEX vertex format (full-screen quad).
	 */
	private GraphicsPipeline createVulkanPipeline() {
		// Collect sampler names from preprocessed sources
		List<String> allSamplers = new ArrayList<>();
		collectSamplerNamesToList(vshVulkan, allSamplers);
		collectSamplerNamesToList(fshVulkan, allSamplers);
		List<String> uniqueSamplers = new ArrayList<>(new LinkedHashSet<>(allSamplers));

		// Sampler binding map: binding 1, 2, 3... (UBO at binding 0)
		Map<String, Integer> samplerBindings = new LinkedHashMap<>();
		for (int i = 0; i < uniqueSamplers.size(); i++) {
			samplerBindings.put(uniqueSamplers.get(i), i + 1);
		}

		// Add explicit bindings to GLSL
		String vshFinal = addExplicitBindings(vshVulkan, samplerBindings);
		String fshFinal = addExplicitBindings(fshVulkan, samplerBindings);

		// Re-compile with bindings
		ByteBuffer vSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".vsh", vshFinal, ShaderType.VERTEX);
		ByteBuffer fSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".fsh", fshFinal, ShaderType.FRAGMENT);

		SPIRVUtils.SPIRV vertSPIRV = new SPIRVUtils.SPIRV(0, vSpirv);
		SPIRVUtils.SPIRV fragSPIRV = new SPIRVUtils.SPIRV(0, fSpirv);

		// Create ManualUBO
		List<UBO> ubos = new ArrayList<>();
		List<ImageDescriptor> imageDescriptors = new ArrayList<>();

		int vkShaderStageAllGraphics = 0x0000001F;
		int uboSizeInBytes = (uniformBuffer != null) ? Math.max(uniformBuffer.getUsedSize(), 16) : 16;
		int uboSizeInWords = (uboSizeInBytes + 3) / 4;
		this.builtManualUBO = new ManualUBO(0, vkShaderStageAllGraphics, uboSizeInWords);
		if (uniformBuffer != null) {
			this.builtManualUBO.setSrc(uniformBuffer.getPointer(), uniformBuffer.getUsedSize());
		}
		ubos.add(this.builtManualUBO);

		// Sampler ImageDescriptors at bindings 1, 2, 3...
		// Use the ProgramSamplers mapping for texture unit indices
		Map<String, Integer> samplerUnitMap = this.samplers.getSamplerNameToUnit();

		StringBuilder samplerDiag = new StringBuilder();
		for (int i = 0; i < uniqueSamplers.size(); i++) {
			String samplerName = uniqueSamplers.get(i);
			boolean fromMap = samplerUnitMap.containsKey(samplerName);
			int textureIdx = fromMap ? samplerUnitMap.get(samplerName) : mapSamplerToTextureIndex(samplerName);
			imageDescriptors.add(new ImageDescriptor(i + 1, "sampler2D", samplerName, textureIdx));
			samplerDiag.append(String.format("\n  [binding=%d] %s -> texIdx=%d (%s)",
				i + 1, samplerName, textureIdx, fromMap ? "samplerUnitMap" : "FALLBACK"));
		}

		// Build with POSITION_TEX format (composite full-screen quad)
		Pipeline.Builder builder = new Pipeline.Builder(DefaultVertexFormat.POSITION_TEX, name);
		builder.setUniforms(ubos, imageDescriptors);
		builder.setSPIRVs(vertSPIRV, fragSPIRV);
		GraphicsPipeline pipeline = builder.createGraphicsPipeline();

		Iris.logger.info("Created Vulkan pipeline for composite '{}': {} samplers, {} UBO bytes, samplerUnitMap={}{}",
			name, uniqueSamplers.size(), uboSizeInBytes, samplerUnitMap, samplerDiag);

		return pipeline;
	}

	private static void collectSamplerNamesToList(String source, List<String> names) {
		for (String line : source.split("\n")) {
			Matcher m = SAMPLER_PATTERN.matcher(line.trim());
			if (m.find()) {
				String samplerName = m.group(2);
				if (!names.contains(samplerName)) {
					names.add(samplerName);
				}
			}
		}
	}

	/**
	 * Adds explicit layout(location=N) qualifiers to vertex shader input declarations
	 * to match VulkanMod's POSITION_TEX vertex format:
	 *   location 0 = Position (vec3)
	 *   location 1 = UV0 (vec2)
	 *
	 * Without explicit qualifiers, shaderc's auto-map assigns locations by declaration
	 * order in the source, which may not match the vertex buffer layout if
	 * CompositeTransformer injected UV0 before Position.
	 */
	private static String fixCompositeVertexInputLocations(String vshSource) {
		// Only fix declarations that don't already have layout qualifiers
		vshSource = vshSource.replaceAll(
			"(?m)^(\\s*)in\\s+vec3\\s+Position\\s*;",
			"$1layout(location = 0) in vec3 Position;");
		vshSource = vshSource.replaceAll(
			"(?m)^(\\s*)in\\s+vec2\\s+UV0\\s*;",
			"$1layout(location = 1) in vec2 UV0;");
		return vshSource;
	}

	/**
	 * Maps well-known sampler names to VTextureSelector texture indices.
	 * Must match ExtendedShader.mapSamplerToTextureIndex() for consistency.
	 * Follows the standard Iris/OptiFine texture unit convention.
	 */
	private static int mapSamplerToTextureIndex(String name) {
		return switch (name) {
			case "gtexture", "gcolor", "colortex0", "tex", "texture" -> 0;
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
			default -> {
				// colortexN (N >= 7) → N + 5 to continue after slot 11
				if (name.startsWith("colortex")) {
					try {
						int n = Integer.parseInt(name.substring(8));
						if (n >= 7) yield n + 5;
						yield 0;
					}
					catch (NumberFormatException e) { yield 0; }
				}
				if (name.startsWith("gaux")) {
					try { yield Integer.parseInt(name.substring(4)) + 15; }
					catch (NumberFormatException e) { yield 0; }
				}
				yield 0;
			}
		};
	}

	private static String addExplicitBindings(String source, Map<String, Integer> samplerBindings) {
		String[] lines = source.split("\n", -1);
		List<String> output = new ArrayList<>();

		for (String line : lines) {
			String trimmed = line.trim();

			if (trimmed.equals("layout(std140) uniform IrisUniforms {")) {
				output.add("layout(std140, binding = 0) uniform IrisUniforms {");
				continue;
			}

			Matcher m = SAMPLER_PATTERN.matcher(trimmed);
			if (m.find()) {
				String samplerType = m.group(1);
				String samplerName = m.group(2);
				Integer binding = samplerBindings.get(samplerName);
				if (binding != null) {
					String fullLine = trimmed.substring(m.end());
					output.add("layout(binding = " + binding + ") uniform " + samplerType + " " + samplerName + fullLine);
					continue;
				}
			}

			output.add(line);
		}

		return String.join("\n", output);
	}

	public ComputeProgram buildCompute() {
		return new ComputeProgram(program, super.buildUniforms(), this.samplers.build(), this.images.build());
	}

	@Override
	public void addExternalSampler(int textureUnit, String... names) {
		samplers.addExternalSampler(textureUnit, names);
	}

	@Override
	public boolean hasSampler(String name) {
		return samplers.hasSampler(name);
	}

	@Override
	public boolean addDefaultSampler(IntSupplier sampler, String... names) {
		return samplers.addDefaultSampler(sampler, names);
	}

	@Override
	public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
		return samplers.addDefaultSampler(type, texture, notifier, sampler, names);
	}

	@Override
	public boolean addDynamicSampler(IntSupplier sampler, String... names) {
		return samplers.addDynamicSampler(sampler, names);
	}

	@Override
	public boolean addDynamicSampler(TextureType type, IntSupplier texture, GlSampler sampler, String... names) {
		return samplers.addDynamicSampler(type, texture, sampler, names);
	}

	public boolean addDynamicSampler(IntSupplier sampler, ValueUpdateNotifier notifier, String... names) {
		return samplers.addDynamicSampler(sampler, notifier, names);
	}

	@Override
	public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
		return samplers.addDynamicSampler(type, texture, notifier, sampler, names);
	}

	@Override
	public boolean hasImage(String name) {
		return images.hasImage(name);
	}

	@Override
	public void addTextureImage(IntSupplier textureID, InternalTextureFormat internalFormat, String name) {
		images.addTextureImage(textureID, internalFormat, name);
	}

	private static void dumpCompositeShader(String filename, String source) {
		if (source == null) return;
		try {
			java.io.File dir = new java.io.File("iris-debug");
			if (!dir.exists()) dir.mkdirs();
			java.io.File file = new java.io.File(dir, filename + ".glsl");
			java.nio.file.Files.writeString(file.toPath(), source);
			Iris.logger.info("[ProgramBuilder] Dumped composite shader to {}", file.getAbsolutePath());
		} catch (Exception e) {
			Iris.logger.warn("[ProgramBuilder] Failed to dump composite shader {}: {}", filename, e.getMessage());
		}
	}
}
