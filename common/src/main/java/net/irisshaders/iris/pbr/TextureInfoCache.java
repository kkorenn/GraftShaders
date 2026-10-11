package net.irisshaders.iris.pbr;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.irisshaders.iris.mixin.GlStateManagerAccessor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL20C;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class TextureInfoCache {
	public static final TextureInfoCache INSTANCE = new TextureInfoCache();

	private final Int2ObjectMap<TextureInfo> cache = new Int2ObjectOpenHashMap<>();

	private TextureInfoCache() {
	}

	public TextureInfo getInfo(int id) {
		TextureInfo info = cache.get(id);
		if (info == null) {
			info = new TextureInfo(id);
			cache.put(id, info);
		}
		return info;
	}

	public void onTexImage2D(int target, int level, int internalformat, int width, int height, int border,
							 int format, int type, @Nullable ByteBuffer pixels) {
		if (level == 0) {
			int id = net.irisshaders.iris.metal.GraftShadersState.unitTextures[net.irisshaders.iris.metal.GraftShadersState.activeUnit];
			TextureInfo info = getInfo(id);
			info.internalFormat = internalformat;
			info.width = width;
			info.height = height;
		}
	}

	public void onDeleteTexture(int id) {
		cache.remove(id);
	}

	public static class TextureInfo {
		private final int id;
		private int internalFormat = -1;
		private int width = -1;
		private int height = -1;

		private TextureInfo(int id) {
			this.id = id;
		}

		public int getId() {
			return id;
		}

		public int getInternalFormat() {
			if (internalFormat == -1) {
				internalFormat = fetchLevelParameter(GL20C.GL_TEXTURE_INTERNAL_FORMAT);
			}
			return internalFormat;
		}

		public int getWidth() {
			if (width == -1) {
				width = fetchLevelParameter(GL20C.GL_TEXTURE_WIDTH);
			}
			return width;
		}

		public int getHeight() {
			if (height == -1) {
				height = fetchLevelParameter(GL20C.GL_TEXTURE_HEIGHT);
			}
			return height;
		}

		private int fetchLevelParameter(int pname) {
			// Metal: read the size straight off the texture behind this id.
			com.mojang.renderpearl.api.textures.GpuTexture texture = net.irisshaders.iris.metal.MetalTextureIds.get(id);
			if (texture == null) {
				return 0;
			}
			return switch (pname) {
				case GL20C.GL_TEXTURE_WIDTH -> texture.getWidth(0);
				case GL20C.GL_TEXTURE_HEIGHT -> texture.getHeight(0);
				default -> throw new UnsupportedOperationException("Graft Shaders: texture level parameter 0x" + Integer.toHexString(pname));
			};
		}
	}
}
