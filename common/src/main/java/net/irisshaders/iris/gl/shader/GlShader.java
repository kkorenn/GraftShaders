// This file is based on code from Sodium by JellySquid, licensed under the LGPLv3 license.

package net.irisshaders.iris.gl.shader;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.KHRDebug;

import java.util.Locale;

/**
 * A compiled OpenGL shader object.
 */
public class GlShader extends GlResource {
	private static final Logger LOGGER = LogManager.getLogger(GlShader.class);

	private final String name;

	public GlShader(ShaderType type, String name, String src) {
		super(createShader(type, name, src));

		this.name = name;
	}

	private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<String[]> SOURCES = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
	private static int nextId = 1;

	/** Metal compiles vertex+fragment together at link time; a shader object just holds its source. */
	private static int createShader(ShaderType type, String name, String src) {
		int id = nextId++;
		SOURCES.put(id, new String[]{type.name(), name, src});
		return id;
	}

	public static String[] source(int handle) {
		return SOURCES.get(handle);
	}

	public String getName() {
		return this.name;
	}

	public int getHandle() {
		return this.getGlId();
	}

	@Override
	protected void destroyInternal() {
		SOURCES.remove(this.getGlId());
	}
}
