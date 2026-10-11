package net.irisshaders.iris.gl.framebuffer;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.pbr.TextureInfoCache;
import org.lwjgl.opengl.GL30C;

public class GlFramebuffer extends GlResource {
	private final Int2IntMap attachments;
	private final int maxDrawBuffers;
	private final int maxColorAttachments;
	private boolean hasDepthAttachment;

	public GlFramebuffer() {
		super(IrisRenderSystem.createFramebuffer());

		this.attachments = new Int2IntArrayMap();
		this.maxDrawBuffers = 8;
		this.maxColorAttachments = 8;
		this.hasDepthAttachment = false;
	}

	public void addDepthAttachment(GpuTexture texture) {
		int fb = getGlId();

		// TODO: NeoForge 1.21.5
		//if (texture.getFormat().hasStencilAspect()) {
		//	IrisRenderSystem.framebufferTexture2D(fb, GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_TEXTURE_2D, texture, 0);
		//} else {
			IrisRenderSystem.framebufferTexture2D(fb, GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_TEXTURE_2D, texture.iris$getGlId(), 0);
		//}

		this.hasDepthAttachment = true;
	}

	public void addDepthAttachmentBypass(int texture) {
		int fb = getGlId();

		IrisRenderSystem.framebufferTexture2D(fb, GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_TEXTURE_2D, texture, 0);

		this.hasDepthAttachment = true;
	}

	public void addColorAttachment(int index, int texture) {
		int fb = getGlId();

		IrisRenderSystem.framebufferTexture2D(fb, GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + index, GL30C.GL_TEXTURE_2D, texture, 0);
		attachments.put(index, texture);
	}

	public void noDrawBuffers() {
		IrisRenderSystem.drawBuffers(getGlId(), new int[]{GL30C.GL_NONE});
	}

	public void drawBuffers(int[] buffers) {
		int[] glBuffers = new int[buffers.length];
		int index = 0;

		if (buffers.length > maxDrawBuffers) {
			throw new IllegalArgumentException("Cannot write to more than " + maxDrawBuffers + " draw buffers on this GPU");
		}

		for (int buffer : buffers) {
			if (buffer >= maxColorAttachments) {
				throw new IllegalArgumentException("Only " + maxColorAttachments + " color attachments are supported on this GPU, but an attempt was made to write to a color attachment with index " + buffer);
			}

			glBuffers[index++] = GL30C.GL_COLOR_ATTACHMENT0 + buffer;
		}

		IrisRenderSystem.drawBuffers(getGlId(), glBuffers);
	}

	public void readBuffer(int buffer) {
		IrisRenderSystem.readBuffer(getGlId(), GL30C.GL_COLOR_ATTACHMENT0 + buffer);
	}

	public int getColorAttachment(int index) {
		return attachments.get(index);
	}

	public boolean hasDepthAttachment() {
		return hasDepthAttachment;
	}

	public void bind() {
		net.irisshaders.iris.metal.GraftShadersState.framebuffer = getGlId();
	}

	public void bindAsReadBuffer() {
		bind();
	}

	public void bindAsDrawBuffer() {
		bind();
	}

	protected void destroyInternal() {
		net.irisshaders.iris.metal.MetalFramebuffers.delete(getGlId());
	}

	public int getStatus() {
		bind();

		return IrisRenderSystem.checkFramebufferStatus(GL30C.GL_FRAMEBUFFER);
	}

	public int getId() {
		return getGlId();
	}
}
