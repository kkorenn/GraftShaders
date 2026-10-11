package net.irisshaders.iris.metal;

import dev.graft.metal.MetalDevice;
import dev.graft.metal.ObjC;
import dev.graft.v1_21.MetalGpuDevice;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** Iris storage buffers (SSBOs, indirect dispatch args) as shared-storage MTLBuffers, keyed by Iris's int ids. */
public final class MetalBuffers {
	public record Buffer(long mtl, long size) {
	}

	private static final Int2ObjectOpenHashMap<Buffer> BUFFERS = new Int2ObjectOpenHashMap<>();
	private static int next = 1;

	private MetalBuffers() {
	}

	/** New zero-filled (or content-initialised) buffer. */
	public static int create(long size, ByteBuffer content) {
		long mtl = MetalGpuDevice.current().metal().newBuffer(size);
		long contents = MetalDevice.contents(mtl);
		MemoryUtil.memSet(contents, 0, Math.max(16L, size));
		if (content != null) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(content), contents, Math.min(size, content.remaining()));
		}
		BUFFERS.put(next, new Buffer(mtl, size));
		return next++;
	}

	public static Buffer get(int id) {
		return BUFFERS.get(id);
	}

	/** Released after the GPU finishes the work already recorded (it may still read the buffer). */
	public static void delete(int id) {
		Buffer b = BUFFERS.remove(id);
		if (b != null) {
			MetalGpuDevice.current().commands().afterGpu(() -> ObjC.release(b.mtl()));
		}
	}
}
