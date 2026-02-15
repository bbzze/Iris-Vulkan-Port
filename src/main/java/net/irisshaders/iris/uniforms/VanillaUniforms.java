package net.irisshaders.iris.uniforms;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import org.joml.Vector2f;

/**
 * Vanilla uniforms - Vulkan Port.
 *
 * GlStateManager.Viewport replaced with IrisRenderSystem viewport tracking.
 */
public class VanillaUniforms {
	public static void addVanillaUniforms(DynamicUniformHolder uniforms) {
		Vector2f cachedScreenSize = new Vector2f();
		// listener -> {} dictates we want this to run on every shader update, not just on a new frame. These are dynamic.
		uniforms.uniform1f("iris_LineWidth", RenderSystem::getShaderLineWidth, listener -> {
		});
		uniforms.uniform2f("iris_ScreenSize", () -> cachedScreenSize.set(IrisRenderSystem.getViewportWidth(), IrisRenderSystem.getViewportHeight()), listener -> {
		});
	}
}
