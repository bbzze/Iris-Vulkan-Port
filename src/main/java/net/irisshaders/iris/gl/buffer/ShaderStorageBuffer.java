package net.irisshaders.iris.gl.buffer;

import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;

// Do not extend GlResource, this is immutable.
public class ShaderStorageBuffer {
	// GL constants (inlined from GL43C)
	private static final int GL_BUFFER = 0x82E0;
	private static final int GL_SHADER_STORAGE_BUFFER = 0x90D2;
	private static final int GL_R8 = 0x8229;
	private static final int GL_RED = 0x1903;
	private static final int GL_BYTE = 0x1400;

	protected final int index;
	protected final ShaderStorageInfo info;
	protected int id;

	public ShaderStorageBuffer(int index, ShaderStorageInfo info) {
		this.id = IrisRenderSystem.createBuffers();
		GLDebug.nameObject(GL_BUFFER, id, "SSBO " + index);
		this.index = index;
		this.info = info;
	}

	public final int getIndex() {
		return index;
	}

	public final long getSize() {
		return info.size();
	}

	protected void destroy() {
		IrisRenderSystem.bindBufferBase(GL_SHADER_STORAGE_BUFFER, index, 0);
		// DO NOT use the GlStateManager version here! On Linux, it will attempt to clear the data using BufferData and cause GL errors.
		IrisRenderSystem.deleteBuffers(id);
	}

	public void bind() {
		IrisRenderSystem.bindBufferBase(GL_SHADER_STORAGE_BUFFER, index, id);
	}

	public void resizeIfRelative(int width, int height) {
		if (!info.relative()) return;

		IrisRenderSystem.deleteBuffers(id);
		int newId = IrisRenderSystem.glGenBuffers();
		IrisRenderSystem.glBindBuffer(GL_SHADER_STORAGE_BUFFER, newId);

		// Calculation time
		long newWidth = (long) (width * info.scaleX());
		long newHeight = (long) (height * info.scaleY());
		long finalSize = (newHeight * newWidth) * info.size();
		IrisRenderSystem.bufferStorage(GL_SHADER_STORAGE_BUFFER, finalSize, 0);
		IrisRenderSystem.clearBufferSubData(GL_SHADER_STORAGE_BUFFER, GL_R8, 0, finalSize, GL_RED, GL_BYTE, new int[]{0});
		IrisRenderSystem.bindBufferBase(GL_SHADER_STORAGE_BUFFER, index, newId);
		id = newId;
	}

	public int getId() {
		return id;
	}
}
