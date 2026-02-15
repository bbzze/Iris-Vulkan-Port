package net.irisshaders.iris.gl.program;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.uniform.IrisUniformBuffer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Represents a shader program - Vulkan Port.
 *
 * In OpenGL, this was a linked GL program with attached vertex/fragment shaders.
 * In Vulkan, the equivalent is a VkPipeline (graphics or compute) which bakes
 * together shaders, vertex format, blend state, depth state, and render pass.
 *
 * Now holds a Vulkan GraphicsPipeline + IrisUniformBuffer + ManualUBO for
 * composite/final pass rendering.
 */
public final class Program extends GlResource {
	// Static previous-frame matrix tracking for gbufferPreviousModelView/Projection.
	// These track across all composite/entity programs since they share the same camera.
	// Frame detection: CapturedRenderingState.setGbufferProjection() creates a new Matrix4f
	// each frame, so reference comparison detects frame boundaries.
	private static float[] prevMvArr = null;
	private static float[] prevProjArr = null;
	private static float[] savedMvArr = null;   // current frame's MV (becomes prev next frame)
	private static float[] savedProjArr = null;  // current frame's proj (becomes prev next frame)
	private static Matrix4fc lastSeenProj = null; // for frame boundary detection

	// Diagnostic: log uniform values written to UBO for first few frames
	private static int diagUboFrameCount = 0;
	private static int lastDiagUboFrame = -1;

	private final String name;
	private final ProgramUniforms uniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;
	private final GraphicsPipeline pipeline;
	private final IrisUniformBuffer uniformBuffer;
	private final ManualUBO manualUBO;

	Program(int program, String name, ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images,
			GraphicsPipeline pipeline, IrisUniformBuffer uniformBuffer, ManualUBO manualUBO) {
		super(program);

		this.name = name != null ? name : "unknown";
		this.uniforms = uniforms;
		this.samplers = samplers;
		this.images = images;
		this.pipeline = pipeline;
		this.uniformBuffer = uniformBuffer;
		this.manualUBO = manualUBO;
	}

	public static void unbind() {
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		IrisRenderSystem.setActiveUniformBuffer(null);
	}

	public void use() {
		// Memory barrier before each program use, matching original Iris behavior.
		// Ensures compute shader writes (images, SSBOs, textures) are visible to
		// subsequent fragment shader reads.
		IrisRenderSystem.memoryBarrier(0x00000020 | 0x00000008 | 0x00002000);

		// Set active uniform buffer so IrisRenderSystem.uniform*() writes to our buffer
		if (uniformBuffer != null) {
			IrisRenderSystem.setActiveUniformBuffer(uniformBuffer);
		}

		// Update Iris uniforms (writes to active buffer via IrisRenderSystem)
		uniforms.update();
		images.update();

		// Write gbufferModelView, gbufferProjection, and other OptiFine-compatible
		// uniforms directly to the UBO. In original OpenGL Iris, composite programs
		// don't register these through ProgramUniforms, but in Vulkan all uniforms
		// live in a single UBO so we must write them explicitly.
		if (uniformBuffer != null) {
			writeGbufferUniforms();
		}

		// Update ManualUBO source pointer so VulkanMod copies our data at draw time
		if (manualUBO != null && uniformBuffer != null) {
			manualUBO.setSrc(uniformBuffer.getPointer(), uniformBuffer.getUsedSize());
		}

		// Bind the Vulkan pipeline for subsequent draw calls
		if (pipeline != null) {
			Renderer renderer = Renderer.getInstance();
			renderer.bindGraphicsPipeline(pipeline);

			// Bind Iris samplers AFTER pipeline bind so they set VTextureSelector correctly.
			// Iris's ProgramSamplers handles all texture binding via IrisRenderSystem.bindTextureToUnit(),
			// which sets VTextureSelector.boundTextures[] directly. Do NOT call
			// VTextureSelector.bindShaderTextures() here — that would overwrite Iris's
			// bindings for slots 0-11 with stale RenderSystem textures.
			samplers.update();

			renderer.uploadAndBindUBOs(pipeline);
		}
	}

