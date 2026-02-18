package net.irisshaders.iris.gl.program;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.uniform.IrisUniformBuffer;
import net.irisshaders.iris.mixin.GameRendererAccessor;
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
	private static int diagLogCount = 0;

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

			// Save raw values for diagnostic
			float rawM00 = proj.m00(), rawM11 = proj.m11(), rawM22 = proj.m22();
			float rawM23 = proj.m23(), rawM32 = proj.m32(), rawM33 = proj.m33();

			// VulkanMod overrides getDepthFar() to return POSITIVE_INFINITY, creating
			// a projection with infinite far plane. This causes m00 and m11 to be Infinity
			// (or other non-standard values). Shader packs need finite, correct values.
			// Rebuild the ENTIRE perspective projection from actual game parameters.
			if (proj.m23() != 0) { // perspective projection
				Minecraft mcClient = Minecraft.getInstance();
				float far = mcClient.gameRenderer != null ? mcClient.gameRenderer.getRenderDistance() : 256.0f;
				float near = 0.05f;

				// Rebuild m00/m11 from FOV and aspect ratio (matching terrain pipeline fix)
				if (!Float.isFinite(proj.m00()) || !Float.isFinite(proj.m11())) {
					double fovDegrees = 70.0; // default
					try {
						if (mcClient.gameRenderer != null) {
							fovDegrees = ((net.irisshaders.iris.mixin.GameRendererAccessor) mcClient.gameRenderer)
								.invokeGetFov(mcClient.gameRenderer.getMainCamera(),
									mcClient.getTimer().getGameTimeDeltaPartialTick(true), true);
						}
					} catch (Exception ignored) {}
					if (fovDegrees < 1.0 || !Double.isFinite(fovDegrees)) fovDegrees = 70.0;
					float fovRad = (float)(fovDegrees * Math.PI / 180.0);
					float tanHalfFov = (float) Math.tan(fovRad / 2.0);
					var window = mcClient.getWindow();
					float aspect = (float) window.getWidth() / (float) window.getHeight();
					proj.m00(1.0f / (aspect * tanHalfFov));
					proj.m11(1.0f / tanHalfFov);
				}

				// Fix infinite-far depth elements (m22, m32) with finite far
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

			// NOTE: m11 is NOT negated here. Scene shaders (gbuffer) use gl_FragCoord
			// with the flipped viewport, so they need the original m11 for correct
			// position reconstruction. Composite/deferred shaders handle the Y mismatch
			// via iris_flipProjY() injected by CompositeTransformer.

			float[] arr = new float[16];
			proj.get(arr);

			int projOff = uniformBuffer.getFieldOffset("gbufferProjection");
			int projInvOff = uniformBuffer.getFieldOffset("gbufferProjectionInverse");

			// Diagnostic: log first few composite UBO writes
			if (diagLogCount < 5) {
				diagLogCount++;
				Matrix4f invCheck = new Matrix4f(proj).invert();
				Iris.logger.info("[COMP_PROJ] prog='{}' raw=[{},{},{},{},{},{}] final=[{},{},{},{},{},{}] inv11={} off=proj:{} projInv:{}",
					this.name,
					String.format("%.4f", rawM00), String.format("%.4f", rawM11),
					String.format("%.6f", rawM22), String.format("%.4f", rawM23),
					String.format("%.6f", rawM32), String.format("%.4f", rawM33),
					String.format("%.4f", proj.m00()), String.format("%.4f", proj.m11()),
					String.format("%.6f", proj.m22()), String.format("%.4f", proj.m23()),
					String.format("%.6f", proj.m32()), String.format("%.4f", proj.m33()),
					String.format("%.4f", invCheck.m11()),
					projOff, projInvOff);
			}

			writeMatIfPresent("gbufferProjection", arr);
			// Save this frame's converted projection for next frame (only first call per frame)
			if (savedProjArr == null) savedProjArr = arr.clone();
			// Inverse
			Matrix4f inv = new Matrix4f(proj);
			inv.invert();
			inv.get(arr);
			writeMatIfPresent("gbufferProjectionInverse", arr);

			// Diagnostic: readback projection inverse to verify UBO data integrity
			if (diagLogCount <= 5 && projInvOff >= 0) {
				float[] pInvRB = uniformBuffer.readbackMat4f(projInvOff);
				if (pInvRB != null) {
					// Critical check: col2[3] (arr[11]) should be ~-10 (=1/m32), col3[2] (arr[14]) should be ~-1
					// If swapped, matrix is transposed or inverse is wrong
					Iris.logger.info("[DIAG_PROJINV] prog='{}' col2=({},{},{},{}) col3=({},{},{},{})",
						this.name,
						String.format("%.4f", pInvRB[8]), String.format("%.4f", pInvRB[9]),
						String.format("%.4f", pInvRB[10]), String.format("%.4f", pInvRB[11]),
						String.format("%.4f", pInvRB[12]), String.format("%.4f", pInvRB[13]),
						String.format("%.4f", pInvRB[14]), String.format("%.4f", pInvRB[15]));

					// Simulate shader viewPos reconstruction for top-center sky pixel
					// texCoord=(0.5, 0.0), depth=1.0 → NDC=(0, -1, 1, 1)
					// After iris_flipProjY (negate col1): use -pInvRB[4..7] for col1
					float ndc_x = 0.0f, ndc_y = -1.0f, ndc_z = 1.0f, ndc_w = 1.0f;
					float vx = pInvRB[0]*ndc_x + (-pInvRB[4])*ndc_y + pInvRB[8]*ndc_z + pInvRB[12]*ndc_w;
					float vy = pInvRB[1]*ndc_x + (-pInvRB[5])*ndc_y + pInvRB[9]*ndc_z + pInvRB[13]*ndc_w;
					float vz = pInvRB[2]*ndc_x + (-pInvRB[6])*ndc_y + pInvRB[10]*ndc_z + pInvRB[14]*ndc_w;
					float vw = pInvRB[3]*ndc_x + (-pInvRB[7])*ndc_y + pInvRB[11]*ndc_z + pInvRB[15]*ndc_w;
					float viewY = vy / vw;
					float viewZ = vz / vw;
					float len = (float) Math.sqrt(viewY*viewY + viewZ*viewZ);
					float VdotU_approx = viewY / len; // assuming level camera, upVec=(0,1,0)
					Iris.logger.info("[DIAG_VIEWPOS] prog='{}' topCenter: viewY={} viewZ={} vw={} VdotU_approx={}",
						this.name,
						String.format("%.4f", viewY), String.format("%.4f", viewZ),
						String.format("%.6f", vw), String.format("%.4f", VdotU_approx));
				}

				// Also readback MV and MV inverse for upVec verification
				int mvOff = uniformBuffer.getFieldOffset("gbufferModelView");
				float[] mvRB = uniformBuffer.readbackMat4f(mvOff);
				if (mvRB != null) {
					// Column 1 = upVec direction: (mvRB[4], mvRB[5], mvRB[6])
					Iris.logger.info("[DIAG_MV] prog='{}' col1(upVec)=({},{},{}) col0(eastVec)=({},{},{})",
						this.name,
						String.format("%.4f", mvRB[4]), String.format("%.4f", mvRB[5]), String.format("%.4f", mvRB[6]),
						String.format("%.4f", mvRB[0]), String.format("%.4f", mvRB[1]), String.format("%.4f", mvRB[2]));
				}
			}

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
