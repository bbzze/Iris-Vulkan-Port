package net.irisshaders.iris.vulkan.uniform;

import net.irisshaders.iris.gl.uniform.UniformType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * std140 layout calculator for Iris uniform buffers.
 *
 * In Vulkan, uniforms must be packed into UBOs following std140 layout rules.
 * This class calculates byte offsets for each uniform field to ensure correct
 * GPU-side alignment.
 *
 * std140 alignment rules:
 * - float:  4 bytes, aligned to 4
 * - int:    4 bytes, aligned to 4
 * - bool:   4 bytes, aligned to 4 (stored as int)
 * - vec2:   8 bytes, aligned to 8
 * - vec3:  12 bytes, aligned to 16 (!)
 * - vec4:  16 bytes, aligned to 16
 * - ivec2:  8 bytes, aligned to 8
 * - ivec3: 12 bytes, aligned to 16 (!)
 * - ivec4: 16 bytes, aligned to 16
 * - mat3:  48 bytes (3 x vec4 padded), aligned to 16
 * - mat4:  64 bytes, aligned to 16
 * - arrays: each element rounded up to vec4 alignment (16 bytes)
 *
 * The total buffer size is always rounded up to a multiple of 16 bytes
 * (the largest base alignment in std140).
 */
public class UniformLayout {
	private final Map<String, FieldInfo> fields = new LinkedHashMap<>();
	private int currentOffset = 0;
	private int totalSize = 0;

	/**
	 * Adds a uniform field to the layout.
	 *
	 * @param name The uniform name (must match shader declaration)
	 * @param type The Iris uniform type
	 * @return The byte offset of this field in the UBO
	 */
	public int addField(String name, UniformType type) {
		int alignment = getAlignment(type);
		int size = getSize(type);

		// Align current offset
		currentOffset = alignUp(currentOffset, alignment);

		FieldInfo info = new FieldInfo(name, type, currentOffset, size);
		fields.put(name, info);

		int offset = currentOffset;
		currentOffset += size;

		// Update total size (rounded to 16-byte boundary)
		totalSize = alignUp(currentOffset, 16);

		return offset;
	}

	/**
	 * Gets the byte offset for a named uniform.
	 *
	 * @return The offset, or -1 if the uniform is not in the layout
	 */
	public int getOffset(String name) {
		FieldInfo info = fields.get(name);
		return info != null ? info.offset : -1;
	}

	/**
	 * Gets the total buffer size in bytes (aligned to 16 bytes).
	 */
	public int getTotalSize() {
		return totalSize;
	}

	/**
	 * Gets all field info entries in declaration order.
	 */
	public List<FieldInfo> getFields() {
		return new ArrayList<>(fields.values());
	}

	/**
	 * Gets the std140 alignment for a given uniform type.
	 */
	public static int getAlignment(UniformType type) {
		return switch (type) {
			case FLOAT -> 4;
			case INT -> 4;
			case VEC2 -> 8;
			case VEC2I -> 8;
			case VEC3 -> 16;    // vec3 aligns to vec4!
			case VEC3I -> 16;   // ivec3 aligns to ivec4!
			case VEC4 -> 16;
			case VEC4I -> 16;
			case MAT3 -> 16;    // Column vectors align to vec4
			case MAT4 -> 16;
		};
	}

	/**
	 * Gets the std140 size in bytes for a given uniform type.
	 */
	public static int getSize(UniformType type) {
		return switch (type) {
			case FLOAT -> 4;
			case INT -> 4;
			case VEC2 -> 8;
			case VEC2I -> 8;
			case VEC3 -> 12;    // 3 floats, but padded to 16 by alignment
			case VEC3I -> 12;   // 3 ints, but padded to 16 by alignment
			case VEC4 -> 16;
			case VEC4I -> 16;
			case MAT3 -> 48;    // 3 columns x 16 bytes each (vec3 padded to vec4)
			case MAT4 -> 64;    // 4 columns x 16 bytes each
		};
	}

	private static int alignUp(int offset, int alignment) {
		return (offset + alignment - 1) & ~(alignment - 1);
	}

	/**
	 * Information about a single field in the UBO layout.
	 */
	public record FieldInfo(String name, UniformType type, int offset, int size) {
	}
}