	/**
	 * Writes gbufferModelView, gbufferProjection, their inverses, and cameraPosition
	 * to the UBO. These standard OptiFine/Iris uniforms are used by shader packs for
	 * camera-space calculations in composite/deferred/final passes.
	 */
	private void writeGbufferUniforms() {
		Matrix4fc gbufferMV = CapturedRenderingState.INSTANCE.getGbufferModelView();
		Matrix4fc gbufferProj = CapturedRenderingState.INSTANCE.getGbufferProjection();

		// Detect frame boundary: setGbufferProjection() creates a new Matrix4f each frame,
		// so a reference change means we've entered a new frame. Rotate saved→prev.
		if (gbufferProj != null && gbufferProj != lastSeenProj) {
			prevMvArr = savedMvArr;
			prevProjArr = savedProjArr;
			savedMvArr = null;
			savedProjArr = null;
			lastSeenProj = gbufferProj;
		}

		if (gbufferMV != null) {
			float[] arr = new float[16];
			gbufferMV.get(arr);
			writeMatIfPresent("gbufferModelView", arr);
			// Save this frame's MV for next frame (only first call per frame)
			if (savedMvArr == null) savedMvArr = arr.clone();
			// Inverse
			Matrix4f inv = new Matrix4f(gbufferMV);
			inv.invert();
			inv.get(arr);
			writeMatIfPresent("gbufferModelViewInverse", arr);
			// Previous frame model view
			if (prevMvArr != null) {
				writeMatIfPresent("gbufferPreviousModelView", prevMvArr);
			} else {
				gbufferMV.get(arr);
				writeMatIfPresent("gbufferPreviousModelView", arr);
			}
		}

		if (gbufferProj != null) {
			Matrix4f proj = new Matrix4f(gbufferProj);

			// VulkanMod overrides getDepthFar() to return POSITIVE_INFINITY, creating
			// a projection with infinite far plane. But shader packs use the 'far'
			// uniform (finite, = renderDistance in blocks) for depth linearization:
			//   GetDepth(z) = 2*near*far / (far+near - (2*z-1)*(far-near))
			//   GetDistX(d) = far*(d-near) / (d*(far-near))
			// These functions produce depth values for a FINITE far perspective.
			// If gbufferProjectionInverse uses infinite far but GetDistX uses finite
			// far, the VL ray march reconstructs positions at wrong distances.
			// Fix: replace the infinite-far depth elements (m22, m32) with finite-far
			// values matching the 'far' uniform. This makes GetDistX ↔ gbufferProjectionInverse
			// consistent, fixing VL ray marching and shadow lookups.
			if (proj.m23() != 0) { // perspective projection
				Minecraft mcClient = Minecraft.getInstance();
				float far = mcClient.gameRenderer != null ? mcClient.gameRenderer.getRenderDistance() : 256.0f;
				float near = 0.05f;
				// Vulkan zZeroToOne: m22 = -far/(far-near), m32 = -far*near/(far-near)
				proj.m22(-far / (far - near));
				proj.m32(-far * near / (far - near));
			}

			// VulkanMod's Matrix4fM mixin forces all projection matrices to use
			// Vulkan [0,1] depth range (zZeroToOne=true). But shader packs expect
			// OpenGL [-1,1] depth range because they reconstruct position via:
			//   clipPos.z = depth * 2.0 - 1.0  (converts [0,1] buffer to [-1,1] NDC)
			//   viewPos = gbufferProjectionInverse * clipPos
			// Convert from Vulkan [0,1] to OpenGL [-1,1] depth range:
			//   m22_gl = 2*m22_vk - m23_vk
			//   m32_gl = 2*m32_vk - m33_vk
			vulkanToOpenGLDepthRange(proj);

			// Negate Y scaling (m11) to compensate for VulkanMod's viewport Y-flip.
			// CompositeTransformer flips UV.y (1.0 - UV0.y) for correct Vulkan texture
			// sampling, which inverts clipPos.y. Negating m11 makes the inverse
			// compensate: viewPos.y = (-1/m11) * (-clipY) = clipY/m11 = correct.
			proj.m11(-proj.m11());

			float[] arr = new float[16];
			proj.get(arr);
			writeMatIfPresent("gbufferProjection", arr);
			// Save this frame's converted projection for next frame (only first call per frame)
			if (savedProjArr == null) savedProjArr = arr.clone();
			// Inverse
			Matrix4f inv = new Matrix4f(proj);
			inv.invert();
			inv.get(arr);
			writeMatIfPresent("gbufferProjectionInverse", arr);
			// Previous frame projection
			if (prevProjArr != null) {
				writeMatIfPresent("gbufferPreviousProjection", prevProjArr);
			} else {
				proj.get(arr);
				writeMatIfPresent("gbufferPreviousProjection", arr);
			}
		}

		// cameraPosition — from the Minecraft camera entity
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
			var camPos = mc.gameRenderer.getMainCamera().getPosition();
			writeVec3IfPresent("cameraPosition", (float) camPos.x, (float) camPos.y, (float) camPos.z);
		}

