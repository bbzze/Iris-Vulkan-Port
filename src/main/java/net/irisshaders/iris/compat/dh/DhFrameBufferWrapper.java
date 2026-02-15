package net.irisshaders.iris.compat.dh;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiFramebuffer;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;

public class DhFrameBufferWrapper implements IDhApiFramebuffer {
	// GL constants (inlined from GL32)
	private static final int GL_FRAMEBUFFER = 0x8D40;

	private final GlFramebuffer framebuffer;


	public DhFrameBufferWrapper(GlFramebuffer framebuffer) {
		this.framebuffer = framebuffer;
	}


	@Override
	public boolean overrideThisFrame() {
		return true;
	}

	@Override
	public void bind() {
		this.framebuffer.bind();
	}

	@Override
	public void addDepthAttachment(int i, boolean b) {
		// ignore
	}

	@Override
	public int getId() {
		return this.framebuffer.getId();
	}

	@Override
	public int getStatus() {
		this.bind();
		int status = IrisRenderSystem.checkFramebufferStatus(GL_FRAMEBUFFER);
		return status;
	}

	@Override
	public void addColorAttachment(int i, int i1) {
		// ignore
	}

	@Override
	public void destroy() {
		// ignore
		//this.framebuffer.destroy();
	}

}
