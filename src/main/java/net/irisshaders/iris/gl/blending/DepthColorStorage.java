package net.irisshaders.iris.gl.blending;

import net.irisshaders.iris.gl.IrisRenderSystem;

/**
 * Depth and color storage - Vulkan Port.
 *
 * Manages depth mask and color mask overrides for shader packs.
 * GlStateManager calls replaced with IrisRenderSystem stubs.
 * Mixin accessors (GlStateManagerAccessor) removed since we track state internally.
 *
 * In Vulkan, depth/color masks are part of the pipeline state.
 */
public class DepthColorStorage {
	private static boolean originalDepthEnable;
	private static ColorMask originalColor;
	private static boolean depthColorLocked;

	// Internal state tracking (replaces GlStateManager accessor)
	private static boolean currentDepthMask = true;
	private static boolean currentColorR = true;
	private static boolean currentColorG = true;
	private static boolean currentColorB = true;
	private static boolean currentColorA = true;

	public static boolean isDepthColorLocked() {
		return depthColorLocked;
	}

	public static void disableDepthColor() {
		if (!depthColorLocked) {
			// Save previous state from our internal tracking
			originalDepthEnable = currentDepthMask;
			originalColor = new ColorMask(currentColorR, currentColorG, currentColorB, currentColorA);
		}

		depthColorLocked = false;

		currentDepthMask = false;
		currentColorR = false;
		currentColorG = false;
		currentColorB = false;
		currentColorA = false;
		IrisRenderSystem.depthMask(false);
		IrisRenderSystem.colorMask(false, false, false, false);

		depthColorLocked = true;
	}

	public static void deferDepthEnable(boolean enabled) {
		originalDepthEnable = enabled;
	}

	public static void deferColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
		originalColor = new ColorMask(red, green, blue, alpha);
	}

	public static void unlockDepthColor() {
		if (!depthColorLocked) {
			return;
		}

		depthColorLocked = false;

		currentDepthMask = originalDepthEnable;
		IrisRenderSystem.depthMask(originalDepthEnable);

		currentColorR = originalColor.isRedMasked();
		currentColorG = originalColor.isGreenMasked();
		currentColorB = originalColor.isBlueMasked();
		currentColorA = originalColor.isAlphaMasked();
		IrisRenderSystem.colorMask(originalColor.isRedMasked(), originalColor.isGreenMasked(),
			originalColor.isBlueMasked(), originalColor.isAlphaMasked());
	}

	/**
	 * Updates internal depth/color state tracking. Called by state listeners.
	 */
	public static void updateState(boolean depthMask, boolean r, boolean g, boolean b, boolean a) {
		if (!depthColorLocked) {
			currentDepthMask = depthMask;
			currentColorR = r;
			currentColorG = g;
			currentColorB = b;
			currentColorA = a;
		}
	}
}
