package net.irisshaders.iris.pipeline.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.uniform.IrisUniformBuffer;
import net.irisshaders.iris.pipeline.VulkanTerrainPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Vector3d;
import net.irisshaders.iris.vulkan.shader.IrisSPIRVCompiler;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;

/**
 * Compiles Iris terrain shader GLSL sources into VulkanMod GraphicsPipeline objects.
 *
 * Takes transformed shader sources from VulkanTerrainPipeline, preprocesses them
 * for Vulkan (UBO wrapping, vertex attribute type corrections, push constants),
 * compiles to SPIR-V, and builds GraphicsPipeline with COMPRESSED_TERRAIN format.
 */
public class IrisTerrainPipelineCompiler {

	private static final Pattern SAMPLER_PATTERN = Pattern.compile(
		"^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+((?:sampler|isampler|usampler)\\w+)\\s+(\\w+)");

	private GraphicsPipeline solidPipeline;
	private GraphicsPipeline cutoutPipeline;
	private GraphicsPipeline translucentPipeline;

	// Uniform buffers and ManualUBOs — one per pipeline since UBO layouts may differ
	private IrisUniformBuffer solidUniformBuffer;
	private IrisUniformBuffer cutoutUniformBuffer;
	private IrisUniformBuffer translucentUniformBuffer;
	private ManualUBO solidManualUBO;

	// Shadow terrain pipelines — use the shader pack's shadow.vsh/shadow.fsh
	// which apply the same distortion as the fragment shader's GetShadowPos() lookup
	private GraphicsPipeline shadowSolidPipeline;
	private GraphicsPipeline shadowCutoutPipeline;
	private IrisUniformBuffer shadowUniformBuffer;

	public void compile(VulkanTerrainPipeline terrainPipeline) {

		solidPipeline = tryCompileIrisShader("iris_terrain_solid",
			terrainPipeline.getTerrainSolidVertexShaderSource(),
			terrainPipeline.getTerrainSolidFragmentShaderSource());

		cutoutPipeline = tryCompileIrisShader("iris_terrain_cutout",
			terrainPipeline.getTerrainCutoutVertexShaderSource(),
			terrainPipeline.getTerrainCutoutFragmentShaderSource());

		translucentPipeline = tryCompileIrisShader("iris_terrain_translucent",
			terrainPipeline.getTranslucentVertexShaderSource(),
			terrainPipeline.getTranslucentFragmentShaderSource());

		// Compile shadow terrain shaders from the pack's shadow.vsh/shadow.fsh.
		// These include the shadow distortion that matches GetShadowPos() in the fragment shader.
		// Without these, the shadow depth map coordinates don't match the lookup → artifacts.
		shadowSolidPipeline = tryCompileShadowShader("iris_shadow_solid",
			terrainPipeline.getShadowVertexShaderSource(),
			terrainPipeline.getShadowFragmentShaderSource());
		shadowCutoutPipeline = tryCompileShadowShader("iris_shadow_cutout",
			terrainPipeline.getShadowVertexShaderSource(),
			terrainPipeline.getShadowCutoutFragmentShaderSource());
		if (shadowCutoutPipeline == null) shadowCutoutPipeline = shadowSolidPipeline;

		Iris.logger.info("[IrisTerrainPipelineCompiler] Compiled terrain pipelines (real Iris shaders with MinimalTest fallback). Shadow: {}",
			shadowSolidPipeline != null ? "compiled" : "not available");
	}

	private GraphicsPipeline tryCompileIrisShader(String name,
			java.util.Optional<String> vertOpt, java.util.Optional<String> fragOpt) {
		if (vertOpt != null && vertOpt.isPresent() && fragOpt != null && fragOpt.isPresent()) {
			try {
				GraphicsPipeline pipeline = compilePipeline(name, vertOpt.get(), fragOpt.get());
				Iris.logger.info("[IrisTerrainPipelineCompiler] Compiled REAL Iris shader: {}", name);
				return pipeline;
			} catch (Exception e) {
				Iris.logger.error("[IrisTerrainPipelineCompiler] Failed to compile Iris shader {}, falling back", name, e);
			}
		}

		try {
			GraphicsPipeline pipeline = compileMinimalTestPipeline(name);
			Iris.logger.info("[IrisTerrainPipelineCompiler] Compiled fallback test pipeline: {}", name);
			return pipeline;
		} catch (Exception e) {
			Iris.logger.error("[IrisTerrainPipelineCompiler] Failed to compile test pipeline: {}", name, e);
			return null;
		}
	}

	private GraphicsPipeline tryCompileShadowShader(String name,
			java.util.Optional<String> vertOpt, java.util.Optional<String> fragOpt) {
		if (vertOpt != null && vertOpt.isPresent() && fragOpt != null && fragOpt.isPresent()) {
			try {
				GraphicsPipeline pipeline = compilePipeline(name, vertOpt.get(), fragOpt.get(), true);
				Iris.logger.info("[IrisTerrainPipelineCompiler] Compiled shadow shader: {}", name);
				return pipeline;
			} catch (Exception e) {
				Iris.logger.error("[IrisTerrainPipelineCompiler] Failed to compile shadow shader {}", name, e);
			}
		}
		return null;
	}

	private GraphicsPipeline compilePipeline(String name, String vertSource, String fragSource) {
		return compilePipeline(name, vertSource, fragSource, false);
	}

	private GraphicsPipeline compilePipeline(String name, String vertSource, String fragSource, boolean isShadow) {
		// Step 1: Collect and merge uniforms from both shaders
		@SuppressWarnings("unchecked")
		List<IrisSPIRVCompiler.UniformField> merged = IrisSPIRVCompiler.mergeUniforms(
			IrisSPIRVCompiler.collectLooseUniforms(vertSource),
			IrisSPIRVCompiler.collectLooseUniforms(fragSource)
		);

		// Remove u_RegionOffset from uniform list — it becomes a push constant
		merged.removeIf(f -> f.name().equals("u_RegionOffset"));

		// Step 2: Preprocess for Vulkan with shared UBO layout
		String vshVulkan = IrisSPIRVCompiler.prepareForVulkan(vertSource, merged);
		String fshVulkan = IrisSPIRVCompiler.prepareForVulkan(fragSource, merged);

		// Step 3: Terrain-specific preprocessing
		vshVulkan = fixTerrainVertexAttributes(vshVulkan);
		vshVulkan = fixTerrainPositionDecoding(vshVulkan);
		vshVulkan = addPushConstantBlock(vshVulkan);
		vshVulkan = renameRegionOffset(vshVulkan);
		fshVulkan = renameRegionOffset(fshVulkan);
		// Shadow sampling NO LONGER bypassed — IrisSPIRVCompiler.convertShadowComparison()
		// handles sampler2DShadow → sampler2D conversion with manual bilinear PCF.
		// Shadow textures are bound to VTextureSelector by IrisTerrainRenderHook.
		// NOTE: Do NOT call convertShadowSamplerTypes() here! convertShadowComparison()
		// needs to see sampler2DShadow to know which texture() calls to convert.
		if (isShadow) {
			// Shadow vertex shader needs depth correction: OpenGL NDC [-1,1] → Vulkan [0,1]
			// The shadow pack shader outputs OpenGL-style z (matching the lookup in GetShadowPos)
			// but Vulkan depth buffer expects [0,1]
			vshVulkan = addShadowDepthCorrection(vshVulkan);
		} else {
			// TAA jitter is handled entirely in GLSL by the shader pack's TAAJitter() function.
			// The required uniforms (framemod8, viewWidth, viewHeight) are already written to the UBO.
			// Composite TAA resolve passes are running, so jitter is properly resolved.
			fshVulkan = injectFragmentNormals(fshVulkan);
		}
		// Tone mapping removed — composite/final passes handle this

		// NOTE: No Vulkan depth correction needed here. iris_ProjectionMatrix is already
		// Vulkan-style [0,1] depth range, so the depth buffer naturally stores [0,1] values.
		// OpenGL and Vulkan depth buffer values are IDENTICAL for the same scene:
		//   OpenGL depth = (z_ndc_gl + 1)/2 = (2*z_ndc_vk - 1 + 1)/2 = z_ndc_vk = Vulkan depth.
		// Shader packs correctly reconstruct via: ndc_z = depth * 2.0 - 1.0
		// then use gbufferProjectionInverse (OpenGL-style) for position reconstruction.
		// The previously applied correction (z*0.5 + w*0.5) was WRONG — it shifted depth
		// to [0.5, 1.0], causing broken position reconstruction, shadows, and god rays.

		// Step 4: Collect sampler names and add explicit bindings
		List<String> samplerNames = new ArrayList<>();
		collectSamplerNames(vshVulkan, samplerNames);
		collectSamplerNames(fshVulkan, samplerNames);
		List<String> uniqueSamplers = new ArrayList<>(new LinkedHashSet<>(samplerNames));

		Map<String, Integer> samplerBindings = new LinkedHashMap<>();
		for (int i = 0; i < uniqueSamplers.size(); i++) {
			samplerBindings.put(uniqueSamplers.get(i), i + 1); // binding 0 = UBO
		}

		vshVulkan = addExplicitBindings(vshVulkan, samplerBindings);
		fshVulkan = addExplicitBindings(fshVulkan, samplerBindings);

		// Step 5: Create IrisUniformBuffer from processed source
		String uboSource = vshVulkan.contains("IrisUniforms") ? vshVulkan : fshVulkan;
		IrisUniformBuffer uniformBuffer = IrisUniformBuffer.fromVulkanGLSL(uboSource);

		// Store uniform buffer for each pipeline (all need updating each frame)
		if (isShadow) {
			if (this.shadowUniformBuffer == null) {
				this.shadowUniformBuffer = uniformBuffer;
			}
		} else if (name.contains("solid")) {
			this.solidUniformBuffer = uniformBuffer;
		} else if (name.contains("cutout")) {
			this.cutoutUniformBuffer = uniformBuffer;
		} else if (name.contains("translucent")) {
			this.translucentUniformBuffer = uniformBuffer;
		}

		// Step 6: Dump transformed shaders to files for debugging
		dumpShaderToFile(name + ".vsh", vshVulkan);
		dumpShaderToFile(name + ".fsh", fshVulkan);

		// Step 7: Compile to SPIR-V
		Iris.logger.info("[IrisTerrainPipelineCompiler] Compiling SPIR-V for {}", name);

		ByteBuffer vertSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".vsh", vshVulkan, ShaderType.VERTEX);
		ByteBuffer fragSpirv = IrisSPIRVCompiler.compilePreprocessed(name + ".fsh", fshVulkan, ShaderType.FRAGMENT);