		// viewWidth / viewHeight
		if (mc.getWindow() != null) {
			writeFloatIfPresent("viewWidth", (float) mc.getWindow().getWidth());
			writeFloatIfPresent("viewHeight", (float) mc.getWindow().getHeight());
		}

		// near / far
		writeFloatIfPresent("near", 0.05f);
		if (mc.gameRenderer != null) {
			writeFloatIfPresent("far", mc.gameRenderer.getRenderDistance());
		}

		// Shadow matrices — read from ShadowRenderer's static fields
		Matrix4f shadowMV = net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW;
		Matrix4f shadowProj = net.irisshaders.iris.shadows.ShadowRenderer.PROJECTION;
		if (shadowMV != null) {
			float[] arr = new float[16];
			shadowMV.get(arr);
			writeMatIfPresent("shadowModelView", arr);
			Matrix4f smvInv = new Matrix4f(shadowMV).invert();
			smvInv.get(arr);
			writeMatIfPresent("shadowModelViewInverse", arr);
		}
		if (shadowProj != null) {
			Matrix4f sp = new Matrix4f(shadowProj);
			// ShadowMatrices.createOrthoMatrix() uses raw column values (NOT .ortho()),
			// so VulkanMod's Matrix4fM mixin does NOT affect it — it's already OpenGL-style.
			// Do NOT apply vulkanToOpenGLDepthRange() — that would double-convert.
			// Do NOT negate m11 — composite/final fragment shaders sample the shadow texture
			// directly (no rasterizer Y-flip), so shadow UV Y must match the stored layout.
			// The shadow vertex shader has m11 negated via the terrain UBO (which cancels
			// VulkanMod's negative viewport during shadow rendering).

			float[] arr = new float[16];
			sp.get(arr);
			writeMatIfPresent("shadowProjection", arr);
			Matrix4f spInv = new Matrix4f(sp).invert();
			spInv.get(arr);
			writeMatIfPresent("shadowProjectionInverse", arr);
		}

