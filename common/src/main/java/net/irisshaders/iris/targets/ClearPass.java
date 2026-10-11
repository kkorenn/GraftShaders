package net.irisshaders.iris.targets;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.IrisRenderSystem;
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
		// GL_COLOR_BUFFER_BIT = 0x4000, GL_DEPTH_BUFFER_BIT = 0x100
		IrisRenderSystem.clearFramebuffer(framebuffer.getId(), (clearFlags & 0x4000) != 0, (clearFlags & 0x100) != 0, color);
	}

	public GlFramebuffer getFramebuffer() {
		return framebuffer;
	}
}
