package net.irisshaders.iris.vulkan.pipeline;

import java.util.Objects;

/**
 * Pipeline state key for Vulkan pipeline deduplication.
 *
 * In Vulkan, ALL render state is baked into the graphics pipeline object.
 * This key captures the full set of state that varies between draw calls
 * in Iris's shader pipeline, enabling efficient pipeline caching.
 *
 * Maps to VulkanMod's PipelineState bit-packed representation:
 * - assemblyRasterState: topology + polygon mode + cull mode
 * - blendState: src/dst RGB/Alpha blend factors + blend op + enable
 * - depthState: depth test/write enable + compare function
 * - colorMask: per-channel write mask
 *
 * The shaderProgramId distinguishes pipelines using different shader programs
 * (each gbuffer/composite/final shader is a different program).
 */
public final class PipelineStateKey {
	private final int shaderProgramId;
	private final int blendState;
	private final int depthState;
	private final int colorMask;
	private final int assemblyRasterState;
	private final long renderPassHandle;
	private final int hash;

	public PipelineStateKey(int shaderProgramId, int blendState, int depthState,
							int colorMask, int assemblyRasterState, long renderPassHandle) {
		this.shaderProgramId = shaderProgramId;
		this.blendState = blendState;
		this.depthState = depthState;
		this.colorMask = colorMask;
		this.assemblyRasterState = assemblyRasterState;
		this.renderPassHandle = renderPassHandle;
		this.hash = computeHash();
	}

	// ==================== Blend State Encoding ====================
	// Matches VulkanMod PipelineState.BlendState bit layout:
	// Bits 0-4:   srcRgb blend factor (5-bit)
	// Bits 5-9:   dstRgb blend factor (5-bit)
	// Bits 10-14: srcAlpha blend factor (5-bit)
	// Bits 15-19: dstAlpha blend factor (5-bit)
	// Bits 20-23: blend op (4-bit)
	// Bit 24:     blend enable

	/**
	 * Encodes blend state from Iris blend mode parameters (GL enum values).
	 * GL blend factor values are converted to the 5-bit VulkanMod encoding.
	 */
	public static int encodeBlendState(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
		if (!enabled) {
			return 0; // Disabled: all zeros
		}
		int state = encodeBlendFactor(srcRgb);
		state |= encodeBlendFactor(dstRgb) << 5;
		state |= encodeBlendFactor(srcAlpha) << 10;
		state |= encodeBlendFactor(dstAlpha) << 15;
		state |= 0 << 20; // VK_BLEND_OP_ADD = 0 (default)
		state |= 1 << 24; // Enable bit
		return state;
	}

	/**
	 * Converts GL blend factor constant to VulkanMod's 5-bit encoding.
	 * Based on PipelineState.BlendState.encodeBlendFactor().
	 */
	private static int encodeBlendFactor(int glFactor) {
		return switch (glFactor) {
			case 0 -> 0;      // GL_ZERO -> VK_BLEND_FACTOR_ZERO
			case 1 -> 1;      // GL_ONE -> VK_BLEND_FACTOR_ONE
			case 0x0300 -> 2;  // GL_SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR
			case 0x0301 -> 3;  // GL_ONE_MINUS_SRC_COLOR
			case 0x0302 -> 4;  // GL_SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA
			case 0x0303 -> 5;  // GL_ONE_MINUS_SRC_ALPHA
			case 0x0304 -> 6;  // GL_DST_ALPHA
			case 0x0305 -> 7;  // GL_ONE_MINUS_DST_ALPHA
			case 0x0306 -> 8;  // GL_DST_COLOR
			case 0x0307 -> 9;  // GL_ONE_MINUS_DST_COLOR
			case 0x0308 -> 10; // GL_SRC_ALPHA_SATURATE
			default -> 0;      // Unknown -> ZERO
		};
	}

	// ==================== Depth State Encoding ====================
	// Matches VulkanMod PipelineState.DepthState bit layout:
	// Bit 0:    depth test enable
	// Bit 1:    depth write enable
	// Bits 2-5: compare function (4-bit)