		// Celestial light positions — CRITICAL for deferred lighting.
		// ProgramUniforms/CelestialUniforms should write these via callbacks, but
		// we also write them explicitly as a safety net. Without sunPosition,
		// deferred shaders compute sunVec = normalize(vec3(0)) = NaN → black output.
		if (gbufferMV != null && mc.level != null) {
			float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
			float skyAngle = mc.level.getTimeOfDay(tickDelta);
			float sunAngle = skyAngle < 0.75f ? skyAngle + 0.25f : skyAngle - 0.75f;

			float sunPathRotation = 0.0f;
			try {
				var pm = net.irisshaders.iris.Iris.getPipelineManager();
				if (pm != null) {
					var wp = pm.getPipelineNullable();
					if (wp instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
						sunPathRotation = irp.getSunPathRotation();
					}
				}
			} catch (Exception ignored) {}

			Matrix4f celestial = new Matrix4f(gbufferMV);
			celestial.rotateY((float) Math.toRadians(-90.0));
			celestial.rotateZ((float) Math.toRadians(sunPathRotation));
			celestial.rotateX((float) Math.toRadians(skyAngle * 360.0f));

			org.joml.Vector4f sunPos = new org.joml.Vector4f(0, 100, 0, 0);
			celestial.transform(sunPos);
			writeVec3IfPresent("sunPosition", sunPos.x(), sunPos.y(), sunPos.z());

			org.joml.Vector4f moonPos = new org.joml.Vector4f(0, -100, 0, 0);
			celestial.transform(moonPos);
			writeVec3IfPresent("moonPosition", moonPos.x(), moonPos.y(), moonPos.z());

			boolean isDay = sunAngle <= 0.5f;
			if (isDay) {
				writeVec3IfPresent("shadowLightPosition", sunPos.x(), sunPos.y(), sunPos.z());
			} else {
				writeVec3IfPresent("shadowLightPosition", moonPos.x(), moonPos.y(), moonPos.z());
			}

			writeFloatIfPresent("sunAngle", sunAngle);
			float shadowAngle = isDay ? sunAngle : sunAngle - 0.5f;
			writeFloatIfPresent("shadowAngle", shadowAngle);

			// upPosition: modelView * rotY(-90) * (0, 100, 0, 0)
			Matrix4f preCelestial = new Matrix4f(gbufferMV);
			preCelestial.rotateY((float) Math.toRadians(-90.0));
			org.joml.Vector4f upPos = new org.joml.Vector4f(0, 100, 0, 0);
			preCelestial.transform(upPos);
			writeVec3IfPresent("upPosition", upPos.x(), upPos.y(), upPos.z());
		}

