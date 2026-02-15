package net.irisshaders.iris.gl.program;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.FilledIndirectPointer;
import org.joml.Vector2f;
import org.joml.Vector3i;

/**
 * Compute shader program - Vulkan Port.
 *
 * In Vulkan, compute shaders use VkComputePipeline instead of VkGraphicsPipeline.
 * Dispatch is done via vkCmdDispatch/vkCmdDispatchIndirect.
 *
 * Full implementation in Phase 18 (Compute Shaders).
 */
public final class ComputeProgram extends GlResource {
	private final ProgramUniforms uniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;
	private final int[] localSize;
	private Vector3i absoluteWorkGroups;
	private Vector2f relativeWorkGroups;
	private float cachedWidth;
	private float cachedHeight;
	private Vector3i cachedWorkGroups;
	private FilledIndirectPointer indirectPointer;

	ComputeProgram(int program, ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images) {
		super(program);

		localSize = new int[]{1, 1, 1}; // Default local size; will be read from SPIR-V reflection in Phase 18
		this.uniforms = uniforms;
		this.samplers = samplers;
		this.images = images;
	}

	public static void unbind() {
		ProgramUniforms.clearActiveUniforms();
		// In Vulkan, compute pipeline unbinding is implicit
	}

	public void setWorkGroupInfo(Vector2f relativeWorkGroups, Vector3i absoluteWorkGroups, FilledIndirectPointer indirectPointer) {
		this.relativeWorkGroups = relativeWorkGroups;
		this.absoluteWorkGroups = absoluteWorkGroups;
		this.indirectPointer = indirectPointer;
	}

	public Vector3i getWorkGroups(float width, float height) {
		if (indirectPointer != null) return null;

		if (cachedWidth != width || cachedHeight != height || cachedWorkGroups == null) {
			this.cachedWidth = width;
			this.cachedHeight = height;
			if (this.absoluteWorkGroups != null) {
				this.cachedWorkGroups = this.absoluteWorkGroups;
			} else if (relativeWorkGroups != null) {
				this.cachedWorkGroups = new Vector3i(
					(int) Math.ceil(Math.ceil((width * relativeWorkGroups.x)) / localSize[0]),
					(int) Math.ceil(Math.ceil((height * relativeWorkGroups.y)) / localSize[1]),
					1);
			} else {
				this.cachedWorkGroups = new Vector3i(
					(int) Math.ceil(width / localSize[0]),
					(int) Math.ceil(height / localSize[1]),
					1);
			}
		}

		return cachedWorkGroups;
	}

	public void use() {
		// In Vulkan: vkCmdBindPipeline(COMPUTE, pipeline)
		uniforms.update();
		samplers.update();
		images.update();
	}

	public void dispatch(float width, float height) {
		if (!Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::allowConcurrentCompute).orElse(false)) {
			IrisRenderSystem.memoryBarrier(0); // Pipeline barrier
		}

		if (indirectPointer != null) {
			IrisRenderSystem.dispatchComputeIndirect(indirectPointer.offset());
		} else {
			IrisRenderSystem.dispatchCompute(getWorkGroups(width, height));
		}
	}

	public void destroyInternal() {
		// VkComputePipeline destruction in Phase 18
	}

	@Deprecated
	public int getProgramId() {
		return getGlId();
	}

	public int getActiveImages() {
		return images.getActiveImages();
	}
}
