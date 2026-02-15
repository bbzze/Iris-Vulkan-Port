package net.irisshaders.iris.vulkan.pipeline;

import net.irisshaders.iris.Iris;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pipeline manager for Iris Vulkan port.
 *
 * Manages the lifecycle of all IrisGraphicsPipeline instances.
 * Provides centralized pipeline creation, caching, lookup, and destruction.
 *
 * Pipeline creation flow:
 * 1. Shader pack loads -> TransformPatcher transforms GLSL -> GLSL 460
 * 2. IrisSPIRVCompiler compiles GLSL 460 -> SPIR-V bytecode
 * 3. IrisPipelineManager.createPipeline() creates IrisGraphicsPipeline from SPIR-V
 * 4. During rendering, pipeline.getHandle(stateKey) returns cached VkPipeline
 *
 * Integrates with VulkanMod's pipeline cache for driver-level deduplication.
 *
 * Thread safety: All public methods are thread-safe via ConcurrentHashMap.
 */
public class IrisPipelineManager {
	private static IrisPipelineManager instance;

	// All active pipelines, keyed by program ID
	private final Map<Integer, IrisGraphicsPipeline> pipelines = new ConcurrentHashMap<>();

	// Statistics for debugging
	private int totalPipelinesCreated = 0;
	private int totalPipelineVariants = 0;

	private IrisPipelineManager() {
	}

	public static IrisPipelineManager getInstance() {
		if (instance == null) {
			instance = new IrisPipelineManager();
		}
		return instance;
	}

	/**
	 * Creates a new IrisGraphicsPipeline from compiled SPIR-V bytecode.
	 *
	 * @param programId  Unique program identifier
	 * @param name       Human-readable program name (e.g. "gbuffers_terrain")
	 * @param vertSpirv  Vertex shader SPIR-V bytecode
	 * @param fragSpirv  Fragment shader SPIR-V bytecode
	 * @return The created pipeline
	 */
	public IrisGraphicsPipeline createPipeline(int programId, String name,
											   ByteBuffer vertSpirv, ByteBuffer fragSpirv) {
		// Destroy existing pipeline with same ID (shader reload)
		IrisGraphicsPipeline existing = pipelines.get(programId);
		if (existing != null) {
			existing.destroy();
		}

		IrisGraphicsPipeline pipeline = new IrisGraphicsPipeline(programId, name, vertSpirv, fragSpirv);
		pipelines.put(programId, pipeline);
		totalPipelinesCreated++;

		Iris.logger.debug("Created pipeline '{}' (id={}), total active: {}",
			name, programId, pipelines.size());

		return pipeline;
	}

	/**
	 * Gets an existing pipeline by program ID.
	 *
	 * @return The pipeline, or null if not found
	 */
	public IrisGraphicsPipeline getPipeline(int programId) {
		return pipelines.get(programId);
	}

	/**
	 * Destroys a specific pipeline.
	 */
	public void destroyPipeline(int programId) {
		IrisGraphicsPipeline pipeline = pipelines.remove(programId);
		if (pipeline != null) {
			pipeline.destroy();
		}
	}

	/**
	 * Destroys all pipelines. Called on shader pack unload/reload.
	 */
	public void destroyAll() {
		int count = pipelines.size();
		int variants = 0;

		for (IrisGraphicsPipeline pipeline : pipelines.values()) {
			variants += pipeline.getCachedPipelineCount();
			pipeline.destroy();
		}
		pipelines.clear();

		Iris.logger.info("Destroyed {} Iris pipelines ({} cached variants)", count, variants);
	}

	/**
	 * Gets the VkPipeline handle for a program with given render state.
	 * Convenience method that combines lookup + state resolution.
	 *
	 * @param programId The program to use
	 * @param stateKey  The current render state
	 * @return VkPipeline handle, or 0 if program not found
	 */
	public long getPipelineHandle(int programId, PipelineStateKey stateKey) {
		IrisGraphicsPipeline pipeline = pipelines.get(programId);
		if (pipeline == null) {
			return 0;
		}
		return pipeline.getHandle(stateKey);
	}

	// ==================== Statistics ====================

	public int getActivePipelineCount() {
		return pipelines.size();
	}

	public int getTotalCachedVariants() {
		int total = 0;
		for (IrisGraphicsPipeline pipeline : pipelines.values()) {
			total += pipeline.getCachedPipelineCount();
		}
		return total;
	}

	public int getTotalPipelinesCreated() {
		return totalPipelinesCreated;
	}

	/**
	 * Resets the singleton. Used for testing or complete mod reload.
	 */
	public static void reset() {
		if (instance != null) {
			instance.destroyAll();
			instance = null;
		}
	}
}
