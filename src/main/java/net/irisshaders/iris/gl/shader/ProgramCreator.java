// This file is based on code from Sodium by JellySquid, licensed under the LGPLv3 license.

package net.irisshaders.iris.gl.shader;

import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.vulkan.shader.IrisSPIRVCompiler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;

/**
 * Program creator - Vulkan Port.
 *
 * In OpenGL, this created a GL program by compiling and linking shaders.
 * In Vulkan, shader compilation happens via GLSL -> SPIR-V (shaderc),
 * and "programs" become Vulkan graphics pipelines.
 *
 * This class now compiles each shader to SPIR-V and returns a tracking
 * program ID. The actual VkPipeline creation happens in Phase 6.
 */
public class ProgramCreator {
	private static final Logger LOGGER = LogManager.getLogger(ProgramCreator.class);

	// GL constant inlined
	private static final int GL_LINK_STATUS = 0x8B82;

	private static int nextProgramId = 1;

	/**
	 * Creates a "program" from the given shaders.
	 * In Vulkan, this compiles each shader's GLSL source to SPIR-V bytecode
	 * and returns a tracking program ID. The actual Vulkan pipeline will be
	 * created when the program is first used for rendering (Phase 6).
	 *
	 * @param name    The name of the program (for debugging)
	 * @param shaders The shaders that make up this program
	 * @return A tracking program ID
	 */
	public static int create(String name, GlShader... shaders) {
		int programId = nextProgramId++;

		for (GlShader shader : shaders) {
			GLDebug.nameObject(0x8B48, shader.getGlHandle(), shader.getName()); // GL_SHADER = 0x8B48

			// Compile shader source to SPIR-V if not already compiled
			if (shader.getSpirvBytecode() == null) {
				try {
					ByteBuffer spirv = IrisSPIRVCompiler.compile(
						shader.getName(),
						shader.getSource(),
						shader.getType()
					);
					shader.setSpirvBytecode(spirv);
					LOGGER.debug("Compiled SPIR-V for shader: {} ({})", shader.getName(), shader.getType().name());
				} catch (Exception e) {
					LOGGER.error("Failed to compile SPIR-V for shader: {} ({})", shader.getName(), shader.getType().name());
					throw new ShaderCompileException(name, e);
				}
			}
		}

		GLDebug.nameObject(0x8B40, programId, name); // GL_PROGRAM = 0x8B40

		LOGGER.debug("Created program '{}' with ID {} ({} shaders)", name, programId, shaders.length);

		return programId;
	}
}
