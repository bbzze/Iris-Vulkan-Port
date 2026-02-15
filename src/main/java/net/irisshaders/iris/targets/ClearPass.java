package net.irisshaders.iris.targets;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;

import java.util.Objects;
import java.util.function.IntSupplier;

public class ClearPass {
	private final Vector4f color;
	private final IntSupplier viewportX;
	private final IntSupplier viewportY;
	private final GlFramebuffer framebuffer;
	private final int clearFlags;

	public ClearPass(Vector4f color, IntSupplier viewportX, IntSupplier viewportY, GlFramebuffer framebuffer, int clearFlags) {
		this.color = color;
		this.viewportX = viewportX;
		this.viewportY = viewportY;
		this.framebuffer = framebuffer;
		this.clearFlags = clearFlags;
	}

	public void execute(Vector4f defaultClearColor) {
		Vector4f color = Objects.requireNonNull(defaultClearColor);

		if (this.color != null) {
			color = this.color;
		}

		// Use Vulkan-native clear via vkCmdClearColorImage.
		// In OpenGL, this was: bind framebuffer → glClear(). But in Vulkan,
		// GlFramebuffer.bind() starts a render pass with LOAD_OP_LOAD, and
		// RenderSystem.clear() inside a LOAD render pass is ineffective.
		// clearColorAttachments() ends any active render pass and uses
		// vkCmdClearColorImage directly on the attachment images.
		if ((clearFlags & 0x4000) != 0) { // GL_COLOR_BUFFER_BIT
			framebuffer.clearColorAttachments(color.x, color.y, color.z, color.w);
		}
	}

	public GlFramebuffer getFramebuffer() {
		return framebuffer;
	}
}
