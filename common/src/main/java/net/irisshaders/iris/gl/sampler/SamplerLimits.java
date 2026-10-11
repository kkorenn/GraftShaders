package net.irisshaders.iris.gl.sampler;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL45C;

public class SamplerLimits {
	private static SamplerLimits instance;
	private final int maxTextureUnits;
	private final int maxDrawBuffers;
	private final int maxShaderStorageUnits;

	private SamplerLimits() {
		// Metal per-stage limits (Apple GPUs): 16 sampler states, 8 color attachments. Textures share sampler
		// slots 1:1 in Graft Shaders, so 16 is the texture unit limit too. Storage buffers share the 31-entry buffer table.
		this.maxTextureUnits = net.irisshaders.iris.metal.GraftShadersState.UNITS; // texture slots; samplers past 16 share an array (MetalGlsl.shareSamplers)
		this.maxDrawBuffers = 8;
		this.maxShaderStorageUnits = 8;
	}

	public static SamplerLimits get() {
		if (instance == null) {
			instance = new SamplerLimits();
		}

		return instance;
	}

	public int getMaxTextureUnits() {
		return maxTextureUnits;
	}

	public int getMaxDrawBuffers() {
		return maxDrawBuffers;
	}

	public int getMaxShaderStorageUnits() {
		return maxShaderStorageUnits;
	}
}
