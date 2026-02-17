package net.irisshaders.iris.uniforms;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.minecraft.client.Minecraft;
import org.joml.Vector2f;

/**
 * Vanilla uniforms - Vulkan Port.
 *
 * iris_ScreenSize reads from Minecraft's window directly because VulkanMod
 * doesn't call glViewport() (it uses Vulkan viewports), so
 * IrisRenderSystem's viewport tracking is never updated from rendering.
 */
public class VanillaUniforms {
	public static void addVanillaUniforms(DynamicUniformHolder uniforms) {
		Vector2f cachedScreenSize = new Vector2f();
		uniforms.uniform1f("iris_LineWidth", RenderSystem::getShaderLineWidth, listener -> {
		});
		uniforms.uniform2f("iris_ScreenSize", () -> {
			var window = Minecraft.getInstance().getWindow();
			return cachedScreenSize.set(window.getWidth(), window.getHeight());
		}, listener -> {
		});
	}
}
