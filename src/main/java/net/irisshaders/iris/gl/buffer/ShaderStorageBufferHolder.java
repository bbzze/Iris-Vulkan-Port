package net.irisshaders.iris.gl.buffer;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ShaderStorageBufferHolder {
	// GL constants (inlined from GL43C)
	private static final int GL_SHADER_STORAGE_BUFFER = 0x90D2;
	private static final int GL_R8 = 0x8229;
	private static final int GL_RED = 0x1903;
	private static final int GL_BYTE = 0x1400;

	private int cachedWidth;
	private int cachedHeight;
	private ShaderStorageBuffer[] buffers;
	private boolean destroyed;

	private static List<ShaderStorageBuffer> ACTIVE_BUFFERS = new ArrayList<>();


	public ShaderStorageBufferHolder(Int2ObjectArrayMap<ShaderStorageInfo> overrides, int width, int height) {
		destroyed = false;
		cachedWidth = width;
		cachedHeight = height;
		buffers = new ShaderStorageBuffer[Collections.max(overrides.keySet()) + 1];
		overrides.forEach((index, bufferInfo) -> {
			if (bufferInfo.size() > IrisRenderSystem.getVRAM()) {
				throw new OutOfVideoMemoryError("We only have " + toMib(IrisRenderSystem.getVRAM()) + "MiB of RAM to work with, but the pack is requesting " + bufferInfo.size() + "! Can't continue.");
			}

			if (index > SamplerLimits.get().getMaxShaderStorageUnits()) {
				throw new IllegalStateException("We don't have enough SSBO units??? (index: " + index + ", max: " + SamplerLimits.get().getMaxShaderStorageUnits());
			}

			buffers[index] = new ShaderStorageBuffer(index, bufferInfo);
			ACTIVE_BUFFERS.add(buffers[index]);
			int buffer = buffers[index].getId();

			if (bufferInfo.relative()) {
				buffers[index].resizeIfRelative(width, height);
			} else {
				IrisRenderSystem.glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
				IrisRenderSystem.bufferStorage(GL_SHADER_STORAGE_BUFFER, bufferInfo.size(), 0);
				IrisRenderSystem.clearBufferSubData(GL_SHADER_STORAGE_BUFFER, GL_R8, 0, bufferInfo.size(), GL_RED, GL_BYTE, new int[]{0});
				IrisRenderSystem.bindBufferBase(GL_SHADER_STORAGE_BUFFER, index, buffer);
			}
		});
		IrisRenderSystem.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
	}

	private static long toMib(long x) {
		return x / 1024L / 1024L;
	}

	public void hasResizedScreen(int width, int height) {
		if (width != cachedWidth || height != cachedHeight) {
			cachedWidth = width;
			cachedHeight = height;
			for (ShaderStorageBuffer buffer : buffers) {
				if (buffer != null) {
					buffer.resizeIfRelative(width, height);
				}
			}
		}
	}

	public static void forceDeleteBuffers() {
		if (!ACTIVE_BUFFERS.isEmpty()) {
			Iris.logger.warn("Found " + ACTIVE_BUFFERS.size() + " stored buffers with a total size of " + ACTIVE_BUFFERS.stream().map(ShaderStorageBuffer::getSize).reduce(0L, Long::sum) + ", forcing them to be deleted.");
			ACTIVE_BUFFERS.forEach(ShaderStorageBuffer::destroy);
			ACTIVE_BUFFERS.clear();
		}
	}

	public void setupBuffers() {
		if (destroyed) {
			throw new IllegalStateException("Tried to use destroyed buffer objects");
		}

		for (ShaderStorageBuffer buffer : buffers) {
			if (buffer != null) {
				buffer.bind();
			}
		}
	}

	public int getBufferIndex(int index) {
		if (buffers.length < index || buffers[index] == null)
			throw new RuntimeException("Tried to query a buffer for indirect dispatch that doesn't exist!");

		return buffers[index].getId();
	}

	public void destroyBuffers() {
		for (ShaderStorageBuffer buffer : buffers) {
			if (buffer != null) {
				ACTIVE_BUFFERS.remove(buffer);
				buffer.destroy();
			}
		}
		buffers = null;
		destroyed = true;
	}

	private static class OutOfVideoMemoryError extends RuntimeException {
		public OutOfVideoMemoryError(String s) {
			super(s);
		}
	}
}
