package net.irisshaders.iris.gl;

import net.irisshaders.iris.Iris;

import java.io.PrintStream;

/**
 * Vulkan debug utilities.
 * In Vulkan, debug output is handled via VK_EXT_debug_utils validation layers.
 * This class provides no-op stubs to maintain API compatibility.
 */
public final class GLDebug {

	public static int setupDebugMessageCallback() {
		Iris.logger.info("[Vulkan] Debug callbacks managed via Vulkan validation layers");
		return 1;
	}

	public static int setupDebugMessageCallback(PrintStream stream) {
		Iris.logger.info("[Vulkan] Debug callbacks managed via Vulkan validation layers");
		return 1;
	}

	public static int disableDebugMessages() {
		return 1;
	}

	public static Throwable filterStackTrace(Throwable throwable, int offset) {
		StackTraceElement[] elems = throwable.getStackTrace();
		StackTraceElement[] filtered = new StackTraceElement[elems.length];
		int j = 0;
		for (int i = offset; i < elems.length; i++) {
			filtered[j++] = elems[i];
		}
		StackTraceElement[] newElems = new StackTraceElement[j];
		System.arraycopy(filtered, 0, newElems, 0, j);
		throwable.setStackTrace(newElems);
		return throwable;
	}

	public static void reloadDebugState() {
		// No-op for Vulkan - validation layers handle this
	}

	public static void nameObject(int id, int object, String name) {
		// In Vulkan, object naming is done via vkSetDebugUtilsObjectNameEXT
		// No-op for now, can be implemented with VK_EXT_debug_utils
	}

	public static void pushGroup(int id, String name) {
		// vkCmdBeginDebugUtilsLabelEXT equivalent
	}

	public static void popGroup() {
		// vkCmdEndDebugUtilsLabelEXT equivalent
	}
}
