package net.irisshaders.iris.pipeline.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.VulkanTerrainPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.joml.Matrix4f;

/**
 * Orchestrates Iris terrain shader integration with VulkanMod's terrain draw path.
 *
 * Hooks into PipelineManager.setShaderGetter() to provide Iris's compiled terrain
 * pipelines instead of VulkanMod's defaults. Also manages gbuffer framebuffer
 * binding before/after terrain draws.
 *
 * During shadow rendering, an override framebuffer can be set via setShadowFramebuffer()
 * so that terrain draws write to the shadow depth texture instead of the gbuffer.
 */
public class IrisTerrainRenderHook {

	private static final IrisTerrainRenderHook INSTANCE = new IrisTerrainRenderHook();

	private final IrisTerrainPipelineCompiler compiler = new IrisTerrainPipelineCompiler();
	private VulkanTerrainPipeline terrainPipeline;
	private boolean active = false;

	// Diagnostic: log gbuffer bind state for first few terrain passes
	private static int diagTerrainFrameCount = 0;
	private static int lastDiagTerrainFrame = -1;

	// Diagnostic: log shadow pass terrain rendering
	private static int diagShadowPassCount = 0;
	private static int shadowPassCallCount = 0;

	// Shadow pass override: when non-null, beginTerrainPass() binds this instead of the gbuffer FB
	private GlFramebuffer shadowFramebuffer;

	private IrisTerrainRenderHook() {}

	public static IrisTerrainRenderHook getInstance() {
		return INSTANCE;
	}

	/**
	 * Activates the terrain pipeline hook.
	 * Compiles Iris terrain shaders and hooks PipelineManager.setShaderGetter().
	 *
	 * @param terrainPipeline The VulkanTerrainPipeline containing shader sources and framebuffers
	 */
	public void activate(VulkanTerrainPipeline terrainPipeline) {
		this.terrainPipeline = terrainPipeline;

		// Compile Iris terrain shaders into VulkanMod GraphicsPipeline objects
		compiler.compile(terrainPipeline);

		// Hook PipelineManager to use Iris terrain pipelines (which write to all gbuffer targets)
		PipelineManager.setShaderGetter(this::getTerrainPipeline);
		active = true;

		GlFramebuffer solidFb = terrainPipeline.getTerrainSolidFramebuffer();
		int solidAttachments = solidFb != null ? solidFb.getColorAttachments().size() : 0;
		Iris.logger.info("[IrisTerrainRenderHook] Activated — Iris terrain pipeline hooked. " +
			"Solid FB has {} color attachments.", solidAttachments);
	}

	/**
	 * Deactivates the terrain pipeline hook.
	 * Restores VulkanMod's default terrain shaders and cleans up compiled pipelines.
	 */
	public void deactivate() {
		active = false;
		terrainPipeline = null;
		shadowFramebuffer = null;

		PipelineManager.setDefaultShader();

		compiler.destroy();

		Iris.logger.info("[IrisTerrainRenderHook] Deactivated — terrain draws restored to VulkanMod defaults");
	}

	/**
	 * Sets a shadow framebuffer override. When set, beginTerrainPass() will bind this
	 * framebuffer instead of the normal gbuffer framebuffer. This allows terrain in
	 * the shadow pass to write depth to the shadow depth texture (shadowtex0).
	 *
	 * Call with null to clear the override (after shadow rendering completes).
	 */
	public void setShadowFramebuffer(GlFramebuffer fb) {
		this.shadowFramebuffer = fb;
	}

	/**
	 * Maps TerrainRenderType to the appropriate Iris GraphicsPipeline.
	 * During shadow rendering, uses shadow-specific pipelines that include the
	 * shader pack's shadow distortion (matching the fragment shader's GetShadowPos lookup).
	 * Falls back to VulkanMod's default pipelines if Iris's pipeline isn't available.
	 */
	private GraphicsPipeline getTerrainPipeline(TerrainRenderType renderType) {
		// During shadow pass, prefer shadow-specific pipelines
		if (shadowFramebuffer != null) {
			GraphicsPipeline pipeline = switch (renderType) {
				case SOLID, CUTOUT_MIPPED -> compiler.getShadowSolidPipeline();
				case CUTOUT -> compiler.getShadowCutoutPipeline();
				case TRANSLUCENT, TRIPWIRE -> compiler.getShadowSolidPipeline();
			};
			if (pipeline != null) return pipeline;
			// Fall through to gbuffer pipelines if shadow not available
		}

		GraphicsPipeline pipeline = switch (renderType) {
			case SOLID, CUTOUT_MIPPED -> compiler.getSolidPipeline();
			case CUTOUT -> compiler.getCutoutPipeline();
			case TRANSLUCENT, TRIPWIRE -> compiler.getTranslucentPipeline();
		};

		// Fall back to VulkanMod default if Iris pipeline not available
		if (pipeline == null) {
			pipeline = PipelineManager.getTerrainDirectShader(null);
		}
		return pipeline;
	}

