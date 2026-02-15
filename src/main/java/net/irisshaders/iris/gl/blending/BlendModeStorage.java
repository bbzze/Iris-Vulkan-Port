package net.irisshaders.iris.gl.blending;

import net.irisshaders.iris.gl.IrisRenderSystem;

/**
 * Blend mode storage - Vulkan Port.
 *
 * Manages blend state overrides for shader packs.
 * GlStateManager calls replaced with IrisRenderSystem stubs.
 * Mixin accessors (GlStateManagerAccessor, BooleanStateAccessor) removed
 * since we track blend state internally.
 *
 * In Vulkan, blend state is baked into the pipeline, but we still need
 * to track the logical state for pipeline state key generation.
 */
public class BlendModeStorage {
	private static boolean originalBlendEnable;
	private static BlendMode originalBlend;
	private static boolean blendLocked;

	// Internal blend state tracking (replaces GlStateManager accessor)
	private static boolean currentBlendEnabled = false;
	private static int currentSrcRgb = 1;    // GL_ONE
	private static int currentDstRgb = 0;    // GL_ZERO
	private static int currentSrcAlpha = 1;  // GL_ONE
	private static int currentDstAlpha = 0;  // GL_ZERO

	public static boolean isBlendLocked() {
		return blendLocked;
	}

	public static void overrideBlend(BlendMode override) {
		if (!blendLocked) {
			// Save previous state from our internal tracking
			originalBlendEnable = currentBlendEnabled;
			originalBlend = new BlendMode(currentSrcRgb, currentDstRgb, currentSrcAlpha, currentDstAlpha);
		}

		blendLocked = false;

		if (override == null) {
			currentBlendEnabled = false;
			IrisRenderSystem.disableBlend();
		} else {
			currentBlendEnabled = true;
			IrisRenderSystem.enableBlend();
			currentSrcRgb = override.srcRgb();
			currentDstRgb = override.dstRgb();
			currentSrcAlpha = override.srcAlpha();
			currentDstAlpha = override.dstAlpha();
			IrisRenderSystem.blendFuncSeparate(override.srcRgb(), override.dstRgb(), override.srcAlpha(), override.dstAlpha());
		}

		blendLocked = true;
	}

	public static void overrideBufferBlend(int index, BlendMode override) {
		if (!blendLocked) {
			// Save previous state from our internal tracking
			originalBlendEnable = currentBlendEnabled;
			originalBlend = new BlendMode(currentSrcRgb, currentDstRgb, currentSrcAlpha, currentDstAlpha);
		}

		if (override == null) {
			IrisRenderSystem.disableBufferBlend(index);
		} else {
			IrisRenderSystem.enableBufferBlend(index);
			IrisRenderSystem.blendFuncSeparatei(index, override.srcRgb(), override.dstRgb(), override.srcAlpha(), override.dstAlpha());
		}

		blendLocked = true;
	}

	public static void deferBlendModeToggle(boolean enabled) {
		originalBlendEnable = enabled;
	}

	public static void deferBlendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
		originalBlend = new BlendMode(srcRgb, dstRgb, srcAlpha, dstAlpha);
	}

	public static void restoreBlend() {
		if (!blendLocked) {
			return;
		}

		blendLocked = false;

		if (originalBlendEnable) {
			currentBlendEnabled = true;
			IrisRenderSystem.enableBlend();
		} else {
			currentBlendEnabled = false;
			IrisRenderSystem.disableBlend();
		}

		currentSrcRgb = originalBlend.srcRgb();
		currentDstRgb = originalBlend.dstRgb();
		currentSrcAlpha = originalBlend.srcAlpha();
		currentDstAlpha = originalBlend.dstAlpha();
		IrisRenderSystem.blendFuncSeparate(originalBlend.srcRgb(), originalBlend.dstRgb(),
			originalBlend.srcAlpha(), originalBlend.dstAlpha());
	}

	/**
	 * Updates internal blend state tracking. Called by state listeners.
	 */
	public static void updateBlendState(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
		if (!blendLocked) {
			currentBlendEnabled = enabled;
			currentSrcRgb = srcRgb;
			currentDstRgb = dstRgb;
			currentSrcAlpha = srcAlpha;
			currentDstAlpha = dstAlpha;
		}
	}
}
