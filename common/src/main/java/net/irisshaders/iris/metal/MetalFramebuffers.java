package net.irisshaders.iris.metal;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.Arrays;

/** Iris framebuffers on Metal: just the attachment set a later Metal render pass, copy or clear will use. */
public final class MetalFramebuffers {
	public static final class Framebuffer {
		/** index -> texture id (0 = none) */
		public final int[] colors = new int[8];
		public int depth;
		public int[] drawBuffers = {0};
		public int readBuffer;
	}

	private static final Int2ObjectOpenHashMap<Framebuffer> FRAMEBUFFERS = new Int2ObjectOpenHashMap<>();
	private static int next = 1;

	private MetalFramebuffers() {
	}

	public static int create() {
		FRAMEBUFFERS.put(next, new Framebuffer());
		return next++;
	}

	public static Framebuffer get(int id) {
		return FRAMEBUFFERS.get(id);
	}

	public static void delete(int id) {
		FRAMEBUFFERS.remove(id);
	}

	public static void attach(int id, int attachment, int texture) {
		Framebuffer fb = FRAMEBUFFERS.get(id);
		if (fb == null) {
			return;
		}
		// GL_COLOR_ATTACHMENT0.. = 0x8CE0.., GL_DEPTH_ATTACHMENT = 0x8D00, GL_DEPTH_STENCIL_ATTACHMENT = 0x821A
		if (attachment == 0x8D00 || attachment == 0x821A) {
			fb.depth = texture;
		} else {
			fb.colors[attachment - 0x8CE0] = texture;
		}
	}

	/** GL_NONE (0) entries mean "no draw buffer". */
	public static void drawBuffers(int id, int[] buffers) {
		Framebuffer fb = FRAMEBUFFERS.get(id);
		if (fb != null) {
			fb.drawBuffers = Arrays.stream(buffers).map(b -> b == 0 ? -1 : b - 0x8CE0).toArray();
		}
	}
}