	/**
	 * Called before terrain rendering in a given section layer.
	 * Binds the appropriate framebuffer:
	 * - During shadow rendering: binds the shadow framebuffer (writes depth to shadowtex0)
	 * - During normal rendering: binds the Iris gbuffer framebuffer
	 *
	 * @param renderType The terrain render type being drawn
	 */
	public void beginTerrainPass(TerrainRenderType renderType, Matrix4f modelView, Matrix4f projection) {
		if (!active || terrainPipeline == null) return;

		// Force unlock Iris's depth/color/blend overrides so we can set
		// the correct blend state for this terrain pass
		if (DepthColorStorage.isDepthColorLocked()) {
			DepthColorStorage.unlockDepthColor();
		}
		if (BlendModeStorage.isBlendLocked()) {
			BlendModeStorage.restoreBlend();
		}

		// Apply the correct blend mode override for this render type.
		// In Vulkan, blend state is baked into the pipeline, so this must be set
		// BEFORE VulkanMod's draw calls create/select the pipeline variant.
		// Without this, whatever blend state was active from a previous pass
		// (e.g. translucent particles) carries over, causing ghostly terrain.
		BlendModeOverride blendOverride = switch (renderType) {
			case SOLID, CUTOUT_MIPPED -> terrainPipeline.getTerrainSolidBlendOverride();
			case CUTOUT -> terrainPipeline.getTerrainCutoutBlendOverride();
			case TRANSLUCENT, TRIPWIRE -> terrainPipeline.getTranslucentBlendOverride();
		};

		if (blendOverride != null) {
			blendOverride.apply();
		} else {
			// No override specified — disable blending (correct for opaque terrain)
			BlendModeOverride.OFF.apply();
		}

		// Apply per-buffer blend overrides if the shader pack specifies them
		java.util.List<BufferBlendOverride> bufferOverrides = switch (renderType) {
			case SOLID, CUTOUT_MIPPED -> terrainPipeline.getTerrainSolidBufferOverrides();
			case CUTOUT -> terrainPipeline.getTerrainCutoutBufferOverrides();
			case TRANSLUCENT, TRIPWIRE -> terrainPipeline.getTranslucentBufferOverrides();
		};

		if (bufferOverrides != null) {
			bufferOverrides.forEach(BufferBlendOverride::apply);
		}

		// During shadow rendering, bind the shadow framebuffer so terrain depth
		// writes to shadowtex0 instead of the gbuffer depth.
		if (shadowFramebuffer != null) {
			shadowFramebuffer.bind();
			// Shadow viewport is already set by ShadowRenderer
			shadowPassCallCount++;
			if (diagShadowPassCount < 1) {
				diagShadowPassCount++;
				Iris.logger.info("[SHADOW_DIAG] beginTerrainPass SHADOW: type={} callCount={} depthTexId={} FB={}",
					renderType, shadowPassCallCount,
					shadowFramebuffer.hasDepthAttachment() ? shadowFramebuffer.getDepthAttachment() : -1,
					shadowFramebuffer.getId());
			}
		} else {
			// Normal rendering: bind the appropriate gbuffer framebuffer
			GlFramebuffer framebuffer = switch (renderType) {
				case SOLID, CUTOUT_MIPPED -> terrainPipeline.getTerrainSolidFramebuffer();
				case CUTOUT -> terrainPipeline.getTerrainCutoutFramebuffer();
				case TRANSLUCENT, TRIPWIRE -> terrainPipeline.getTranslucentFramebuffer();
			};

			if (framebuffer != null) {
				framebuffer.bind();

				// === DIAGNOSTIC: Confirm gbuffer FB is bound (first 3 frames) ===
				int frame = net.vulkanmod.vulkan.Renderer.getCurrentFrame();
				if (frame != lastDiagTerrainFrame) {
					lastDiagTerrainFrame = frame;
					if (diagTerrainFrameCount++ < 1) {
						int colorCount = framebuffer.getColorAttachments().size();
						boolean hasDepth = framebuffer.hasDepthAttachment();
						Iris.logger.info("[DIAG] Terrain gbuffer bind: type={} FB={} colors={} depth={} depthTexId={}",
							renderType, framebuffer.getId(), colorCount, hasDepth,
							hasDepth ? framebuffer.getDepthAttachment() : 0);
					}
				}
			}

			// Set viewport to full screen dimensions. Composite/final passes may
			// have set a scaled viewport that wasn't restored — terrain must always
			// render at full resolution to avoid partial/ghostly rendering.
			com.mojang.blaze3d.pipeline.RenderTarget mainRT = Minecraft.getInstance().getMainRenderTarget();
			RenderSystem.viewport(0, 0, mainRT.width, mainRT.height);

			// Bind shadow textures so terrain fragment shaders can sample
			// the shadow map for lighting/god rays. The shadow pass writes to
			// these textures; during the gbuffer pass we read them.
			GlFramebuffer shadowFB = terrainPipeline.getShadowFramebuffer();
			if (shadowFB != null) {
				int shadowDepthId = shadowFB.getDepthAttachment();
				if (shadowDepthId > 0) {
					IrisRenderSystem.bindTextureToUnit(0x0DE1, 14, shadowDepthId); // shadowtex0
					IrisRenderSystem.bindTextureToUnit(0x0DE1, 15, shadowDepthId); // shadowtex1
				}
				int shadowColor0 = shadowFB.getColorAttachment(0);
				if (shadowColor0 > 0) {
					IrisRenderSystem.bindTextureToUnit(0x0DE1, 16, shadowColor0); // shadowcolor0
				}
				int shadowColor1 = shadowFB.getColorAttachment(1);
				if (shadowColor1 > 0) {
					IrisRenderSystem.bindTextureToUnit(0x0DE1, 17, shadowColor1); // shadowcolor1
				}
				}

			// Bind PBR atlas textures (normals, specular) so the terrain
			// fragment shader can sample them for labPBR / PBR materials.
			// VulkanMod only binds the block atlas (slot 0) and lightmap (slot 2) —
			// the PBR textures must be bound explicitly to match
			// IrisTerrainPipelineCompiler.mapSamplerToTextureIndex().
			WorldRenderingPipeline worldPipeline = Iris.getPipelineManager().getPipeline().orElse(null);
			if (worldPipeline != null) {
				int normalTex = worldPipeline.getCurrentNormalTexture();
				int specularTex = worldPipeline.getCurrentSpecularTexture();
				if (normalTex > 0) {
					IrisRenderSystem.bindTextureToUnit(0x0DE1, 3, normalTex); // normals at VTextureSelector slot 3
				}
				if (specularTex > 0) {
					IrisRenderSystem.bindTextureToUnit(0x0DE1, 4, specularTex); // specular at VTextureSelector slot 4
				}

				// Bind noise texture for terrain emission/dithering effects.
				// The terrain fragment shader samples noisetex for emission calculations.
				int noiseTex = worldPipeline.getNoiseTextureId();
				if (noiseTex > 0) {
					IrisRenderSystem.bindTextureToUnit(0x0DE1, 18, noiseTex); // noisetex at VTextureSelector slot 18
				}
			}
		}

		// Update ManualUBO with current MVP matrix before draws
		compiler.updateUniforms(modelView, projection, shadowFramebuffer != null);
	}

	/**
	 * Called after terrain rendering in a given section layer.
	 * We intentionally do NOT end the render pass here — the Iris gbuffer
	 * render pass stays active so that subsequent entity/block entity draws
	 * also write into the gbuffer (which is correct Iris behavior).
	 * The render pass will be transitioned by the next beginRendering() call.
	 */
	public void endTerrainPass() {
		// Restore blend state so subsequent non-terrain draws get correct blending
		BlendModeOverride.restore();
		// Let the Iris gbuffer render pass stay active.
		// Entity rendering between terrain layers should also target the gbuffer.
	}

	public boolean isActive() {
		return active;
	}
}
