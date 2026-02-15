package net.irisshaders.iris.vulkan.uniform;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.uniform.UniformType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Iris uniform buffer for Vulkan.
 *
 * Manages a CPU-side ByteBuffer that mirrors the GPU-side UBO.
 * Uniform values are written to this buffer using std140 layout,
 * then the entire buffer is uploaded to the GPU once per frame.
 *
 * In VulkanMod, UBO upload goes through:
 * - VulkanMod's UBO class for descriptor-set-bound uniform buffers
 * - Or push constants for frequently-updated small data (128 bytes max)
 *
 * Lifecycle:
 * 1. Created when shader pack is loaded (Phase 7)
 * 2. Layout computed from shader program's active uniforms
 * 3. Updated every frame with new uniform values
 * 4. Uploaded to GPU via VulkanMod's UniformBuffer
 * 5. Destroyed when shader pack is unloaded
 *
 * Integration point: Each IrisGraphicsPipeline (Phase 6) will have an
 * associated IrisUniformBuffer for its per-program uniforms.
 */
public class IrisUniformBuffer {
	private final String name;
	private final UniformLayout layout;
	private final ByteBuffer cpuBuffer;
	private boolean dirty = true;

	// GPU-side buffer handle (managed by VulkanMod's memory system)
	// Phase 15 will set this when integrating with the pipeline orchestrator
	private long gpuBufferHandle = 0;

	public IrisUniformBuffer(String name, UniformLayout layout) {
		this.name = name;
		this.layout = layout;
		this.cpuBuffer = ByteBuffer.allocateDirect(layout.getTotalSize())
			.order(ByteOrder.nativeOrder());

		Iris.logger.debug("Created uniform buffer '{}': {} bytes, {} fields",
			name, layout.getTotalSize(), layout.getFields().size());
	}

	// ==================== Uniform Write Methods ====================
	// These write to the CPU-side buffer at the correct std140 offset.

	public void writeFloat(String uniformName, float value) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0) {
			cpuBuffer.putFloat(offset, value);
			dirty = true;
		}
	}

	public void writeInt(String uniformName, int value) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0) {
			cpuBuffer.putInt(offset, value);
			dirty = true;
		}
	}

	public void writeVec2(String uniformName, float x, float y) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0) {
			cpuBuffer.putFloat(offset, x);
			cpuBuffer.putFloat(offset + 4, y);
			dirty = true;
		}
	}

	public void writeVec2i(String uniformName, int x, int y) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0) {
			cpuBuffer.putInt(offset, x);
			cpuBuffer.putInt(offset + 4, y);
			dirty = true;
		}
	}

	public void writeVec3(String uniformName, float x, float y, float z) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0) {
			cpuBuffer.putFloat(offset, x);
			cpuBuffer.putFloat(offset + 4, y);
			cpuBuffer.putFloat(offset + 8, z);
			dirty = true;
		}
	}

	public void writeVec3i(String uniformName, int x, int y, int z) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0) {
			cpuBuffer.putInt(offset, x);
			cpuBuffer.putInt(offset + 4, y);
			cpuBuffer.putInt(offset + 8, z);
			dirty = true;
		}
	}

	public void writeVec4(String uniformName, float x, float y, float z, float w) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0) {
			cpuBuffer.putFloat(offset, x);
			cpuBuffer.putFloat(offset + 4, y);
			cpuBuffer.putFloat(offset + 8, z);
			cpuBuffer.putFloat(offset + 12, w);
			dirty = true;
		}
	}

	public void writeVec4i(String uniformName, int x, int y, int z, int w) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0) {
			cpuBuffer.putInt(offset, x);
			cpuBuffer.putInt(offset + 4, y);
			cpuBuffer.putInt(offset + 8, z);
			cpuBuffer.putInt(offset + 12, w);
			dirty = true;
		}
	}

	public void writeMat4(String uniformName, float[] matrix) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0 && matrix.length >= 16) {
			for (int i = 0; i < 16; i++) {
				cpuBuffer.putFloat(offset + i * 4, matrix[i]);
			}
			dirty = true;
		}
	}

	public void writeMat3(String uniformName, float[] matrix) {
		int offset = layout.getOffset(uniformName);
		if (offset >= 0 && matrix.length >= 9) {
			// std140: mat3 is stored as 3 vec4 columns (each padded to 16 bytes)
			for (int col = 0; col < 3; col++) {
				cpuBuffer.putFloat(offset + col * 16, matrix[col * 3]);
				cpuBuffer.putFloat(offset + col * 16 + 4, matrix[col * 3 + 1]);
				cpuBuffer.putFloat(offset + col * 16 + 8, matrix[col * 3 + 2]);
				// 4th float in each column is padding (leave as 0)
			}
			dirty = true;
		}
	}

	// ==================== Buffer Management ====================

	/**
	 * Gets the CPU-side buffer for GPU upload.
	 * The buffer position is set to 0 and limit to totalSize.
	 */
	public ByteBuffer getBuffer() {
		cpuBuffer.position(0).limit(layout.getTotalSize());
		return cpuBuffer;
	}

	/**
	 * Returns true if any uniform has been written since the last upload.
	 */
	public boolean isDirty() {
		return dirty;
	}

	/**
	 * Marks the buffer as clean (called after GPU upload).
	 */
	public void markClean() {
		dirty = false;
	}

	public String getName() {
		return name;
	}

	public UniformLayout getLayout() {
		return layout;
	}

	public int getBufferSize() {
		return layout.getTotalSize();
	}

	public long getGpuBufferHandle() {
		return gpuBufferHandle;
	}

	public void setGpuBufferHandle(long handle) {
		this.gpuBufferHandle = handle;
	}

	/**
	 * Destroys the buffer. Called when shader pack is unloaded.
	 */
	public void destroy() {
		// Phase 15 will handle GPU-side buffer deallocation
		gpuBufferHandle = 0;
		Iris.logger.debug("Destroyed uniform buffer '{}'", name);
	}

	// ==================== Builder ====================

	/**
	 * Creates a uniform buffer from a list of uniform names and types.
	 */
	public static IrisUniformBuffer create(String name, List<UniformEntry> entries) {
		UniformLayout layout = new UniformLayout();
		for (UniformEntry entry : entries) {
			layout.addField(entry.name(), entry.type());
		}
		return new IrisUniformBuffer(name, layout);
	}

	/**
	 * An entry describing a uniform to include in the buffer.
	 */
	public record UniformEntry(String name, UniformType type) {
	}
}
