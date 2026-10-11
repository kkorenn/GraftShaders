package net.irisshaders.iris.metal;

import dev.graft.v26_2.MetalGpuDevice;
import dev.graft.metal.ObjC;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

/** Iris sampler objects as MTLSamplerStates, keyed by Iris's int ids. Also caches the per-texture default samplers. */
public final class MetalSamplers {
	private static final Int2LongOpenHashMap SAMPLERS = new Int2LongOpenHashMap();
	private static final Int2LongOpenHashMap DEFAULTS = new Int2LongOpenHashMap();
	private static int next = 1;

	private MetalSamplers() {
	}

	/** compare: MTLCompareFunction for hardware shadow sampling, or -1. */
	private static long newState(boolean linear, boolean mipmapped, boolean linearMips, boolean clamp, long compare) {
		long address = clamp ? 0 : 2;
		long filter = linear ? 1 : 0;
		long mipFilter = !mipmapped ? 0 : linearMips ? 2 : 1;
		return MetalGpuDevice.current().metal().newSampler(address, address, filter, filter, mipFilter, 1, mipmapped ? 1000.0 : 0.0, compare);
	}

	public static int create(boolean linear, boolean linearMips, boolean mipmapped, boolean clamp) {
		return create(linear, linearMips, mipmapped, clamp, -1L);
	}

	public static int create(boolean linear, boolean linearMips, boolean mipmapped, boolean clamp, long compare) {
		int id = next++;
		SAMPLERS.put(id, newState(linear, mipmapped, linearMips, clamp, compare));
		return id;
	}

	/** Gives an existing Metal sampler state (e.g. a vanilla GpuSampler's) an Iris sampler id; retained, released on destroy. */
	public static int wrap(long mtlSampler) {
		int id = next++;
		SAMPLERS.put(id, ObjC.retain(mtlSampler));
		return id;
	}

	public static long get(int id) {
		return SAMPLERS.get(id);
	}

	public static void destroy(int id) {
		ObjC.release(SAMPLERS.remove(id));
	}

	/** Sampler for a texture bound without a sampler object (GL texture-parameter state, including compare mode). */
	public static long forTexture(MetalTextureIds.Entry e) {
		int key = (e.linear ? 1 : 0) | (e.mipmapped ? 2 : 0) | (e.clamp ? 4 : 0) | ((int)(e.compare + 1) << 3);
		long s = DEFAULTS.get(key);
		if (s == 0L) {
			s = newState(e.linear, e.mipmapped, true, e.clamp, e.compare);
			DEFAULTS.put(key, s);
		}
		return s;
	}

	/** GL compare func (GL_NEVER..GL_ALWAYS) to MTLCompareFunction. */
	public static long compareFromGl(int gl) {
		return switch (gl) {
			case 0x0200 -> 0; // NEVER
			case 0x0201 -> 1; // LESS
			case 0x0202 -> 2; // EQUAL
			case 0x0203 -> 3; // LEQUAL
			case 0x0204 -> 4; // GREATER
			case 0x0205 -> 5; // NOTEQUAL
			case 0x0206 -> 6; // GEQUAL
			default -> 7;     // ALWAYS
		};
	}
}