		// === DIAGNOSTIC: Log key uniform values for first 5 frames ===
		int frame = Renderer.getCurrentFrame();
		if (frame != lastDiagUboFrame) {
			lastDiagUboFrame = frame;
			if (diagUboFrameCount++ < 5) {
				StringBuilder sb = new StringBuilder();
				sb.append("[DIAG] === Program '").append(name).append("' UBO Values (frame ").append(frame).append(") ===\n");

				if (gbufferProj != null) {
					// Log the RAW Vulkan projection (before our conversion)
					sb.append("  gbufferProj(raw): m00=").append(String.format("%.4f", gbufferProj.m00()));
					sb.append(" m11=").append(String.format("%.4f", gbufferProj.m11()));
					sb.append(" m22=").append(String.format("%.4f", gbufferProj.m22()));
					sb.append(" m23=").append(String.format("%.4f", gbufferProj.m23()));
					sb.append(" m32=").append(String.format("%.4f", gbufferProj.m32()));
					sb.append(" m33=").append(String.format("%.4f", gbufferProj.m33()));
					sb.append("\n");

					// Log what we WROTE to the UBO (after conversion + m11 negate)
					Matrix4f converted = new Matrix4f(gbufferProj);
					vulkanToOpenGLDepthRange(converted);
					converted.m11(-converted.m11());
					sb.append("  gbufferProj(UBO): m00=").append(String.format("%.4f", converted.m00()));
					sb.append(" m11=").append(String.format("%.4f", converted.m11()));
					sb.append(" m22=").append(String.format("%.4f", converted.m22()));
					sb.append(" m23=").append(String.format("%.4f", converted.m23()));
					sb.append(" m32=").append(String.format("%.4f", converted.m32()));
					sb.append(" m33=").append(String.format("%.4f", converted.m33()));
					sb.append("\n");
				} else {
					sb.append("  gbufferProjection: NULL\n");
				}

				if (gbufferMV != null) {
					sb.append("  gbufferMV: m00=").append(String.format("%.4f", gbufferMV.m00()));
					sb.append(" m11=").append(String.format("%.4f", gbufferMV.m11()));
					sb.append(" m22=").append(String.format("%.4f", gbufferMV.m22()));
					sb.append("\n");
				} else {
					sb.append("  gbufferModelView: NULL\n");
				}

				// sunPosition
				if (gbufferMV != null && Minecraft.getInstance().level != null) {
					float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
					float skyAngle2 = Minecraft.getInstance().level.getTimeOfDay(tickDelta);
					float sunAngle2 = skyAngle2 < 0.75f ? skyAngle2 + 0.25f : skyAngle2 - 0.75f;
					sb.append("  skyAngle=").append(String.format("%.4f", skyAngle2));
					sb.append(" sunAngle=").append(String.format("%.4f", sunAngle2));
					sb.append(" isDay=").append(sunAngle2 <= 0.5f);
					sb.append("\n");
				}

				// UBO field offsets — check if key fields are registered
				String[] keyFields = {"sunPosition", "gbufferProjection", "gbufferModelView", "shadowLightPosition", "upPosition"};
				sb.append("  UBO field offsets: ");
				for (String field : keyFields) {
					int off = uniformBuffer.getFieldOffset(field);
					sb.append(field).append("=").append(off).append(" ");
				}
				sb.append("\n");
				sb.append("  UBO usedSize=").append(uniformBuffer.getUsedSize());
				sb.append(" fieldCount=").append(uniformBuffer.getFields().size()).append("\n");

				sb.append("[DIAG] === End Program '").append(name).append("' UBO Values ===");
				Iris.logger.info(sb.toString());
			}
		}

	}

	/**
	 * Converts a projection matrix from Vulkan [0,1] depth range to OpenGL [-1,1]
	 * depth range. VulkanMod's Matrix4fM mixin forces zZeroToOne=true on all
	 * projection matrices, but shader packs expect OpenGL-style depth.
	 *
	 * The conversion is derived from: P_gl = D_inv * P_vk, where D maps OpenGL
	 * clip-space Z to Vulkan clip-space Z: z_vk = 0.5*z_gl + 0.5*w.
	 * Only the Z row (row 2) of the projection matrix is affected:
	 *   m22_gl = 2*m22_vk - m23_vk
	 *   m32_gl = 2*m32_vk - m33_vk
	 */
	private static void vulkanToOpenGLDepthRange(Matrix4f proj) {
		proj.m22(2.0f * proj.m22() - proj.m23());
		proj.m32(2.0f * proj.m32() - proj.m33());
	}

	private void writeMatIfPresent(String name, float[] arr) {
		int off = uniformBuffer.getFieldOffset(name);
		if (off >= 0) uniformBuffer.writeMat4f(off, arr);
	}

	private void writeVec3IfPresent(String name, float x, float y, float z) {
		int off = uniformBuffer.getFieldOffset(name);
		if (off >= 0) uniformBuffer.writeVec3f(off, x, y, z);
	}

	private void writeFloatIfPresent(String name, float val) {
		int off = uniformBuffer.getFieldOffset(name);
		if (off >= 0) uniformBuffer.writeFloat(off, val);
	}

	public GraphicsPipeline getPipeline() {
		return pipeline;
	}

	public void destroyInternal() {
		if (uniformBuffer != null) {
			uniformBuffer.free();
		}
	}

	/**
	 * @return the program ID (tracking ID in Vulkan port)
	 * @deprecated this should be encapsulated eventually
	 */
	@Deprecated
	public int getProgramId() {
		return getGlId();
	}

	public int getActiveImages() {
		return images.getActiveImages();
	}
}
