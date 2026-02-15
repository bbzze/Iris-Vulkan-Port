package net.irisshaders.iris.gl;

/**
 * Base class for Vulkan resources in the Iris Vulkan port.
 * Replaces the original OpenGL GlResource class.
 *
 * In Vulkan, resource handles are 64-bit longs (not 32-bit ints like OpenGL).
 * Some resources may wrap VulkanMod objects rather than holding raw handles.
 *
 * The class name is kept as GlResource to minimize refactoring across the codebase.
 */
public abstract class GlResource {
	private long handle;
	private boolean isValid;

	/**
	 * Construct with a Vulkan handle (long).
	 */
	protected GlResource(long handle) {
		this.handle = handle;
		isValid = true;
	}

	/**
	 * Backward-compatible constructor accepting int handles.
	 * Converts to long internally. Used during incremental porting.
	 */
	protected GlResource(int id) {
		this((long) id);
	}

	public final void destroy() {
		if (isValid) {
			destroyInternal();
			isValid = false;
		}
	}

	protected abstract void destroyInternal();

	protected void assertValid() {
		if (!isValid) {
			throw new IllegalStateException("Tried to use a destroyed resource");
		}
	}

	/**
	 * Returns the resource handle as a long (native Vulkan handle type).
	 */
	protected long getHandle() {
		assertValid();
		return handle;
	}

	/**
	 * Returns the resource handle as an int for backward compatibility.
	 * During porting, some code still expects int IDs.
	 * @deprecated Use getHandle() for Vulkan handles
	 */
	@Deprecated
	protected int getGlId() {
		assertValid();
		return (int) handle;
	}

	/**
	 * Check if this resource is still valid (not destroyed).
	 */
	public boolean isValid() {
		return isValid;
	}

	/**
	 * Update the handle (e.g. after resize/recreate).
	 */
	protected void setHandle(long newHandle) {
		this.handle = newHandle;
	}
}
