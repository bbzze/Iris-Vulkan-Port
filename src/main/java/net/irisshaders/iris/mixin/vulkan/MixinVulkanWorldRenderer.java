package net.irisshaders.iris.mixin.vulkan;

import net.irisshaders.iris.pipeline.terrain.IrisTerrainRenderHook;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on VulkanMod's WorldRenderer to intercept terrain rendering.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public class MixinVulkanWorldRenderer {

	@Inject(method = "renderSectionLayer", at = @At("HEAD"))
	private void iris$beforeTerrainDraw(RenderType renderType, double camX, double camY, double camZ,
										Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		IrisTerrainRenderHook hook = IrisTerrainRenderHook.getInstance();
		if (hook.isActive()) {
			TerrainRenderType terrainRenderType = TerrainRenderType.get(renderType);
			hook.beginTerrainPass(terrainRenderType, modelView, projection);
		}
	}

	@Inject(method = "renderSectionLayer", at = @At("RETURN"))
	private void iris$afterTerrainDraw(RenderType renderType, double camX, double camY, double camZ,
									   Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
		IrisTerrainRenderHook hook = IrisTerrainRenderHook.getInstance();
		if (hook.isActive()) {
			hook.endTerrainPass();
		}
	}
}
