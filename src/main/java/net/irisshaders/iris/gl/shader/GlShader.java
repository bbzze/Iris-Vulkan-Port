package net.irisshaders.iris.gl.shader;

import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.vulkan.shader.IrisSPIRVCompiler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;

/**
 * A compiled shader object - Vulkan Port.
 *
 * In OpenGL, this was a compiled GLSL shader (vertex/fragment/etc).
 * In Vulkan, shaders are compiled to SPIR-V bytecode and wrapped in VkShaderModule.
 *
 * The shader stores its GLSL source and compiles to SPIR-V on demand via
 * IrisSPIRVCompiler, which bridges to VulkanMod's SPIRVUtils (shaderc).
 */
public class GlShader extends GlResource {
	private static final Logger LOGGER = LogManager.getLogger(GlShader.class);

	private final String name;
	private final ShaderType type;
	private final String source;
	private ByteBuffer spirvBytecode;
	private boolean compilationAttempted;

	public GlShader(ShaderType type, String name, String src) {
		super(0L); // No GL handle; Vulkan uses VkShaderModule handles

		this.name = name;
		this.type = type;
		this.source = src;
		this.compilationAttempted = false;

		LOGGER.debug("Shader created (pending SPIR-V compilation): {} ({})", name, type.name());
	}

	public String getName() {
		return this.name;
	}

	public ShaderType getType() {
		return this.type;
	}

	public String getSource() {
		return this.source;
	}

	/**
	 * Gets the compiled SPIR-V bytecode. If not yet compiled, triggers
	 * compilation via IrisSPIRVCompiler.
	 *
	 * @return The SPIR-V bytecode buffer, or null if compilation failed
	 */
	public ByteBuffer getSpirvBytecode() {
		if (spirvBytecode == null && !compilationAttempted) {
			compileSPIRV();
		}
		return spirvBytecode;
	}

	/**
	 * Sets pre-compiled SPIR-V bytecode (e.g., from cache or external compilation).
	 */
	public void setSpirvBytecode(ByteBuffer spirv) {
		this.spirvBytecode = spirv;
		this.compilationAttempted = true;
	}

	/**
	 * Compiles the shader's GLSL source to SPIR-V bytecode.
	 * Uses IrisSPIRVCompiler which bridges to VulkanMod's SPIRVUtils (shaderc).
	 */
	private void compileSPIRV() {
		compilationAttempted = true;
		try {
			spirvBytecode = IrisSPIRVCompiler.compile(name, source, type);
			LOGGER.debug("SPIR-V compilation successful for shader: {} ({} bytes)",
				name, spirvBytecode != null ? spirvBytecode.remaining() : 0);
		} catch (Exception e) {
			LOGGER.error("SPIR-V compilation failed for shader: {} ({})", name, type.name(), e);
			spirvBytecode = null;
		}
	}

	/**
	 * Returns whether this shader has been successfully compiled to SPIR-V.
	 */
	public boolean isCompiled() {
		return spirvBytecode != null;
	}

	/**
	 * Returns the shader handle as an int for backward compatibility.
	 * Note: This intentionally does not override GlResource.getHandle() which returns long.
	 */
	public int getGlHandle() {
		return this.getGlId();
	}

	@Override
	protected void destroyInternal() {
		// VkShaderModule destruction will be handled in Phase 6
		if (spirvBytecode != null) {
			spirvBytecode = null;
		}
	}
}
