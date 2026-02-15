package net.irisshaders.iris.vulkan.pipeline;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendMode;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Iris graphics pipeline wrapper for Vulkan.
 *
 * Bridges Iris's shader programs to VulkanMod's GraphicsPipeline system.
 * Each IrisGraphicsPipeline represents one shader program (vertex + fragment pair)
 * and caches multiple VkPipeline handles for different render state combinations.
 *
 * In VulkanMod's model:
 * - One GraphicsPipeline per shader pair
 * - Multiple VkPipeline handles per GraphicsPipeline (one per PipelineState)
 * - Pipeline cache shared across all pipelines for driver-level dedup
 *
 * Lifecycle:
 * 1. Created when a shader program is loaded from a shader pack
 * 2. Pipeline variants created lazily on first use with a given state
 * 3. Destroyed when shader pack is unloaded or reloaded
 *
 * Integration point: Phase 14 (ExtendedShader/Gbuffer programs) will create
 * IrisGraphicsPipeline instances and Phase 15 (Pipeline Orchestrator) will
 * bind them during rendering.
 */
public class IrisGraphicsPipeline {
	private final int programId;
	private final String programName;
	private final ByteBuffer vertexSpirvBytecode;
	private final ByteBuffer fragmentSpirvBytecode;

	// Cached VkPipeline handles keyed by state
	// Uses VulkanMod's GraphicsPipeline.getHandle(PipelineState) internally
	private final Map<PipelineStateKey, Long> pipelineCache = new ConcurrentHashMap<>();

	// Shader module handles (created once, reused for all pipeline variants)
	private long vertShaderModule = 0;
	private long fragShaderModule = 0;
	private boolean modulesCreated = false;

	// Default blend/depth state from shader pack directives
	private int defaultBlendState;
	private int defaultDepthState;
	private int defaultColorMask = 0xF; // All channels enabled

	public IrisGraphicsPipeline(int programId, String programName,
								ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv) {
		this.programId = programId;
		this.programName = programName;
		this.vertexSpirvBytecode = vertexSpirv;
		this.fragmentSpirvBytecode = fragmentSpirv;
	}

	/**
	 * Gets or creates a VkPipeline handle for the given render state.
	 * This is the hot path called every frame for each draw call.
	 *
	 * @param stateKey The complete pipeline state key
	 * @return VkPipeline handle, or 0 if pipeline creation failed
	 */
	public long getHandle(PipelineStateKey stateKey) {
		Long cached = pipelineCache.get(stateKey);
		if (cached != null) {
			return cached;
		}

		return createAndCachePipeline(stateKey);
	}

	/**
	 * Creates a new VkPipeline for the given state and caches it.
	 * Uses VulkanMod's GraphicsPipeline under the hood.
	 * Full implementation in Phase 14 when we integrate with VulkanMod's Pipeline.Builder.
	 */
	private synchronized long createAndCachePipeline(PipelineStateKey stateKey) {
		// Double-check after acquiring lock
		Long cached = pipelineCache.get(stateKey);
		if (cached != null) {
			return cached;
		}

		try {
			// Phase 14 will implement:
			// 1. Ensure shader modules are created from SPIR-V bytecode
			// 2. Build VkGraphicsPipelineCreateInfo with state from stateKey
			// 3. Create VkPipeline via VulkanMod's GraphicsPipeline
			// 4. Cache and return handle

			if (!modulesCreated) {
				createShaderModules();
			}

			// Placeholder: return 0 until Phase 14 implements full pipeline creation
			long handle = 0;
			pipelineCache.put(stateKey, handle);
			return handle;

		} catch (Exception e) {
			Iris.logger.error("Failed to create Vulkan pipeline for program '{}': {}", programName, e.getMessage());
			return 0;
		}
	}

	/**
	 * Creates VkShaderModule handles from SPIR-V bytecode.
	 * Uses VulkanMod's Pipeline.createShaderModule(ByteBuffer).
	 * Full implementation in Phase 14.
	 */
	private void createShaderModules() {
		if (modulesCreated) return;

		try {
			// Phase 14 will call:
			// vertShaderModule = Pipeline.createShaderModule(vertexSpirvBytecode);
			// fragShaderModule = Pipeline.createShaderModule(fragmentSpirvBytecode);
			modulesCreated = true;
			Iris.logger.debug("Created shader modules for program '{}'", programName);
		} catch (Exception e) {
			Iris.logger.error("Failed to create shader modules for '{}': {}", programName, e.getMessage());
		}
	}

	/**
	 * Sets default blend state from shader pack directives.
	 */
	public void setDefaultBlendState(BlendMode blendMode) {
		if (blendMode != null) {
			this.defaultBlendState = PipelineStateKey.encodeBlendState(
				true, blendMode.srcRgb(), blendMode.dstRgb(),
				blendMode.srcAlpha(), blendMode.dstAlpha());
		} else {
			this.defaultBlendState = 0; // Blending disabled
		}
	}

	/**
	 * Sets default depth state from shader pack directives.
	 */
	public void setDefaultDepthState(boolean testEnabled, boolean writeEnabled, int compareFunction) {
		this.defaultDepthState = PipelineStateKey.encodeDepthState(testEnabled, writeEnabled, compareFunction);
	}

	/**
	 * Sets default color write mask.
	 */
	public void setDefaultColorMask(boolean r, boolean g, boolean b, boolean a) {
		this.defaultColorMask = PipelineStateKey.encodeColorMask(r, g, b, a);
	}

	public int getDefaultBlendState() {
		return defaultBlendState;
	}

	public int getDefaultDepthState() {
		return defaultDepthState;
	}

	public int getDefaultColorMask() {
		return defaultColorMask;
	}

	public int getProgramId() {
		return programId;
	}

	public String getProgramName() {
		return programName;
	}

	public int getCachedPipelineCount() {
		return pipelineCache.size();
	}

	/**
	 * Destroys all cached pipelines and shader modules.
	 * Called when shader pack is unloaded or reloaded.
	 */
	public void destroy() {
		// Phase 14 will implement:
		// for (long handle : pipelineCache.values()) {
		//     vkDestroyPipeline(device, handle, null);
		// }
		pipelineCache.clear();

		// if (vertShaderModule != 0) vkDestroyShaderModule(device, vertShaderModule, null);
		// if (fragShaderModule != 0) vkDestroyShaderModule(device, fragShaderModule, null);
		vertShaderModule = 0;
		fragShaderModule = 0;
		modulesCreated = false;

		Iris.logger.debug("Destroyed pipeline '{}' ({} cached variants)", programName, pipelineCache.size());
	}
}
