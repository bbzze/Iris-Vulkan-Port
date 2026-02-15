package net.irisshaders.iris.gl;

/**
 * Iris limits - Vulkan Port.
 *
 * VK_CONFORMANCE is set to true for the Vulkan port, enabling
 * layout qualifier injection (LayoutTransformer) in the shader
 * transformation pipeline. This is required for Vulkan SPIR-V
 * compilation since Vulkan requires explicit layout locations
 * on all in/out declarations.
 */
public class IrisLimits {
	/**
	 * The maximum number of color textures that a shader pack can write to and read from in gbuffer and composite
	 * programs.
	 * <p>
	 * It's not recommended to raise this higher than 16 until code for avoiding allocation of unused color textures
	 * is implemented.
	 */
	public static final int MAX_COLOR_BUFFERS = 16;
	public static final boolean VK_CONFORMANCE = true;
}