	/**
	 * Encodes depth state from Iris depth parameters.
	 */
	public static int encodeDepthState(boolean testEnabled, boolean writeEnabled, int compareFunction) {
		int state = testEnabled ? 1 : 0;
		state |= (writeEnabled ? 1 : 0) << 1;
		state |= encodeDepthFunc(compareFunction) << 2;
		return state;
	}

	/**
	 * Converts GL depth function constant to VulkanMod's 4-bit encoding.
	 */
	private static int encodeDepthFunc(int glFunc) {
		return switch (glFunc) {
			case 0x0200 -> 0;  // GL_NEVER -> VK_COMPARE_OP_NEVER
			case 0x0201 -> 1;  // GL_LESS -> VK_COMPARE_OP_LESS
			case 0x0202 -> 2;  // GL_EQUAL -> VK_COMPARE_OP_EQUAL
			case 0x0203 -> 3;  // GL_LEQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL
			case 0x0204 -> 4;  // GL_GREATER -> VK_COMPARE_OP_GREATER
			case 0x0205 -> 5;  // GL_NOTEQUAL -> VK_COMPARE_OP_NOT_EQUAL
			case 0x0206 -> 6;  // GL_GEQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL
			case 0x0207 -> 7;  // GL_ALWAYS -> VK_COMPARE_OP_ALWAYS
			default -> 3;      // Default: LEQUAL
		};
	}

	// ==================== Color Mask Encoding ====================
	// 4-bit mask: bit0=R, bit1=G, bit2=B, bit3=A

	public static int encodeColorMask(boolean r, boolean g, boolean b, boolean a) {
		int mask = 0;
		if (r) mask |= 1;
		if (g) mask |= 2;
		if (b) mask |= 4;
		if (a) mask |= 8;
		return mask;
	}

	// ==================== Assembly/Raster State Encoding ====================
	// Bits 0-2:  polygon mode (FILL=0, LINE=1, POINT=2)
	// Bits 3-7:  topology (TRIANGLE_LIST=3, TRIANGLE_STRIP=4, etc.)
	// Bits 8-9:  cull mode (NONE=0, BACK=1, FRONT=2)

	public static int encodeAssemblyRasterState(int topology, int polygonMode, int cullMode) {
		int state = polygonMode & 0x7;
		state |= (topology & 0x1F) << 3;
		state |= (cullMode & 0x3) << 8;
		return state;
	}

	// Default triangle list, fill mode, back-face culling
	public static final int DEFAULT_ASSEMBLY_RASTER = encodeAssemblyRasterState(3, 0, 1);

	// ==================== Getters ====================

	public int getShaderProgramId() {
		return shaderProgramId;
	}

	public int getBlendState() {
		return blendState;
	}

	public int getDepthState() {
		return depthState;
	}

	public int getColorMask() {
		return colorMask;
	}

	public int getAssemblyRasterState() {
		return assemblyRasterState;
	}

	public long getRenderPassHandle() {
		return renderPassHandle;
	}

	// ==================== Equality & Hashing ====================

	private int computeHash() {
		int result = shaderProgramId;
		result = 31 * result + blendState;
		result = 31 * result + depthState;
		result = 31 * result + colorMask;
		result = 31 * result + assemblyRasterState;
		result = 31 * result + Long.hashCode(renderPassHandle);
		return result;
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof PipelineStateKey other)) return false;
		return shaderProgramId == other.shaderProgramId
			&& blendState == other.blendState
			&& depthState == other.depthState
			&& colorMask == other.colorMask
			&& assemblyRasterState == other.assemblyRasterState
			&& renderPassHandle == other.renderPassHandle;
	}

	@Override
	public String toString() {
		return "PipelineStateKey{shader=" + shaderProgramId
			+ ", blend=0x" + Integer.toHexString(blendState)
			+ ", depth=0x" + Integer.toHexString(depthState)
			+ ", colorMask=0x" + Integer.toHexString(colorMask)
			+ ", assembly=0x" + Integer.toHexString(assemblyRasterState)
			+ ", renderPass=0x" + Long.toHexString(renderPassHandle) + "}";
	}
}
