package net.irisshaders.iris.metal;

import com.mojang.blaze3d.textures.GpuTexture;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Iris keys textures by int id (atlas tracking, PBR maps, sampler tables, render targets). On Metal the id is only a lookup
 * key: every operation goes to the Metal texture behind it. Each id also carries the sampling state Iris sets per texture.
 */
public final class MetalTextureIds {
	public static final class Entry {
		public GpuTexture texture;
		/** Raw MTLTexture for textures Minecraft's GpuTexture cannot describe (3D). Owned by the entry. */
		public long raw;
		/** Bytes per texel of {@link #raw}. */
		public int rawTexel;
		/** Channel type of {@link #raw} for clears: 'f' float/normalized, 'u' unsigned int, 'i' signed int. */
		public char rawKind = 'f';
		public boolean linear = true;
		public boolean mipmapped;
		public boolean clamp = true;
		/** MTLCompareFunction when GL_TEXTURE_COMPARE_MODE is COMPARE_REF_TO_TEXTURE, else -1. */
		public long compare = -1;
		int compareFunc = 0x0203; // GL_LEQUAL (GL default)
		boolean compareEnabled;
		public void setCompare(Boolean enabled, int glFunc) {
			if (enabled != null) {
				compareEnabled = enabled;
			}
			if (glFunc >= 0) {
				compareFunc = glFunc;
			}
			compare = compareEnabled ? MetalSamplers.compareFromGl(compareFunc) : -1;
		}

		/** Set for ids created by Iris itself (render targets, custom textures); they own their texture. */
		public boolean owned;
	}

	private static final Int2ObjectOpenHashMap<Entry> TEXTURES = new Int2ObjectOpenHashMap<>();
	private static int next = 1;

	private MetalTextureIds() {
	}

	/** Id for a texture Minecraft created. */
	public static int register(GpuTexture texture) {
		Entry e = new Entry();
		e.texture = texture;
		TEXTURES.put(next, e);
		return next++;
	}

	/** Empty id, filled by texImage (Iris-owned). */
	public static int allocate() {
		Entry e = new Entry();
		e.owned = true;
		TEXTURES.put(next, e);
		return next++;
	}

	public static void delete(int id) {
		Entry e = TEXTURES.remove(id);
		if (e != null && e.owned && e.texture != null) {
			e.texture.close();
		}
		if (e != null && e.raw != 0L) {
			dev.graft.metal.ObjC.release(e.raw);
		}
	}

	public static void unregister(int id) {
		TEXTURES.remove(id);
	}

	public static Entry entry(int id) {
		return TEXTURES.get(id);
	}

	/** MTLTexture behind an id (GpuTexture-backed or raw), or 0. */
	public static long mtl(int id) {
		return mtl(TEXTURES.get(id));
	}

	public static long mtl(Entry e) {
		if (e == null) {
			return 0L;
		}
		if (e.raw != 0L) {
			return e.raw;
		}
		return e.texture instanceof dev.graft.v26_2.MetalTexture t ? t.mtl() : 0L;
	}

	public static GpuTexture get(int id) {
		Entry e = TEXTURES.get(id);
		return e != null ? e.texture : null;
	}
}
