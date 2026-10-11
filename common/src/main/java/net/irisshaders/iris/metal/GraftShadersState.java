package net.irisshaders.iris.metal;

/** Iris's per-frame binding state on Metal: which program is active and which texture/sampler each unit holds. */
public final class GraftShadersState {
	public static final int UNITS = 32;
	public static final int[] unitTextures = new int[UNITS];
	public static final int[] unitSamplers = new int[UNITS];
	/** SSBO binding index -> MetalBuffers id (glBindBufferBase(GL_SHADER_STORAGE_BUFFER, index, id)). */
	public static final int[] storageBuffers = new int[16];
	/** Image unit -> texture id (glBindImageTexture). */
	public static final int[] imageUnits = new int[8];
	/** MetalBuffers id bound to GL_DISPATCH_INDIRECT_BUFFER. */
	public static int dispatchIndirectBuffer;
	public static int activeUnit;
	public static MetalProgram program;
	public static int framebuffer;
	public static final float[] clearColor = new float[4];

	private GraftShadersState() {
	}

	// Replacements for the GlStateManager calls Iris makes directly.

	public static void activeTexture(int glUnit) {
		activeUnit = glUnit - 0x84C0; // GL_TEXTURE0
	}

	public static void bindTexture(int texture) {
		debugUnbind(activeUnit, texture);
		unitTextures[activeUnit] = texture;
	}

	private static int debugCount;

	/** Debug (with /tmp/graftshaders-dump): who clears unit 0 after it held a texture. */
	public static void debugUnbind(int unit, int texture) {
		if (unit == 0 && texture == 0 && unitTextures[0] != 0 && debugCount < 3 && java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/graftshaders-dump"))) {
			debugCount++;
			net.irisshaders.iris.Iris.logger.info("Graft Shaders debug: unit 0 cleared (was tex " + unitTextures[0] + ")", new Throwable());
		}
	}

	public static int uniformLocation(int program, CharSequence name) {
		MetalProgram p = MetalProgram.get(program);
		return p != null ? p.location(name.toString()) : -1;
	}

	public static int blockBinding(int program, String blockName) {
		MetalProgram p = MetalProgram.get(program);
		Integer b = p != null ? p.linked.blockBindings().get(blockName) : null;
		return b != null ? b : -1;
	}

	public static void useProgram(int id) {
		program = id == 0 ? null : MetalProgram.get(id);
	}

	/**
	 * Viewport {x, y, w, h} (GL convention) for the current full-screen Iris pass when the pack scales it
	 * (scale.compositeN), or null for the whole framebuffer. Set by CompositeRenderer around each pass.
	 */
	public static int[] passViewport;

	public static void bindFramebuffer(int target, int id) {
		framebuffer = id;
	}

	/** ponytail: fixed-function state (blend, masks, depth, cull, scissor, viewport) is not applied to Iris passes yet;
	 *  composite/final passes run opaque at full size. Wire these into MetalProgram pipelines for gbuffers (phase 2). */
	public static void ignoredState() {
	}

	public static int getInteger(int pname) {
		return switch (pname) {
			case 0x8CA6 -> framebuffer;      // GL_FRAMEBUFFER_BINDING
			case 0x8B8D -> 0;                // GL_CURRENT_PROGRAM
			case 0x84E0 -> 0x84C0 + activeUnit; // GL_ACTIVE_TEXTURE
			case 0x8069 -> unitTextures[activeUnit]; // GL_TEXTURE_BINDING_2D
			case 0x8824, 0x8CDF -> 8;        // GL_MAX_DRAW_BUFFERS, GL_MAX_COLOR_ATTACHMENTS
			case 0x8872, 0x8B4D -> 16;       // GL_MAX_TEXTURE_IMAGE_UNITS, GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
			case 0x0D33 -> 16384;            // GL_MAX_TEXTURE_SIZE
			default -> throw new UnsupportedOperationException("Graft Shaders: glGetInteger(0x" + Integer.toHexString(pname) + ") not ported");
		};
	}
}
