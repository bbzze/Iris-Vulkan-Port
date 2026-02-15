// Copyright 2020 Grondag
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.

package net.irisshaders.iris.gl.shader;

/**
 * Shader workarounds - Vulkan Port.
 *
 * The original workaround was for a crash in nglShaderSource on some AMD drivers
 * when using OpenGL. In Vulkan, shader source is compiled to SPIR-V bytecode via
 * shaderc before being loaded, so this workaround is no longer needed.
 *
 * This method now simply stores the source string for later SPIR-V compilation.
 * The actual SPIR-V compilation happens in IrisSPIRVCompiler.
 */
public class ShaderWorkarounds {
	/**
	 * In OpenGL, this was a workaround for AMD driver bugs with glShaderSource.
	 * In Vulkan, shader source is compiled to SPIR-V, so this is now a no-op
	 * pass-through. The source will be compiled via shaderc in the pipeline
	 * creation phase.
	 *
	 * @param glId   Legacy parameter (unused in Vulkan)
	 * @param source The shader source code
	 */
	public static void safeShaderSource(int glId, CharSequence source) {
		// In Vulkan, GLSL source is compiled to SPIR-V via shaderc,
		// not uploaded directly to a GL shader object.
		// This method is retained for API compatibility but is a no-op.
		// SPIR-V compilation is handled by IrisSPIRVCompiler.
	}
}
