package net.irisshaders.iris.gl.uniform;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages a native memory buffer for Iris shader uniform data (std140 layout).
 * Each ExtendedShader creates one of these to hold all non-opaque uniform values.
 * The buffer is uploaded to the GPU via VulkanMod's ManualUBO mechanism.
 *
 * Phase 7: Uniform System Adaptation - Vulkan Port.
 */
public class IrisUniformBuffer {
	private final long bufferPtr;
	private final int bufferSize;
	private final Map<String, FieldInfo> fields;
	private final int usedSize;

	public static class FieldInfo {
		public final String name;
		public final String type;
		public final int byteOffset;
		public final int byteSize;

		FieldInfo(String name, String type, int byteOffset, int byteSize) {
			this.name = name;
			this.type = type;
			this.byteOffset = byteOffset;
			this.byteSize = byteSize;
		}
	}

	public IrisUniformBuffer(Map<String, FieldInfo> fields, int usedSize) {
		this.bufferSize = Math.max(usedSize, 16);
		this.bufferPtr = MemoryUtil.nmemCalloc(1, bufferSize);
		this.fields = fields;
		this.usedSize = usedSize;
	}

	public long getPointer() { return bufferPtr; }
	public int getSize() { return bufferSize; }
	public int getUsedSize() { return usedSize; }
	public Map<String, FieldInfo> getFields() { return fields; }

	public int getFieldOffset(String name) {
		FieldInfo info = fields.get(name);
		return info != null ? info.byteOffset : -1;
	}

	// ==================== Read Methods ====================

	public float readFloat(int byteOffset) {
		if (byteOffset >= 0 && byteOffset + 4 <= bufferSize)
			return MemoryUtil.memGetFloat(bufferPtr + byteOffset);
		return 0.0f;
	}

	public int readInt(int byteOffset) {
		if (byteOffset >= 0 && byteOffset + 4 <= bufferSize)
			return MemoryUtil.memGetInt(bufferPtr + byteOffset);
		return 0;
	}

	// ==================== Write Methods ====================

	public void writeFloat(int byteOffset, float value) {
		if (byteOffset >= 0 && byteOffset + 4 <= bufferSize)
			MemoryUtil.memPutFloat(bufferPtr + byteOffset, value);
	}

	public void writeInt(int byteOffset, int value) {
		if (byteOffset >= 0 && byteOffset + 4 <= bufferSize)
			MemoryUtil.memPutInt(bufferPtr + byteOffset, value);
	}

