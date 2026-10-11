package net.irisshaders.iris.gl.buffer;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

// Do not extend GlResource, this is immutable.
public class ShaderStorageBuffer {
	protected final int index;
	protected final BuiltShaderStorageInfo info;
	protected final ByteBuffer content;
	protected int id;

	public ShaderStorageBuffer(int index, BuiltShaderStorageInfo info) {
		if (info.content() != null) {
			content = MemoryUtil.memAlloc(info.content().length);
			content.put(info.content());
			content.flip();
		} else {
			content = null;
		}
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
		if (net.irisshaders.iris.metal.GraftShadersState.storageBuffers[index] == id) {
			net.irisshaders.iris.metal.GraftShadersState.storageBuffers[index] = 0;
		}
		net.irisshaders.iris.metal.MetalBuffers.delete(id);
		MemoryUtil.memFree(content);
	}

	public void bind() {
		net.irisshaders.iris.metal.GraftShadersState.storageBuffers[index] = id;
	}

	/** Screen-relative buffers are reallocated (zeroed) when the window size changes. */
	public void resizeIfRelative(int width, int height) {
		if (!info.relative()) return;
		if (id != 0) {
			net.irisshaders.iris.metal.MetalBuffers.delete(id);
		}
		long newWidth = (long) (width * info.scaleX());
		long newHeight = (long) (height * info.scaleY());
		id = net.irisshaders.iris.metal.MetalBuffers.create((newHeight * newWidth) * info.size(), null);
		bind();
	}

	public int getId() {
		return id;
	}

	public void createStatic() {
		id = net.irisshaders.iris.metal.MetalBuffers.create(info.size(), content);
		bind();
	}
}