		SPIRVUtils.SPIRV vertSPIRV = new SPIRVUtils.SPIRV(0, vertSpirv);
		SPIRVUtils.SPIRV fragSPIRV = new SPIRVUtils.SPIRV(0, fragSpirv);

		// Step 8: Build Pipeline
		List<UBO> ubos = new ArrayList<>();
		List<ImageDescriptor> imageDescriptors = new ArrayList<>();

		// ManualUBO for IrisUniforms at binding 0
		int uboSizeBytes = Math.max(uniformBuffer.getUsedSize(), 16);
		int uboSizeWords = (uboSizeBytes + 3) / 4;
		ManualUBO manualUBO = new ManualUBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, uboSizeWords);
		manualUBO.setSrc(uniformBuffer.getPointer(), uniformBuffer.getUsedSize());
		ubos.add(manualUBO);

		if (name.contains("solid")) {
			this.solidManualUBO = manualUBO;
		}

		// ImageDescriptors for samplers
		for (int i = 0; i < uniqueSamplers.size(); i++) {
			String samplerName = uniqueSamplers.get(i);
			int texIdx = mapSamplerToTextureIndex(samplerName);
			imageDescriptors.add(new ImageDescriptor(i + 1, "sampler2D", samplerName, texIdx));
		}

		// Build with COMPRESSED_TERRAIN format
		Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.COMPRESSED_TERRAIN, name);
		builder.setUniforms(ubos, imageDescriptors);
		builder.setSPIRVs(vertSPIRV, fragSPIRV);

		// Add push constants for ChunkOffset (vec3) — matches VulkanMod's terrain push constants
		AlignedStruct.Builder pcBuilder = new AlignedStruct.Builder();
		pcBuilder.addUniformInfo("float", "ChunkOffset", 3);
		PushConstants pushConstants = pcBuilder.buildPushConstant();
		builder.setPushConstants(pushConstants);

		GraphicsPipeline pipeline = builder.createGraphicsPipeline();

		Iris.logger.info("[IrisTerrainPipelineCompiler] Built pipeline {} ({} samplers, {} UBO bytes)",
			name, uniqueSamplers.size(), uboSizeBytes);

		return pipeline;
	}

	/**
	 * Fix vertex attribute types to match COMPRESSED_TERRAIN VkFormat:
	 * - Position (SHORT×4) → VK_FORMAT_R16G16B16A16_SINT → ivec4
	 * - Color (UBYTE×4) → VK_FORMAT_R8G8B8A8_UNORM → vec4
	 * - UV0 (USHORT×2) → VK_FORMAT_R16G16_UINT → uvec2
	 * - UV2 (SHORT×2) → VK_FORMAT_R16G16_SINT → ivec2
	 *
	 * Also strips 'in' qualifier from standard Iris vertex inputs (_vert_position,
	 * _vert_color, etc.) that are computed by _vert_init() from terrain attributes.
	 * Without this, shaderc auto_map_locations assigns them conflicting locations.
	 *
	 * Converts Iris-specific vertex inputs (mc_midTexCoord, mc_Entity) to constants
	 * since COMPRESSED_TERRAIN format doesn't include them.
	 */
	private String fixTerrainVertexAttributes(String source) {
		// === Step 1: Fix terrain attribute types and add explicit locations ===

		// Fix a_PosId: uvec4 → ivec4 (SINT format)
		source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+[ui]?vec4\\s+a_PosId",
			"layout(location=0) in ivec4 a_PosId");

		// Fix a_Color: already vec4 (UNORM format) — just add location
		source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+vec4\\s+a_Color",
			"layout(location=1) in vec4 a_Color");

		// Fix a_TexCoord: vec2 → uvec2 (UINT format), with #define for automatic conversion
		if (source.matches("(?s).*\\bin\\s+vec2\\s+a_TexCoord.*")) {
			source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+vec2\\s+a_TexCoord\\s*;",
				"layout(location=2) in uvec2 _iris_TexCoord_raw;\n#define a_TexCoord vec2(_iris_TexCoord_raw)");
		} else {
			source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+uvec2\\s+a_TexCoord",
				"layout(location=2) in uvec2 a_TexCoord");
		}

		// Convert a_LightCoord to a constant instead of a vertex input.
		// VulkanMod's CompressedVertexBuilder never writes UV2 (bytes 16-19 of the
		// 20-byte stride), so location 3 reads uninitialized garbage.
		// Light data is correctly extracted from a_PosId.w in fixTerrainPositionDecoding().
		source = source.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+ivec2\\s+a_LightCoord\\s*;",
			"ivec2 a_LightCoord = ivec2(0); // UV2 not written by CompressedVertexBuilder");

		// === Step 2: Strip 'in' from standard Iris vertex inputs ===
		// These are computed by _vert_init() from the terrain attributes, NOT from vertex buffer.
		// If left as 'in', shaderc auto_map_locations gives them locations 0-N that conflict
		// with terrain attribute locations.
		String[] irisVertInputs = {
			"_vert_position", "_vert_color", "_vert_tex_diffuse_coord",
			"_vert_tex_light_coord", "_draw_id", "_material_params"
		};
		for (String varName : irisVertInputs) {
			// Match: [layout(...)] in <type> <varName>  →  <type> <varName>
			source = source.replaceAll(
				"(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+(\\w+\\s+" + Pattern.quote(varName) + ")",
				"$1");
		}

		// === Step 3: Convert Iris-specific vertex inputs to constants ===
		// COMPRESSED_TERRAIN format doesn't include these. Match any type.
		String[] irisSpecialInputs = {
			"mc_midTexCoord", "mc_Entity", "at_midBlock", "at_tangent", "iris_Normal"
		};
		for (String varName : irisSpecialInputs) {
			// Match: [layout(...)] in <type> <varName>;  →  <type> <varName> = <default>;
			source = source.replaceAll(
				"(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+(\\w+)\\s+" + Pattern.quote(varName) + "\\s*;",
				"$1 " + varName + " = " + getDefaultInitializer(varName) + ";");
		}

		// === Step 4: Catch-all for ANY remaining non-terrain 'in' declarations ===
		// Strip 'in' from anything that's not one of our 4 terrain attributes.
		// This prevents shaderc auto_map_locations from creating conflicts.
		String[] terrainAttribs = {"a_PosId", "a_Color", "_iris_TexCoord_raw", "a_TexCoord"};
		Set<String> terrainSet = new HashSet<>(Arrays.asList(terrainAttribs));
		StringBuilder sb = new StringBuilder();
		for (String line : source.split("\n", -1)) {
			String trimmed = line.trim();
			// Check if this is an 'in' declaration not for terrain attributes
			if (trimmed.matches("(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+\\w+\\s+\\w+.*")) {
				boolean isTerrain = false;
				for (String ta : terrainAttribs) {
					if (trimmed.contains(" " + ta)) {
						isTerrain = true;
						break;
					}
				}
				if (!isTerrain) {
					// Strip the 'in' qualifier — convert to plain global variable
					line = line.replaceAll("(?:layout\\s*\\([^)]*\\)\\s*)?\\bin\\s+", "");
					Iris.logger.warn("[IrisTerrainPipelineCompiler] Stripped unexpected 'in' decl: {}", trimmed);
				}
			}
			sb.append(line).append("\n");
		}
		source = sb.toString();

		return source;
	}

	/**
	 * Returns a sensible GLSL default initializer for Iris-specific vertex inputs
	 * that aren't available in COMPRESSED_TERRAIN format.
	 */
	private String getDefaultInitializer(String varName) {
		return switch (varName) {
			case "mc_midTexCoord" -> "vec2(0.0)"; // mid-texture coord, will use type from declaration
			case "mc_Entity" -> "vec4(-1.0)";     // entity/block ID
			case "at_midBlock" -> "vec3(0.0)";    // mid-block offset
			case "at_tangent" -> "vec4(1.0, 0.0, 0.0, 1.0)"; // tangent
			case "iris_Normal" -> "vec3(0.0, 1.0, 0.0)";     // up normal
			default -> "0";
		};
	}

	/**
	 * Fix position decoding for VulkanMod's COMPRESSED_TERRAIN format.
	 *
	 * Iris/Sodium encodes terrain positions as unsigned 16-bit with an 8-block offset:
	 *   encoded = (blockPos + 8.0) * 2048  →  decode: val * (1/2048) - 8.0
	 *
	 * VulkanMod encodes as signed 16-bit without offset:
	 *   encoded = blockPos * 2048  →  decode: val * (1/2048)
	 *
	 * Iris extracts chunk draw-translation from a_PosId.w, but VulkanMod stores
	 * lightmap data there instead. VulkanMod passes chunk offset via gl_InstanceIndex.
	 *
	 * This method patches _vert_init() and _get_draw_translation() to use VulkanMod's
	 * encoding scheme.
	 */
	private String fixTerrainPositionDecoding(String source) {
		// Replace Iris/Sodium position decoding (unsigned + offset) with VulkanMod's (signed, no offset).
		// The glsl_transformer AST printer may reformat the expression: drop outer parentheses,
		// change numeric literal format, rewrite "+-" as "-", wrap negatives in parens, etc.
		// We use multiple patterns in priority order to handle these variations.
		//
		// Iris:     _vert_position = (vec3(a_PosId.xyz) * 4.8828125E-4f + -8.0f);
		// VulkanMod: pos = fma(Position.xyz, vec3(1/2048), ChunkOffset + baseOffset);
		// We include baseOffset (from gl_InstanceIndex) in _vert_position so _get_draw_translation() can return 0.
		String posReplacement = "_vert_position = vec3(a_PosId.xyz) * vec3(1.0 / 2048.0) + " +
			"vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8))";

		// Pattern 1: Original — with outer parens: (vec3(a_PosId.xyz) * CONST + -8.0)
		String replaced = source.replaceAll(
			"_vert_position\\s*=\\s*\\(vec3\\(a_PosId\\.xyz\\)\\s*\\*\\s*[0-9.eE+-]+f?\\s*\\+\\s*-8\\.0f?\\)",
			posReplacement);

		// Pattern 2: No outer parens, "- 8.0" instead of "+ -8.0"
		if (replaced.equals(source)) {
			replaced = source.replaceAll(
				"_vert_position\\s*=\\s*vec3\\s*\\(\\s*a_PosId\\.xyz\\s*\\)\\s*\\*\\s*[0-9.eE+-]+f?\\s*(?:\\+\\s*-|-)\\s*8\\.0f?",
				posReplacement);
		}

		// Pattern 3: Parenthesized negative: + (-8.0) or (+ (-8.0f))
		if (replaced.equals(source)) {
			replaced = source.replaceAll(
				"_vert_position\\s*=\\s*\\(?\\s*vec3\\s*\\(\\s*a_PosId\\.xyz\\s*\\)\\s*\\*\\s*[0-9.eE+-]+f?\\s*\\+\\s*\\(\\s*-\\s*8\\.0f?\\s*\\)\\s*\\)?",
				posReplacement);
		}

		// Pattern 4: Broadest fallback — any _vert_position assignment referencing a_PosId.xyz
		// in the same statement (before the semicolon). Safe because this only appears in _vert_init().
		if (replaced.equals(source)) {
			replaced = source.replaceAll(
				"_vert_position\\s*=\\s*[^;]*?vec3\\s*\\(\\s*a_PosId\\.xyz\\s*\\)[^;]*",
				posReplacement);
		}

		if (replaced.equals(source)) {
			Iris.logger.warn("[IrisTerrainPipelineCompiler] WARNING: _vert_position decoding pattern NOT matched! " +
				"Position decoding may be wrong. Searching for context...");
			int idx = source.indexOf("_vert_position");
			if (idx >= 0) {
				int end = source.indexOf(";", idx);
				if (end > idx) {
					Iris.logger.warn("[IrisTerrainPipelineCompiler] Found: {}",
						source.substring(idx, Math.min(end + 1, idx + 200)).trim());
				}
			} else {
				Iris.logger.warn("[IrisTerrainPipelineCompiler] _vert_position NOT FOUND in shader source at all!");
			}
		} else {
			Iris.logger.info("[IrisTerrainPipelineCompiler] Fixed _vert_position decoding for VulkanMod COMPRESSED_TERRAIN");
		}
		source = replaced;

		// Replace draw_id extraction — a_PosId.w contains lightmap data in VulkanMod, not draw ID.
		// Handle with/without parens, with/without 'u' suffix on shift amount and mask constant.
		String drawIdBefore = source;
		source = source.replaceAll(
			"_draw_id\\s*=\\s*\\(?\\s*a_PosId\\.w\\s*>>\\s*8u?\\s*\\)?\\s*&\\s*(?:0x[0-9a-fA-F]+u?|\\d+u?)",
			"_draw_id = 0u");
		if (source.equals(drawIdBefore)) {
			// Fallback: match any _draw_id assignment referencing a_PosId
			source = source.replaceAll(
				"_draw_id\\s*=\\s*[^;]*a_PosId[^;]*",
				"_draw_id = 0u");
		}

		// Replace _material_params extraction — same issue, a_PosId.w is lightmap data
		source = source.replaceAll(
			"_material_params\\s*=\\s*\\(?\\s*a_PosId\\.w\\s*>>\\s*0u?\\s*\\)?\\s*&\\s*(?:0x[0-9a-fA-F]+u?|\\d+u?)",
			"_material_params = 0u");

		// Extract lightmap from a_PosId.w instead of a_LightCoord (UV2).
		// VulkanMod's COMPRESSED_TERRAIN format packs light into position.W (offset 6-7)
		// but never writes UV2 (offset 16-19), so a_LightCoord is always ivec2(0,0).
		// The packed short contains: low byte = blockLight (0-240), high byte = skyLight (0-240).
		// VulkanMod packing: short l = (short)(((light >>> 8) & 0xFF00) | (light & 0xFF))
		// where light = blockLight | (skyLight << 16), both in 0-240 range (level * 16).
		source = source.replaceAll(
			"_vert_tex_light_coord\\s*=\\s*a_LightCoord\\s*;",
			"_vert_tex_light_coord = ivec2(a_PosId.w & 0xFF, (a_PosId.w >> 8) & 0xFF);");

		// Fix midCoord: mc_midTexCoord is unavailable in COMPRESSED_TERRAIN (defaults to vec2(0)),
		// which makes iris_MidTex = (0,0,0,1) and midCoord = (0,0). This breaks atlas border
		// color averaging and tile randomization. Use texCoord instead — not the exact center
		// of the block's atlas region, but much better than (0,0).
		source = source.replaceAll(
			"midCoord\\s*=\\s*\\(mat4\\(1\\.0f?\\)\\s*\\*\\s*iris_MidTex\\)\\.st\\s*;",
			"midCoord = texCoord;");

		// Replace _get_draw_translation() — offset is already in _vert_position via baseOffset
		source = source.replaceAll(
			"vec3\\s+_get_draw_translation\\s*\\(uint\\s+\\w+\\)\\s*\\{[^}]*\\}",
			"vec3 _get_draw_translation(uint pos) { return vec3(0.0); }");

		// Replace _get_relative_chunk_coord() — not needed in VulkanMod
		source = source.replaceAll(
			"uvec3\\s+_get_relative_chunk_coord\\s*\\(uint\\s+\\w+\\)\\s*\\{[^}]*\\}",
			"uvec3 _get_relative_chunk_coord(uint pos) { return uvec3(0u); }");

		return source;
	}

	/**
	 * Bypasses shadow map sampling in fragment shaders.
	 *
	 * Shadow map textures (shadowtex0, shadowtex1) are not available in the Vulkan port
	 * yet — they're mapped to the block atlas as a fallback. Shadow depth comparison
	 * against block atlas color data returns "in shadow" for most fragments, making
	 * everything very dark.
	 *
	 * This method:
	 * 1. Replaces texture(shadowtex0/1, vec3(...)) shadow comparison calls with 1.0 (full light)
	 * 2. Changes sampler2DShadow to sampler2D to prevent type errors from remaining references
	 */
	private String bypassShadowSampling(String source) {
		// Step 1: Replace shadow depth comparison calls with 1.0 (no shadow).
		// texture(shadowtex0, vec3(uv, depth_ref)) does depth comparison → returns float.
		// We replace these with 1.0 meaning "fully lit, not in shadow."
		// Must be done BEFORE changing sampler2DShadow → sampler2D to avoid type errors.
		int beforeLen = source.length();
		source = source.replaceAll(
			"texture\\(shadowtex0\\s*,\\s*vec3\\s*\\([^)]*\\)\\s*\\)",
			"1.0 /* shadow bypass */");
		source = source.replaceAll(
			"texture\\(shadowtex1\\s*,\\s*vec3\\s*\\([^)]*\\)\\s*\\)",
			"1.0 /* shadow bypass */");

		// Also handle shadow2D() calls used by some shader packs
		source = source.replaceAll(
			"shadow2D\\(shadowtex0\\s*,\\s*vec3\\s*\\([^)]*\\)\\s*\\)",
			"vec4(1.0) /* shadow bypass */");
		source = source.replaceAll(
			"shadow2D\\(shadowtex1\\s*,\\s*vec3\\s*\\([^)]*\\)\\s*\\)",
			"vec4(1.0) /* shadow bypass */");

		int replacements = beforeLen - source.length();
		if (replacements != 0) {
			Iris.logger.info("[IrisTerrainPipelineCompiler] Shadow sampling bypassed ({} chars changed)", Math.abs(replacements));
		}

		// Step 2: Change sampler2DShadow to sampler2D so remaining references compile.
		// Any remaining texture() calls on these samplers will use regular 2D sampling.
		source = source.replace("sampler2DShadow", "sampler2D");

		return source;
	}

	/**
	 * Converts sampler2DShadow to sampler2D without bypassing shadow reads.
	 * The actual shadow comparison conversion (texture → iris_shadowComp) is
	 * handled by IrisSPIRVCompiler.convertShadowComparisons() during SPIR-V compilation.
	 * This method only does the type conversion so the shader compiles.
	 */
	private String convertShadowSamplerTypes(String source) {
		source = source.replace("sampler2DShadow", "sampler2D");
		return source;
	}

	/**
	 * Adds Vulkan depth correction to a shadow vertex shader.
	 *
	 * Shadow vertex shaders (from the shader pack) output clip-space z in OpenGL NDC [-1,1]
	 * range because they apply custom z manipulation (distortion, compression) that expects
	 * the OpenGL depth pipeline. Vulkan's depth buffer stores z_ndc directly (no (z+1)/2
	 * viewport transform like OpenGL), so we manually apply the conversion.
	 *
	 * This is DIFFERENT from gbuffer terrain shaders, which use iris_ProjectionMatrix
	 * (already Vulkan [0,1]) and don't apply custom z manipulation.
	 */
	private String addShadowDepthCorrection(String vshSource) {
		int lastBrace = vshSource.lastIndexOf('}');
		if (lastBrace >= 0) {
			vshSource = vshSource.substring(0, lastBrace) +
				"    // Vulkan depth correction: map OpenGL NDC [-1,1] to Vulkan [0,1]\n" +
				"    gl_Position.z = gl_Position.z * 0.5 + gl_Position.w * 0.5;\n" +
				vshSource.substring(lastBrace);
			Iris.logger.info("[IrisTerrainPipelineCompiler] Added shadow depth correction");
		}
		return vshSource;
	}

	/**
	 * Applies tone mapping to fragment shader output.
	 *
	 * The shader pack outputs HDR values (>1.0 for bright areas) to iris_FragData0,
	 * expecting composite passes to apply tone mapping and gamma correction. Since
	 * composite passes are not yet active, we apply ACES filmic tone mapping
	 * directly in the fragment shader output.
	 *
	 * ACES preserves color saturation much better than Reinhard.
	 * No manual gamma — the swapchain blit handles sRGB conversion.
	 *
	 * TODO: Remove this when composite passes are implemented — they handle tone mapping.
	 */
	private String applyOutputToneMapping(String source) {
		// Insert ACES function definition before main()
		String acesFunc =
			"\nvec3 _iris_aces_tonemap(vec3 x) {\n" +
			"  float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;\n" +
			"  return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);\n" +
			"}\n";

		// Insert before void main()
		int mainIdx = source.indexOf("void main()");
		if (mainIdx < 0) mainIdx = source.indexOf("void main(");
		if (mainIdx >= 0) {
			source = source.substring(0, mainIdx) + acesFunc + source.substring(mainIdx);
		}

		// Replace iris_FragData0 = color with tone-mapped version
		// Exposure boost compensates for ACES being darker at mid-tones.
		// Gamma needed because swapchain is UNORM (not sRGB).
		String toneMap =
			"color.rgb = _iris_aces_tonemap(color.rgb * 1.2);\n" +
			"color.rgb = pow(color.rgb, vec3(1.0 / 2.2));\n" +
			"iris_FragData0 = color;";

		String replaced = source.replace("iris_FragData0 = color;", toneMap);
		if (!replaced.equals(source)) {
			Iris.logger.info("[IrisTerrainPipelineCompiler] Applied ACES tone mapping");
		}
		return replaced;
	}

	/**
	 * Bypasses TAA (Temporal Anti-Aliasing) jitter in vertex shaders.
	 *
	 * Shader packs apply a sub-pixel offset to gl_Position via TAAJitter() every frame,
	 * which is resolved by a TAA pass in the composite stage. Since the composite/deferred
	 * passes are not yet active in the Vulkan port, the unresolved jitter causes visible
	 * shimmering on all geometry.
	 *
	 * This method replaces the TAAJitter function body with an identity (returns input unchanged).
	 */
	private String bypassTAAJitter(String source) {
		// Replace the TAAJitter function body to return coord unchanged
		String replaced = source.replaceAll(
			"(vec2\\s+TAAJitter\\s*\\(vec2\\s+\\w+,\\s*float\\s+\\w+\\)\\s*\\{)[^}]*(\\})",
			"$1 return coord; $2");

		if (replaced.equals(source)) {
			// Try with the actual parameter name from the shader
			replaced = source.replaceAll(
				"vec2\\s+TAAJitter\\s*\\(vec2\\s+(\\w+),\\s*float\\s+\\w+\\)\\s*\\{[^}]*\\}",
				"vec2 TAAJitter(vec2 $1, float w) { return $1; }");
		}

		if (!replaced.equals(source)) {
			Iris.logger.info("[IrisTerrainPipelineCompiler] TAA jitter bypassed");
		}

		return replaced;
	}

	/**
	 * Adds Vulkan depth correction to a vertex shader.
	 *
	 * NOTE: This method is NO LONGER CALLED. It was incorrect because
	 * iris_ProjectionMatrix is already Vulkan-style [0,1], so the correction
	 * shifted depth from [0,1] to [0.5,1.0], breaking position reconstruction.
	 * Kept for reference only.
	 */
	static String addVulkanDepthCorrection(String vshSource) {
		// NO-OP: depth correction is NOT needed. See comment at call site.
		return vshSource;
	}

	/**
	 * Injects face normal computation into the terrain fragment shader.
	 *
	 * VulkanMod's COMPRESSED_TERRAIN vertex format doesn't include per-vertex normals,
	 * so iris_Normal is a constant vec3(0,1,0) — making all terrain faces appear to
	 * face upward. This causes wrong lighting for side faces (blue/purple speckling
	 * in the gbuffer normals).
	 *
	 * Fix: compute the geometric face normal from dFdx/dFdy on the interpolated
	 * player-space position varying, then transform to view-space. Uses dFdy×dFdx
	 * (swapped order) to compensate for VulkanMod's viewport Y-flip (negative height).
	 */
	private String injectFragmentNormals(String fshSource) {
		// Check if fragment shader has the expected varyings.
		// Different shader programs use different names for the position varying:
		// - solid/cutout: "vertexPos" (from _vert_init())
		// - translucent:  "playerPos" (from ComplementaryUnbound naming)
		if (!fshSource.contains("in vec3 normal")) {
			Iris.logger.warn("[IrisTerrainPipelineCompiler] Fragment shader missing 'in vec3 normal' varying, skipping normal injection");
			return fshSource;
		}

		String posVarying;
		if (fshSource.contains("in vec3 vertexPos")) {
			posVarying = "vertexPos";
		} else if (fshSource.contains("in vec3 playerPos")) {
			posVarying = "playerPos";
		} else {
			Iris.logger.warn("[IrisTerrainPipelineCompiler] Fragment shader missing position varying (vertexPos/playerPos), skipping normal injection");
			return fshSource;
		}

		// Step 1: Convert "layout(location=N) in vec3 normal;" to a regular variable
		// so we can write to it at the start of main()
		String before = fshSource;
		fshSource = fshSource.replaceAll(
			"layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*in\\s+vec3\\s+normal\\s*;",
			"vec3 normal;");

		if (fshSource.equals(before)) {
			// Try without layout qualifier
			fshSource = fshSource.replaceAll("\\bin\\s+vec3\\s+normal\\s*;", "vec3 normal;");
		}

		if (fshSource.equals(before)) {
			Iris.logger.warn("[IrisTerrainPipelineCompiler] Could not patch 'in vec3 normal' declaration");
			return fshSource;
		}

		// Step 2: Inject normal computation at start of main()
		// dFdy×dFdx (swapped) compensates for VulkanMod's viewport Y-flip.
		// Position varying is in player-space (world relative to camera);
		// gbufferModelView transforms from player-space to view-space.
		// We snap the raw derivative-based normal to the nearest axis because:
		// 1) Minecraft terrain faces are always axis-aligned (±X, ±Y, ±Z)
		// 2) dFdx/dFdy produces garbage at triangle edges from GPU helper invocations,
		//    causing visible grid lines at block boundaries. Snapping fixes these artifacts.
		fshSource = fshSource.replaceFirst(
			"(void\\s+main\\s*\\(\\s*\\)\\s*\\{)",
			"$1\n" +
			"    // Compute geometric face normal from position derivatives,\n" +
			"    // snapped to nearest axis (all Minecraft terrain is axis-aligned)\n" +
			"    {\n" +
			"        vec3 rawN = normalize(cross(dFdy(" + posVarying + "), dFdx(" + posVarying + ")));\n" +
			"        vec3 absN = abs(rawN);\n" +
			"        vec3 snappedN;\n" +
			"        if (absN.x >= absN.y && absN.x >= absN.z) snappedN = vec3(sign(rawN.x), 0.0, 0.0);\n" +
			"        else if (absN.y >= absN.z) snappedN = vec3(0.0, sign(rawN.y), 0.0);\n" +
			"        else snappedN = vec3(0.0, 0.0, sign(rawN.z));\n" +
			"        normal = normalize(mat3(gbufferModelView) * snappedN);\n" +
			"    }\n");

		Iris.logger.info("[IrisTerrainPipelineCompiler] Injected fragment face normal computation (using {})", posVarying);
		return fshSource;
	}

	/**
	 * Writes transformed shader source to a file for debugging.
	 * Files are written to the game directory under iris-debug/.
	 */
	private void dumpShaderToFile(String filename, String source) {
		try {
			java.io.File dir = new java.io.File("iris-debug");
			if (!dir.exists()) dir.mkdirs();
			java.io.File file = new java.io.File(dir, filename + ".glsl");
			java.nio.file.Files.writeString(file.toPath(), source);
			Iris.logger.info("[IrisTerrainPipelineCompiler] Dumped shader to {}", file.getAbsolutePath());
		} catch (Exception e) {
			Iris.logger.warn("[IrisTerrainPipelineCompiler] Failed to dump shader {}: {}", filename, e.getMessage());
		}
	}

	/**
	 * Add push constant block for ChunkOffset (replaces u_RegionOffset uniform).
	 * Inserts after the IrisUniforms block or at the top of the shader.
	 */
	private String addPushConstantBlock(String source) {
		String pushConstantBlock = "\nlayout(push_constant) uniform PushConstants {\n    vec3 ChunkOffset;\n};\n";

		// Insert after the IrisUniforms closing brace
		int uboEnd = source.indexOf("uniform IrisUniforms {");
		if (uboEnd >= 0) {
			int closingBrace = source.indexOf("};", uboEnd);
			if (closingBrace >= 0) {
				int insertPoint = closingBrace + 2;
				source = source.substring(0, insertPoint) + pushConstantBlock + source.substring(insertPoint);
				return source;
			}
		}

		// Fallback: insert after #version line
		int versionEnd = source.indexOf('\n');
		if (versionEnd >= 0) {
			source = source.substring(0, versionEnd + 1) + pushConstantBlock + source.substring(versionEnd + 1);
		}

		return source;
	}

	/**
	 * Rename u_RegionOffset references to ChunkOffset (push constant name).
	 * Also removes any remaining u_RegionOffset declarations that prepareForVulkan
	 * might have placed in the IrisUniforms block.
	 */
	private String renameRegionOffset(String source) {
		// Remove u_RegionOffset from IrisUniforms block if present
		source = source.replaceAll("\\s*vec3\\s+u_RegionOffset\\s*;", "");

		// Rename all references
		source = source.replace("u_RegionOffset", "ChunkOffset");

		return source;
	}

	private void collectSamplerNames(String source, List<String> names) {
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

	private String addExplicitBindings(String source, Map<String, Integer> samplerBindings) {
		String[] lines = source.split("\n", -1);
		List<String> output = new ArrayList<>();

		for (String line : lines) {
			String trimmed = line.trim();

			// Add binding = 0 to IrisUniforms block
			if (trimmed.equals("layout(std140) uniform IrisUniforms {")) {
				output.add("layout(std140, binding = 0) uniform IrisUniforms {");
				continue;
			}

			// Add binding to sampler declarations
			Matcher m = SAMPLER_PATTERN.matcher(trimmed);
			if (m.find()) {
				String samplerType = m.group(1);
				String samplerName = m.group(2);
				Integer binding = samplerBindings.get(samplerName);
				if (binding != null) {
					String rest = trimmed.substring(m.end());
					output.add("layout(binding = " + binding + ") uniform " + samplerType + " " + samplerName + rest);
					continue;
				}
			}

			output.add(line);
		}

		return String.join("\n", output);
	}

	/**
	 * Maps Iris sampler names to VTextureSelector slot indices.
	 *
	 * Currently only the block atlas (slot 0) and lightmap (slot 2) are properly
	 * bound by VulkanMod. Iris-managed textures are mapped to VTextureSelector
	 * indices matching the standard Iris sampler unit convention.
	 * Shadow textures are bound by IrisTerrainRenderHook.beginTerrainPass().
	 */
	private static int mapSamplerToTextureIndex(String name) {
		return switch (name) {
			case "gtexture", "tex", "texture", "Sampler0", "colortex0" -> 0; // block atlas
			case "lightmap", "Sampler2" -> 2; // lightmap texture
			case "colortex1", "normals", "gnormal" -> 3;
			case "colortex2", "specular", "gspecular" -> 4;
			case "colortex3" -> 5;
			case "colortex4", "gaux1" -> 6;
			case "colortex5", "gaux2" -> 7;
			case "colortex6", "gaux3" -> 8;
			case "colortex7", "gaux4" -> 9;
			case "colortex8" -> 10;
			case "depthtex0" -> 12;
			case "depthtex1" -> 13;
			case "shadowtex0", "shadow", "watershadow" -> 14;
			case "shadowtex1" -> 15;
			case "shadowcolor0", "shadowcolor" -> 16;
			case "shadowcolor1" -> 17;
			case "noisetex" -> 18;
			default -> 0; // safe fallback to block atlas
		};
	}

	/**
	 * Updates the IrisUniforms UBO data with current render state.
	 * Must be called before terrain draw commands.
	 */
	private int uniformLogCounter = 0;
	private int gbufferProjLogCount = 0;

	// Frame timing state
	private long lastFrameNanos = System.nanoTime();
	private float cumulativeTime = 0.0f;
	private int frameCount = 0;
	private float[] prevMvArr = new float[16];
	private float[] prevProjArr = new float[16];
	private double prevCamX, prevCamY, prevCamZ;
	private boolean hasPreviousFrame = false;
	private boolean hasPrevCamPos = false;
	private float currentVelocity = 0.0f;

	public void updateUniforms(org.joml.Matrix4f modelView, org.joml.Matrix4f projection, boolean isShadowPass) {
		// Select the appropriate UBO buffer for this pass
		IrisUniformBuffer buf;
		if (isShadowPass && shadowUniformBuffer != null) {
			buf = shadowUniformBuffer;
		} else if (solidUniformBuffer != null) {
			buf = solidUniformBuffer;
		} else {
			return; // No custom UBO available
		}
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();

		// Logged once at end of method

		// === MATRICES ===
		float[] mvArr = new float[16];
		modelView.get(mvArr);

		// VulkanMod may pass a projection matrix with infinite or incorrect m00/m11 values.
		// Always rebuild a clean projection from actual game parameters for gbuffer passes.
		org.joml.Matrix4f projection_clean = new org.joml.Matrix4f(projection);
		if (!isShadowPass && projection_clean.m23() != 0) { // perspective projection
			float far = client.options != null ? client.options.getEffectiveRenderDistance() * 16.0f : 256.0f;
			float near = 0.05f;

			// Always compute m00/m11 from FOV and aspect ratio — VulkanMod's projection
			// may have infinite or non-standard values that break shader pack lighting.
			double fovDegrees = 70.0; // default
			try {
				if (client.gameRenderer != null) {
					fovDegrees = ((net.irisshaders.iris.mixin.GameRendererAccessor) client.gameRenderer)
						.invokeGetFov(client.gameRenderer.getMainCamera(),
							client.getTimer().getGameTimeDeltaPartialTick(true), true);
				}
			} catch (Exception ignored) {}
			// Guard against zero/invalid FOV during initialization
			if (fovDegrees < 1.0 || !Double.isFinite(fovDegrees)) fovDegrees = 70.0;
			float fovRad = (float)(fovDegrees * Math.PI / 180.0);
			float tanHalfFov = (float) Math.tan(fovRad / 2.0);
			var window = client.getWindow();
			float aspect = (float) window.getWidth() / (float) window.getHeight();
			projection_clean.m00(1.0f / (aspect * tanHalfFov));
			projection_clean.m11(1.0f / tanHalfFov);

			// Fix infinite-far depth elements (m22, m32) with finite far
			projection_clean.m22(-far / (far - near));
			projection_clean.m32(-far * near / (far - near));

			if (gbufferProjLogCount < 1) {
				Iris.logger.info("[PROJ_FIX] Gbuffer projection rebuilt: rawM00={} rawM11={} -> m00={} m11={} fov={} aspect={} far={}",
					String.format("%.6f", projection.m00()), String.format("%.6f", projection.m11()),
					String.format("%.4f", projection_clean.m00()), String.format("%.4f", projection_clean.m11()),
					String.format("%.1f", fovDegrees), String.format("%.3f", aspect),
					String.format("%.1f", far));
				gbufferProjLogCount++;
			}
		}

		// Original Vulkan-style projection for iris_ProjectionMatrix (used by vertex
		// shader for gl_Position — must stay Vulkan [0,1] depth range for correct rendering)
		float[] projVkArr = new float[16];
		projection_clean.get(projVkArr);

		org.joml.Matrix4f projVkInv = new org.joml.Matrix4f(projection_clean).invert();
		float[] projVkInvArr = new float[16];
		projVkInv.get(projVkInvArr);

		// OpenGL-style projection for gbufferProjection (used by shader pack code for
		// position reconstruction via: clipZ = depth * 2.0 - 1.0, then gbufferProjectionInverse)
		org.joml.Matrix4f projGL = new org.joml.Matrix4f(projection_clean);
		// VulkanMod's Matrix4fM mixin forces zZeroToOne=true on perspective matrices
		// created via JOML .perspective(), so convert back to OpenGL [-1,1] depth range:
		//   m22_gl = 2*m22_vk - m23_vk, m32_gl = 2*m32_vk - m33_vk
		// BUT skip for shadow pass — the shadow ortho matrix from ShadowMatrices.createOrthoMatrix()
		// is constructed with raw column values (NOT through JOML .ortho()), so VulkanMod's
		// Matrix4fM mixin does NOT apply zZeroToOne conversion. It's already OpenGL-style.
		// Applying vulkanToOpenGLDepthRange would DOUBLE-CONVERT m22 (e.g., -2/(f-n) → -4/(f-n)),
		// making iris_ProjectionMatrix != shadowProjection. The shadow vertex shader does:
		//   position = shadowProjectionInverse * iris_ftransform()
		// which requires iris_ProjectionMatrix == shadowProjection for the chain to cancel properly.
		// With the double-conversion, shadow depth values are corrupted → VL overexposure.
		if (!isShadowPass) {
			projGL.m22(2.0f * projGL.m22() - projGL.m23());
			projGL.m32(2.0f * projGL.m32() - projGL.m33());
		}

		// IMPORTANT: Do NOT negate m11 for the terrain/entity UBO's gbufferProjection.
		// Terrain/entity fragment shaders use gl_FragCoord for ScreenToView() position
		// reconstruction. With VulkanMod's negative viewport, gl_FragCoord follows OpenGL
		// convention (Y=0 at bottom, Y increases upward), so no Y-flip compensation is
		// needed. Negating m11 here would invert the reconstructed viewPos.y, causing
		// wrong specular, fresnel, shadow, and fog calculations → kaleidoscope corruption.
		//
		// The m11 negation is ONLY needed for composite/deferred passes (Program.java),
		// where CompositeTransformer flips UV.y (texCoord.y = 1.0 - UV0.y), creating a
		// non-standard mapping that requires compensating the projection inverse.
		float[] projGLArr = new float[16];
		projGL.get(projGLArr);

		org.joml.Matrix4f projGLInv = new org.joml.Matrix4f(projGL).invert();
		float[] projGLInvArr = new float[16];
		projGLInv.get(projGLInvArr);

		// For the shadow pass iris_ProjectionMatrix, negate m11 to compensate for
		// VulkanMod's negative viewport during shadow rendering.
		float[] projGLShadowArr = null;
		float[] projGLShadowInvArr = null;
		if (isShadowPass) {
			org.joml.Matrix4f projGLShadow = new org.joml.Matrix4f(projGL);
			projGLShadow.m11(-projGLShadow.m11());
			projGLShadowArr = new float[16];
			projGLShadow.get(projGLShadowArr);
			projGLShadowInvArr = new float[16];
			new org.joml.Matrix4f(projGLShadow).invert().get(projGLShadowInvArr);
		}

		org.joml.Matrix4f mvInv = new org.joml.Matrix4f(modelView).invert();
		float[] mvInvArr = new float[16];
		mvInv.get(mvInvArr);

		org.joml.Matrix3f normalMat = new org.joml.Matrix3f(modelView).invert().transpose();
		float[] normArr = new float[9];
		normalMat.get(normArr);

		for (String name : new String[]{"iris_ModelViewMatrix", "gbufferModelView"}) {
			int off = buf.getFieldOffset(name);
			if (off >= 0) buf.writeMat4f(off, mvArr);
		}
		// iris_ProjectionMatrix: OpenGL-style with m11 negated during shadow pass (shadow
		// vertex shader applies custom z manipulation expecting OpenGL NDC, and the
		// negative viewport Y-flip needs compensation), Vulkan-style for gbuffer
		{
			int off = buf.getFieldOffset("iris_ProjectionMatrix");
			if (off >= 0) buf.writeMat4f(off, isShadowPass ? projGLShadowArr : projVkArr);
		}
		// gbufferProjection: OpenGL-style for shader pack position reconstruction
		{
			int off = buf.getFieldOffset("gbufferProjection");
			if (off >= 0) {
				buf.writeMat4f(off, projGLArr);
			}
		}
		for (String name : new String[]{"iris_ModelViewMatrixInverse", "gbufferModelViewInverse"}) {
			int off = buf.getFieldOffset(name);
			if (off >= 0) buf.writeMat4f(off, mvInvArr);
		}
		// iris_ProjectionMatrixInverse: matches iris_ProjectionMatrix style
		{
			int off = buf.getFieldOffset("iris_ProjectionMatrixInverse");
			if (off >= 0) buf.writeMat4f(off, isShadowPass ? projGLShadowInvArr : projVkInvArr);
		}
		// gbufferProjectionInverse: OpenGL-style inverse
		{
			int off = buf.getFieldOffset("gbufferProjectionInverse");
			if (off >= 0) buf.writeMat4f(off, projGLInvArr);
		}
		writeFloat(buf, "iris_NormalMatrix", normalOffset -> buf.writeMat3f(normalOffset, normArr));

		// Previous frame matrices
		if (hasPreviousFrame) {
			int off = buf.getFieldOffset("gbufferPreviousModelView");
			if (off >= 0) buf.writeMat4f(off, prevMvArr);
			off = buf.getFieldOffset("gbufferPreviousProjection");
			if (off >= 0) buf.writeMat4f(off, prevProjArr);
		}
		System.arraycopy(mvArr, 0, prevMvArr, 0, 16);
		System.arraycopy(projGLArr, 0, prevProjArr, 0, 16);
		hasPreviousFrame = true;

		// === CAMERA ===
		writeFloatField(buf, "near", 0.05f);
		if (client.options != null) {
			writeFloatField(buf, "far", client.options.getEffectiveRenderDistance() * 16.0f);
		}

		if (client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
			net.minecraft.world.phys.Vec3 camPos = client.gameRenderer.getMainCamera().getPosition();
			writeVec3Field(buf, "cameraPosition", (float) camPos.x, (float) camPos.y, (float) camPos.z);
			writeFloatField(buf, "eyeAltitude", (float) camPos.y);

			// Integer and fractional parts
			writeVec3iField(buf, "cameraPositionInt",
				(int) Math.floor(camPos.x), (int) Math.floor(camPos.y), (int) Math.floor(camPos.z));
			writeVec3Field(buf, "cameraPositionFract",
				(float)(camPos.x - Math.floor(camPos.x)),
				(float)(camPos.y - Math.floor(camPos.y)),
				(float)(camPos.z - Math.floor(camPos.z)));

			// relativeEyePosition = camera offset from eye (usually 0 for first person)
			writeVec3Field(buf, "relativeEyePosition", 0.0f, 0.0f, 0.0f);

			// Write previousCameraPosition BEFORE overwriting prevCam with current position
			if (hasPrevCamPos) {
				writeVec3Field(buf, "previousCameraPosition", (float) prevCamX, (float) prevCamY, (float) prevCamZ);
				writeVec3iField(buf, "previousCameraPositionInt",
					(int) Math.floor(prevCamX), (int) Math.floor(prevCamY), (int) Math.floor(prevCamZ));
				writeVec3Field(buf, "previousCameraPositionFract",
					(float)(prevCamX - Math.floor(prevCamX)),
					(float)(prevCamY - Math.floor(prevCamY)),
					(float)(prevCamZ - Math.floor(prevCamZ)));
				// Velocity: camera movement distance since last frame
				float dx = (float)(camPos.x - prevCamX);
				float dy = (float)(camPos.y - prevCamY);
				float dz = (float)(camPos.z - prevCamZ);
				currentVelocity = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
			}

			// Store current camera position for next frame
			prevCamX = camPos.x;
			prevCamY = camPos.y;
			prevCamZ = camPos.z;
			hasPrevCamPos = true;
		}

		// === VIEWPORT ===
		if (client.getMainRenderTarget() != null) {
			float w = client.getMainRenderTarget().width;
			float h = client.getMainRenderTarget().height;
			writeFloatField(buf, "viewWidth", w);
			writeFloatField(buf, "viewHeight", h);
			writeFloatField(buf, "aspectRatio", h > 0 ? w / h : 1.0f);
		}

		// === TIME ===
		long nowNanos = System.nanoTime();
		float frameTimeSec = (nowNanos - lastFrameNanos) / 1_000_000_000.0f;
		lastFrameNanos = nowNanos;
		cumulativeTime += frameTimeSec;
		if (cumulativeTime > 3600.0f) cumulativeTime -= 3600.0f;
		frameCount = (frameCount + 1) % 720720;

		writeFloatField(buf, "frameTime", frameTimeSec);
		writeFloatField(buf, "frameTimeCounter", cumulativeTime);
		writeIntField(buf, "frameCounter", frameCount);

		if (client.level != null) {
			long dayTime = client.level.getDayTime();
			writeIntField(buf, "worldTime", (int)(dayTime % 24000L));
			writeIntField(buf, "worldDay", (int)(dayTime / 24000L));
			writeIntField(buf, "moonPhase", client.level.getMoonPhase());

			// Sun angle (0-1 range): adapted from CelestialUniforms
			float tickDelta = client.getTimer().getGameTimeDeltaPartialTick(true);
			float skyAngle = client.level.getTimeOfDay(tickDelta);
			float sunAngle = skyAngle < 0.75f ? skyAngle + 0.25f : skyAngle - 0.75f;
			writeFloatField(buf, "sunAngle", sunAngle);

			// === CELESTIAL LIGHT POSITIONS ===
			// These are CRITICAL for deferred lighting — composite/deferred shaders compute
			// sunVec = normalize(sunPosition) in their vertex shader. Without these, all
			// directional lighting calculations fail, producing dark/black terrain.
			{
				// Get sunPathRotation from the active pipeline (shader pack property)
				float sunPathRotation = 0.0f;
				try {
					var pm = net.irisshaders.iris.Iris.getPipelineManager();
					if (pm != null) {
						var wp = pm.getPipelineNullable();
						if (wp instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
							sunPathRotation = irp.getSunPathRotation();
						}
					}
				} catch (Exception ignored) {}

				// Sun position: modelView * rotY(-90) * rotZ(sunPathRotation) * rotX(skyAngle*360) * (0, 100, 0, 0)
				// This replicates CelestialUniforms.getCelestialPosition()
				org.joml.Matrix4f celestial = new org.joml.Matrix4f(modelView);
				celestial.rotateY((float) Math.toRadians(-90.0));
				celestial.rotateZ((float) Math.toRadians(sunPathRotation));
				celestial.rotateX((float) Math.toRadians(skyAngle * 360.0f));

				org.joml.Vector4f sunPos = new org.joml.Vector4f(0, 100, 0, 0);
				celestial.transform(sunPos);
				writeVec3Field(buf, "sunPosition", sunPos.x(), sunPos.y(), sunPos.z());

				org.joml.Vector4f moonPos = new org.joml.Vector4f(0, -100, 0, 0);
				celestial.transform(moonPos);
				writeVec3Field(buf, "moonPosition", moonPos.x(), moonPos.y(), moonPos.z());

				// shadowLightPosition = sun during day, moon during night
				boolean isDay = sunAngle <= 0.5f;
				if (isDay) {
					writeVec3Field(buf, "shadowLightPosition", sunPos.x(), sunPos.y(), sunPos.z());
				} else {
					writeVec3Field(buf, "shadowLightPosition", moonPos.x(), moonPos.y(), moonPos.z());
				}

				// shadowAngle
				float shadowAngle = isDay ? sunAngle : sunAngle - 0.5f;
				writeFloatField(buf, "shadowAngle", shadowAngle);

				// upPosition: modelView * rotY(-90) * (0, 100, 0, 0) — no sky angle rotation
				org.joml.Matrix4f preCelestial = new org.joml.Matrix4f(modelView);
				preCelestial.rotateY((float) Math.toRadians(-90.0));
				org.joml.Vector4f upPos = new org.joml.Vector4f(0, 100, 0, 0);
				preCelestial.transform(upPos);
				writeVec3Field(buf, "upPosition", upPos.x(), upPos.y(), upPos.z());
			}

			// Complementary/BSL custom uniforms derived from time
			float timeAngle = (dayTime % 24000L) / 24000.0f;
			writeFloatField(buf, "timeAngle", timeAngle);
			writeFloatField(buf, "timeBrightness", (float) Math.max(Math.sin(timeAngle * Math.PI * 2.0), 0.0));
			writeFloatField(buf, "moonBrightness", (float) Math.max(Math.sin(timeAngle * Math.PI * -2.0), 0.0));
			// shadowFade: 1 during day, fades to 0 at sunrise/sunset when shadows disappear
			writeFloatField(buf, "shadowFade", (float) Math.max(0.0, Math.min(1.0,
				1.0 - (Math.abs(Math.abs(sunAngle - 0.5) - 0.25) - 0.23) * 100.0)));
			writeFloatField(buf, "shdFade", (float) Math.max(0.0, Math.min(1.0,
				1.0 - (Math.abs(Math.abs(sunAngle - 0.5) - 0.25) - 0.225) * 40.0)));

			// Weather
			float rainLevel = client.level.getRainLevel(tickDelta);
			writeFloatField(buf, "rainStrength", rainLevel);
			writeFloatField(buf, "wetness", rainLevel);
			writeFloatField(buf, "rainFactor", rainLevel);
			// Complementary smoothed rain variants — in terrain UBO we use the raw
			// value since we can't do temporal smoothing per-field here
			writeFloatField(buf, "rainStrengthS", rainLevel);
			writeFloatField(buf, "rainStrengthShiningStars", rainLevel);
			writeFloatField(buf, "rainStrengthS2", rainLevel);
			writeFloatField(buf, "isPrecipitationRain", rainLevel > 0 ? 1.0f : 0.0f);

			// Sky/fog color
			if (client.player != null) {
				net.minecraft.world.phys.Vec3 sky = client.level.getSkyColor(
					client.player.position(), tickDelta);
				writeVec3Field(buf, "skyColor", (float) sky.x, (float) sky.y, (float) sky.z);
			}

			// Cloud height
			writeFloatField(buf, "cloudHeight", client.level.effects().getCloudHeight());
		}

		// === PLAYER STATE ===
		// isEyeInWater: 0=air, 1=water, 2=lava, 3=powder_snow
		int eyeInWater = 0;
		if (client.gameRenderer != null && client.gameRenderer.getMainCamera() != null) {
			net.minecraft.world.level.material.FogType submersion = client.gameRenderer.getMainCamera().getFluidInCamera();
			if (submersion == net.minecraft.world.level.material.FogType.WATER) eyeInWater = 1;
			else if (submersion == net.minecraft.world.level.material.FogType.LAVA) eyeInWater = 2;
			else if (submersion == net.minecraft.world.level.material.FogType.POWDER_SNOW) eyeInWater = 3;
		}
		writeIntField(buf, "isEyeInWater", eyeInWater);

		float blindness = 0.0f;
		float darknessFactor = 0.0f;
		float nightVision = 0.0f;
		float playerMood = 0.0f;
		if (client.getCameraEntity() instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
			var blindnessEffect = livingEntity.getEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
			if (blindnessEffect != null) {
				blindness = blindnessEffect.isInfiniteDuration() ? 1.0f
					: Math.min(1.0f, blindnessEffect.getDuration() / 20.0f);
			}
			var darknessEffect = livingEntity.getEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
			if (darknessEffect != null) {
				float td = client.getTimer().getGameTimeDeltaPartialTick(true);
				darknessFactor = darknessEffect.getBlendFactor(livingEntity, td);
			}
			var nvEffect = livingEntity.getEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
			if (nvEffect != null) nightVision = 1.0f;
		}
		if (client.player != null) {
			playerMood = Math.max(0.0f, Math.min(1.0f, client.player.getCurrentMood()));
		}
		writeFloatField(buf, "blindness", blindness);
		float blindFactor = (float) Math.max(0.0, Math.min(1.0, blindness * 2.0 - 1.0));
		writeFloatField(buf, "blindFactor", blindFactor * blindFactor);
		writeFloatField(buf, "darknessFactor", darknessFactor);
		writeFloatField(buf, "darknessLightFactor", 0.0f);
		writeFloatField(buf, "maxBlindnessDarkness", Math.max(blindness, darknessFactor));
		writeFloatField(buf, "nightVision", nightVision);
		writeFloatField(buf, "screenBrightness", client.options != null ? client.options.gamma().get().floatValue() : 1.0f);
		writeFloatField(buf, "playerMood", playerMood);

		// Entity/item IDs (for terrain = -1 / 0)
		writeIntField(buf, "blockEntityId", -1);
		writeIntField(buf, "entityId", -1);
		writeIntField(buf, "currentRenderedItemId", -1);
		writeIntField(buf, "heldItemId", 0);
		writeIntField(buf, "heldItemId2", 0);
		writeIntField(buf, "heldBlockLightValue", 0);
		writeIntField(buf, "heldBlockLightValue2", 0);

		// Eye brightness (use eye position, not foot position, matching Iris's CommonUniforms)
		if (client.player != null && client.level != null) {
			net.minecraft.world.phys.Vec3 feet = client.player.position();
			net.minecraft.core.BlockPos eyePos = net.minecraft.core.BlockPos.containing(
				feet.x, client.player.getEyeY(), feet.z);
			int blockLight = client.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eyePos);
			int skyLight = client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eyePos);
			writeVec2iField(buf, "eyeBrightness", blockLight * 16, skyLight * 16);
			writeVec2iField(buf, "eyeBrightnessSmooth", blockLight * 16, skyLight * 16);
			// skyLight is 0-15, multiply by 16 to get lightmap coordinate (0-240), then /240 for 0.0-1.0
			writeFloatField(buf, "eyeBrightnessM", skyLight * 16.0f / 240.0f);
			writeFloatField(buf, "eyeBrightnessM2", skyLight * 16.0f / 240.0f);
		}

		// Atlas size
		writeVec2iField(buf, "atlasSize", 1024, 1024); // TODO: get actual atlas dimensions

		// Render stage
		writeIntField(buf, "renderStage", 1); // TERRAIN

		// Biome precipitation flags (Complementary custom uniforms)
		float isDry = 1.0f, isRainy = 0.0f, isSnowy = 0.0f;
		float isEyeInCave = 0.0f;
		if (client.level != null && client.getCameraEntity() != null) {
			net.minecraft.core.BlockPos camBlockPos = client.getCameraEntity().blockPosition();
			net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome =
				client.level.getBiome(camBlockPos);
			net.minecraft.world.level.biome.Biome.Precipitation precip =
				biome.value().getPrecipitationAt(camBlockPos);
			isDry = (precip == net.minecraft.world.level.biome.Biome.Precipitation.NONE) ? 1.0f : 0.0f;
			isRainy = (precip == net.minecraft.world.level.biome.Biome.Precipitation.RAIN) ? 1.0f : 0.0f;
			isSnowy = (precip == net.minecraft.world.level.biome.Biome.Precipitation.SNOW) ? 1.0f : 0.0f;
			// isEyeInCave: based on sky light at camera position (low sky light = underground)
			if (client.getCameraEntity().getEyeY() < 5.0) {
				int skyLight = client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, camBlockPos);
				isEyeInCave = 1.0f - (skyLight * 16.0f) / 240.0f;
			}
		}
		writeFloatField(buf, "isDry", isDry);
		writeFloatField(buf, "isRainy", isRainy);
		writeFloatField(buf, "isSnowy", isSnowy);
		writeFloatField(buf, "inDry", isDry);
		writeFloatField(buf, "inRainy", isRainy);
		writeFloatField(buf, "inSnowy", isSnowy);
		writeFloatField(buf, "isEyeInCave", isEyeInCave);
		writeFloatField(buf, "velocity", currentVelocity);
		// starter: In OG Iris, this is a SmoothedFloat that ramps 0→1 when the player
		// first moves. It gates TAA temporal accumulation — when starter < 1, TAA blending
		// is reduced, preventing ghost artifacts during initialization.
		// For now, keep at 0.0 to disable TAA temporal accumulation until we verify that
		// reprojection (gbufferPreviousProjection → current frame) works correctly.
		// Setting to 1.0 prematurely causes massive VL ray artifacts and diagonal line
		// artifacts from incorrect temporal accumulation.
		writeFloatField(buf, "starter", 0.0f);
		writeFloatField(buf, "frameTimeSmooth", frameTimeSec);

		// Nether biome flags (defaults)
		writeFloatField(buf, "inBasaltDeltas", 0.0f);
		writeFloatField(buf, "inCrimsonForest", 0.0f);
		writeFloatField(buf, "inNetherWastes", 0.0f);
		writeFloatField(buf, "inSoulValley", 0.0f);
		writeFloatField(buf, "inWarpedForest", 0.0f);
		writeFloatField(buf, "inPaleGarden", 0.0f);

		// Frame mod values (declared as float in UBO, not int)
		writeFloatField(buf, "framemod2", (float)(frameCount % 2));
		writeFloatField(buf, "framemod4", (float)(frameCount % 4));
		writeFloatField(buf, "framemod8", (float)(frameCount % 8));

		// Entity color and lightning (default)
		writeVec4Field(buf, "entityColor", 0.0f, 0.0f, 0.0f, 0.0f);
		writeVec4Field(buf, "lightningBoltPosition", 0.0f, -1000.0f, 0.0f, 0.0f);

		// previousCameraPosition is now written in the camera section above (before prevCam is overwritten)

		// Shadow matrices — read from ShadowRenderer's static fields (set during renderShadows())
		org.joml.Matrix4f shadowMV = net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW;
		org.joml.Matrix4f shadowProj = net.irisshaders.iris.shadows.ShadowRenderer.PROJECTION;
		if (shadowMV != null) {
			float[] smvArr = new float[16];
			shadowMV.get(smvArr);
			int off = buf.getFieldOffset("shadowModelView");
			if (off >= 0) buf.writeMat4f(off, smvArr);
			org.joml.Matrix4f smvInv = new org.joml.Matrix4f(shadowMV).invert();
			smvInv.get(smvArr);
			off = buf.getFieldOffset("shadowModelViewInverse");
			if (off >= 0) buf.writeMat4f(off, smvArr);
		}
		if (shadowProj != null) {
			org.joml.Matrix4f sp = new org.joml.Matrix4f(shadowProj);
			// ShadowMatrices.createOrthoMatrix() uses raw column values (NOT .ortho()),
			// so VulkanMod's Matrix4fM mixin does NOT affect it — it's already OpenGL-style.
			// Do NOT apply vulkanToOpenGLDepthRange() — that would double-convert.

			if (isShadowPass) {
				// Shadow pass: negate m11 to compensate for VulkanMod's negative viewport
				// Y-flip during shadow rendering. Without this, the shadow map would be Y-flipped.
				sp.m11(-sp.m11());
			}
			// Gbuffer pass: don't negate m11 — the terrain fragment shader samples the shadow
			// texture directly (no rasterizer Y-flip), so shadow UV Y must match the stored layout.

			float[] spArr = new float[16];
			sp.get(spArr);
			int off = buf.getFieldOffset("shadowProjection");
			if (off >= 0) buf.writeMat4f(off, spArr);
			org.joml.Matrix4f spInv = new org.joml.Matrix4f(sp).invert();
			spInv.get(spArr);
			off = buf.getFieldOffset("shadowProjectionInverse");
			if (off >= 0) buf.writeMat4f(off, spArr);
		}

		// Lightmap texture matrix: converts raw light coords (0-240) to UV space (0-1)
		// Minecraft stores lightmap as 16 levels × 16 = 0-240 range per axis.
		// The matrix scales by 1/256 and adds 1/32 offset for half-texel sampling.
		float lmScale = 1.0f / 256.0f;
		float lmOffset = 1.0f / 32.0f;
		float[] lightmapMatrix = new float[]{
			lmScale, 0, 0, 0,
			0, lmScale, 0, 0,
			0, 0, 1, 0,
			lmOffset, lmOffset, 0, 1
		};
		int lmOff = buf.getFieldOffset("iris_LightmapTextureMatrix");
		if (lmOff >= 0) buf.writeMat4f(lmOff, lightmapMatrix);

		// Fog — use Iris's captured fog color from MixinFogRenderer, which captures
		// at the correct time (FogRenderer.setupColor TAIL). RenderSystem.getShaderFogColor()
		// returns stale sky/horizon colors that cause an orange color cast.
		Vector3d capturedFog = CapturedRenderingState.INSTANCE.getFogColor();
		float fogR = (float) capturedFog.x;
		float fogG = (float) capturedFog.y;
		float fogB = (float) capturedFog.z;
		writeVec4Field(buf, "iris_FogColor", fogR, fogG, fogB, 1.0f);
		writeVec3Field(buf, "fogColor", fogR, fogG, fogB);
		writeFloatField(buf, "iris_FogStart", RenderSystem.getShaderFogStart());
		writeFloatField(buf, "iris_FogEnd", RenderSystem.getShaderFogEnd());
		writeFloatField(buf, "iris_FogDensity", CapturedRenderingState.INSTANCE.getFogDensity());
		writeIntField(buf, "heavyFog", 0);

		if (uniformLogCounter < 1 && !isShadowPass) {
			uniformLogCounter++;
		}

		// Copy uniform data to cutout and translucent buffers (only during gbuffer pass).
		// Their UBO layouts share the same field offsets for common uniforms,
		// with pass-specific fields (iris_currentAlphaTest) appended at the end.
		if (isShadowPass) return; // Shadow pass only needs the shadow UBO
		int solidSize = buf.getUsedSize();
		if (cutoutUniformBuffer != null) {
			int copySize = Math.min(solidSize, cutoutUniformBuffer.getUsedSize());
			org.lwjgl.system.MemoryUtil.memCopy(buf.getPointer(), cutoutUniformBuffer.getPointer(), copySize);
			// Cutout pass: alpha test threshold for leaf/glass cutout
			writeFloatField(cutoutUniformBuffer, "iris_currentAlphaTest", 0.1f);
		}
		if (translucentUniformBuffer != null) {
			int copySize = Math.min(solidSize, translucentUniformBuffer.getUsedSize());
			org.lwjgl.system.MemoryUtil.memCopy(buf.getPointer(), translucentUniformBuffer.getPointer(), copySize);
			// Translucent pass: no alpha test (water, stained glass render with any alpha)
			writeFloatField(translucentUniformBuffer, "iris_currentAlphaTest", 0.0f);
		}
	}

	// Helper methods to write uniforms by name (no-op if field doesn't exist)
	private void writeFloatField(IrisUniformBuffer buf, String name, float value) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) buf.writeFloat(off, value);
	}

	private void writeIntField(IrisUniformBuffer buf, String name, int value) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) buf.writeInt(off, value);
	}

	private void writeVec3Field(IrisUniformBuffer buf, String name, float x, float y, float z) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) buf.writeVec3f(off, x, y, z);
	}

	private void writeVec4Field(IrisUniformBuffer buf, String name, float x, float y, float z, float w) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) buf.writeVec4f(off, x, y, z, w);
	}

	private void writeVec3iField(IrisUniformBuffer buf, String name, int x, int y, int z) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) {
			buf.writeInt(off, x);
			buf.writeInt(off + 4, y);
			buf.writeInt(off + 8, z);
		}
	}

	private void writeVec2iField(IrisUniformBuffer buf, String name, int x, int y) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) {
			buf.writeInt(off, x);
			buf.writeInt(off + 4, y);
		}
	}

	@FunctionalInterface
	private interface OffsetConsumer { void accept(int offset); }

	private void writeFloat(IrisUniformBuffer buf, String name, OffsetConsumer writer) {
		int off = buf.getFieldOffset(name);
		if (off >= 0) writer.accept(off);
	}

	/**
	 * Compiles a textured terrain pipeline with block atlas sampling and vertex colors.
	 * Uses VulkanMod's standard MVP UBO mechanism + Sampler0 (block atlas).
	 * Position decoding matches VulkanMod's terrain.vsh exactly.
	 */
	private GraphicsPipeline compileMinimalTestPipeline(String name) {
		String minimalVert = """
			#version 460
			layout(location=0) in ivec4 a_PosId;
			layout(location=1) in vec4 a_Color;
			layout(location=2) in uvec2 a_TexCoord;
			layout(location=3) in ivec2 a_LightCoord;

			layout(push_constant) uniform PushConstants {
			    vec3 ChunkOffset;
			};

			layout(std140, binding=0) uniform MVPBlock {
			    mat4 MVP;
			};

			layout(location=0) out vec4 vertexColor;
			layout(location=1) out vec2 texCoord0;

			void main() {
			    const vec3 POSITION_INV = vec3(1.0 / 2048.0);
			    vec3 baseOffset = vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8));
			    vec4 pos = vec4(fma(vec3(a_PosId.xyz), POSITION_INV, ChunkOffset + baseOffset), 1.0);
			    gl_Position = MVP * pos;

			    vertexColor = a_Color;
			    texCoord0 = vec2(a_TexCoord) * (1.0 / 32768.0);
			}
			""";

		// Fallback fragment: solid GREEN output for pipeline testing
		String minimalFrag = """
			#version 460
			layout(binding=1) uniform sampler2D Sampler0;

			layout(location=0) in vec4 vertexColor;
			layout(location=1) in vec2 texCoord0;

			layout(location=0) out vec4 outColor;
			layout(location=1) out vec4 outNormal;
			layout(location=2) out vec4 outSpecular;

			void main() {
			    outColor = vec4(0.0, 1.0, 0.0, 1.0);
			    outNormal = vec4(0.5, 1.0, 0.9, 0.02);
			    outSpecular = vec4(0.0, 0.0, 0.0, 0.0);
			}
			""";

		Iris.logger.info("[IrisTerrainPipelineCompiler] Compiling TEXTURED TERRAIN SPIR-V for {}", name);
		ByteBuffer vertSpirv = IrisSPIRVCompiler.compilePreprocessed(name + "_tex.vsh", minimalVert, ShaderType.VERTEX);
		ByteBuffer fragSpirv = IrisSPIRVCompiler.compilePreprocessed(name + "_tex.fsh", minimalFrag, ShaderType.FRAGMENT);

		SPIRVUtils.SPIRV vertSPIRV = new SPIRVUtils.SPIRV(0, vertSpirv);
		SPIRVUtils.SPIRV fragSPIRV = new SPIRVUtils.SPIRV(0, fragSpirv);

		// UBO at binding 0: MVP matrix — standard VRenderSystem mechanism
		List<UBO> ubos = new ArrayList<>();
		List<ImageDescriptor> imageDescriptors = new ArrayList<>();

		net.vulkanmod.vulkan.shader.layout.Uniform.Info mvpInfo =
			net.vulkanmod.vulkan.shader.layout.Uniform.createUniformInfo("matrix4x4", "MVP", 16);
		UBO mvpUBO = new UBO(0, VK_SHADER_STAGE_ALL_GRAPHICS, 64, java.util.List.of(mvpInfo));
		ubos.add(mvpUBO);

		// Sampler0 at binding 1: block atlas texture (imageIdx=0 in VTextureSelector)
		imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "Sampler0", 0));

		Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.COMPRESSED_TERRAIN, name);
		builder.setUniforms(ubos, imageDescriptors);
		builder.setSPIRVs(vertSPIRV, fragSPIRV);

		// Push constants: ChunkOffset vec3
		AlignedStruct.Builder pcBuilder = new AlignedStruct.Builder();
		pcBuilder.addUniformInfo("float", "ChunkOffset", 3);
		PushConstants pushConstants = pcBuilder.buildPushConstant();
		builder.setPushConstants(pushConstants);

		GraphicsPipeline pipeline = builder.createGraphicsPipeline();
		Iris.logger.info("[IrisTerrainPipelineCompiler] Built TEXTURED TERRAIN pipeline {} (MVP UBO + Sampler0)", name);
		return pipeline;
	}

	public GraphicsPipeline getSolidPipeline() { return solidPipeline; }
	public GraphicsPipeline getCutoutPipeline() { return cutoutPipeline; }
	public GraphicsPipeline getTranslucentPipeline() { return translucentPipeline; }
	public GraphicsPipeline getShadowSolidPipeline() { return shadowSolidPipeline; }
	public GraphicsPipeline getShadowCutoutPipeline() { return shadowCutoutPipeline; }

	public void destroy() {
		// Avoid double-free when pipelines share the same instance
		Set<GraphicsPipeline> destroyed = new HashSet<>();
		if (solidPipeline != null && destroyed.add(solidPipeline)) {
			solidPipeline.cleanUp();
		}
		if (cutoutPipeline != null && destroyed.add(cutoutPipeline)) {
			cutoutPipeline.cleanUp();
		}
		if (translucentPipeline != null && destroyed.add(translucentPipeline)) {
			translucentPipeline.cleanUp();
		}
		if (shadowSolidPipeline != null && destroyed.add(shadowSolidPipeline)) {
			shadowSolidPipeline.cleanUp();
		}
		if (shadowCutoutPipeline != null && destroyed.add(shadowCutoutPipeline)) {
			shadowCutoutPipeline.cleanUp();
		}
		solidPipeline = null;
		cutoutPipeline = null;
		translucentPipeline = null;
		shadowSolidPipeline = null;
		shadowCutoutPipeline = null;

		if (solidUniformBuffer != null) {
			solidUniformBuffer.free();
			solidUniformBuffer = null;
		}
		if (cutoutUniformBuffer != null) {
			cutoutUniformBuffer.free();
			cutoutUniformBuffer = null;
		}
		if (translucentUniformBuffer != null) {
			translucentUniformBuffer.free();
			translucentUniformBuffer = null;
		}
		if (shadowUniformBuffer != null) {
			shadowUniformBuffer.free();
			shadowUniformBuffer = null;
		}
	}
}