	public void writeVec2f(int byteOffset, float x, float y) {
		if (byteOffset >= 0 && byteOffset + 8 <= bufferSize) {
			MemoryUtil.memPutFloat(bufferPtr + byteOffset, x);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 4, y);
		}
	}

	public void writeVec2i(int byteOffset, int x, int y) {
		if (byteOffset >= 0 && byteOffset + 8 <= bufferSize) {
			MemoryUtil.memPutInt(bufferPtr + byteOffset, x);
			MemoryUtil.memPutInt(bufferPtr + byteOffset + 4, y);
		}
	}

	public void writeVec3f(int byteOffset, float x, float y, float z) {
		if (byteOffset >= 0 && byteOffset + 12 <= bufferSize) {
			MemoryUtil.memPutFloat(bufferPtr + byteOffset, x);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 4, y);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 8, z);
		}
	}

	public void writeVec3i(int byteOffset, int x, int y, int z) {
		if (byteOffset >= 0 && byteOffset + 12 <= bufferSize) {
			MemoryUtil.memPutInt(bufferPtr + byteOffset, x);
			MemoryUtil.memPutInt(bufferPtr + byteOffset + 4, y);
			MemoryUtil.memPutInt(bufferPtr + byteOffset + 8, z);
		}
	}

	public void writeVec4f(int byteOffset, float x, float y, float z, float w) {
		if (byteOffset >= 0 && byteOffset + 16 <= bufferSize) {
			MemoryUtil.memPutFloat(bufferPtr + byteOffset, x);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 4, y);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 8, z);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 12, w);
		}
	}

	public void writeVec4i(int byteOffset, int x, int y, int z, int w) {
		if (byteOffset >= 0 && byteOffset + 16 <= bufferSize) {
			MemoryUtil.memPutInt(bufferPtr + byteOffset, x);
			MemoryUtil.memPutInt(bufferPtr + byteOffset + 4, y);
			MemoryUtil.memPutInt(bufferPtr + byteOffset + 8, z);
			MemoryUtil.memPutInt(bufferPtr + byteOffset + 12, w);
		}
	}

	public void writeMat4f(int byteOffset, FloatBuffer matrix) {
		if (byteOffset >= 0 && byteOffset + 64 <= bufferSize) {
			int pos = matrix.position();
			for (int i = 0; i < 16; i++) {
				MemoryUtil.memPutFloat(bufferPtr + byteOffset + i * 4, matrix.get(pos + i));
			}
		}
	}

	public void writeMat4f(int byteOffset, float[] matrix) {
		if (byteOffset >= 0 && byteOffset + 64 <= bufferSize) {
			for (int i = 0; i < Math.min(16, matrix.length); i++) {
				MemoryUtil.memPutFloat(bufferPtr + byteOffset + i * 4, matrix[i]);
			}
		}
	}

	/**
	 * Writes a mat3 in std140 layout: each column is padded to vec4 (16 bytes).
	 * Total: 3 columns * 16 bytes = 48 bytes.
	 */
	public void writeMat3f(int byteOffset, float[] matrix) {
		if (byteOffset >= 0 && byteOffset + 48 <= bufferSize && matrix.length >= 9) {
			// Column 0 (3 floats + 4 bytes padding)
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 0, matrix[0]);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 4, matrix[1]);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 8, matrix[2]);
			// Column 1
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 16, matrix[3]);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 20, matrix[4]);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 24, matrix[5]);
			// Column 2
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 32, matrix[6]);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 36, matrix[7]);
			MemoryUtil.memPutFloat(bufferPtr + byteOffset + 40, matrix[8]);
		}
	}

	public void free() {
		MemoryUtil.nmemFree(bufferPtr);
	}

	// ==================== Static Factory ====================

	private static final Pattern FIELD_PATTERN = Pattern.compile(
		"^\\s*(\\w+)\\s+(\\w+)(\\s*\\[[^]]*\\])?\\s*;");

	/**
	 * Parses the IrisUniforms UBO block from preprocessed Vulkan GLSL source
	 * and computes the std140 layout.
	 */
	public static IrisUniformBuffer fromVulkanGLSL(String vulkanSource) {
		Map<String, FieldInfo> fields = new LinkedHashMap<>();
		int currentOffset = 0;

		String[] lines = vulkanSource.split("\n");
		boolean inBlock = false;

		for (String line : lines) {
			String trimmed = line.trim();

			if (trimmed.contains("uniform IrisUniforms {")) {
				inBlock = true;
				continue;
			}

			if (inBlock && trimmed.equals("};")) {
				break;
			}

			if (inBlock) {
				Matcher m = FIELD_PATTERN.matcher(trimmed);
				if (m.find()) {
					String type = m.group(1);
					String name = m.group(2);
					String arrayPart = m.group(3);

					int arrayCount = 1;
					if (arrayPart != null && !arrayPart.isEmpty()) {
						try {
							arrayCount = Integer.parseInt(arrayPart.replaceAll("[\\[\\]\\s]", ""));
						} catch (NumberFormatException e) {
							arrayCount = 1;
						}
					}

					Std140Info info = getStd140Info(type);

					if (arrayCount <= 1) {
						// Non-array: align, record, advance
						currentOffset = alignTo(currentOffset, info.alignment);
						fields.put(name, new FieldInfo(name, type, currentOffset, info.size));
						currentOffset += info.size;
					} else {
						// Array: each element aligned to vec4 (16 bytes) in std140
						int elementAlignment = Math.max(info.alignment, 16);
						int elementSize = alignTo(info.size, 16);

						for (int a = 0; a < arrayCount; a++) {
							currentOffset = alignTo(currentOffset, elementAlignment);
							String fieldName = name + "[" + a + "]";
							fields.put(fieldName, new FieldInfo(fieldName, type, currentOffset, info.size));

							if (a == 0) {
								// Also map base name to first element
								fields.put(name, new FieldInfo(name, type, currentOffset, info.size));
							}
							currentOffset += elementSize;
						}
					}
				}
			}
		}

		// Align total size to 16 bytes (UBO alignment)
		currentOffset = alignTo(currentOffset, 16);

		return new IrisUniformBuffer(fields, currentOffset);
	}

	private static int alignTo(int offset, int alignment) {
		return (offset + alignment - 1) & ~(alignment - 1);
	}

	private static Std140Info getStd140Info(String type) {
		return switch (type) {
			case "float" -> new Std140Info(4, 4);
			case "int", "uint", "bool" -> new Std140Info(4, 4);
			case "vec2" -> new Std140Info(8, 8);
			case "ivec2", "uvec2", "bvec2" -> new Std140Info(8, 8);
			case "vec3" -> new Std140Info(16, 12);
			case "ivec3", "uvec3", "bvec3" -> new Std140Info(16, 12);
			case "vec4" -> new Std140Info(16, 16);
			case "ivec4", "uvec4", "bvec4" -> new Std140Info(16, 16);
			case "mat3" -> new Std140Info(16, 48); // 3 columns of vec4-padded vec3
			case "mat4" -> new Std140Info(16, 64); // 4 columns of vec4
			default -> new Std140Info(4, 4);
		};
	}

	private record Std140Info(int alignment, int size) {}
}
