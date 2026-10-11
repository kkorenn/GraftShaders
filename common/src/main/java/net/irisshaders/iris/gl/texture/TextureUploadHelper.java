package net.irisshaders.iris.gl.texture;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL20C;

public class TextureUploadHelper {
	private TextureUploadHelper() {
		// no construction
	}

	public static void resetTextureUploadState() {
		// Ensure that the pixel storage mode is in a sane state, otherwise the uploaded texture data will be quite
		// incorrect.
		//
		// It is likely that this also avoids the crashes on AMD that I previously experienced with texture creation.
		//
		// This code is from Canvas: https://github.com/grondag/canvas/commit/f0ab652d7a8b7cc9febf0209bee15cffce9eac83
		net.irisshaders.iris.metal.GraftShadersState.ignoredState();
		net.irisshaders.iris.metal.GraftShadersState.ignoredState();
		net.irisshaders.iris.metal.GraftShadersState.ignoredState();
		net.irisshaders.iris.metal.GraftShadersState.ignoredState();
		net.irisshaders.iris.metal.GraftShadersState.ignoredState();
	}
}
