package net.irisshaders.iris.pbr.util;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public class TextureManipulationUtil {
	private static int colorFillFBO = -1;

	/** Fills every mip level (up to maxLevel) of a texture with one RGBA color. */
	public static void fillWithColor(int textureId, int maxLevel, int rgba) {
		com.mojang.blaze3d.textures.GpuTexture texture = net.irisshaders.iris.metal.MetalTextureIds.get(textureId);
		if (texture == null) {
			return;
		}
		// clearColorTexture clears all mip levels of the texture.
		com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, (rgba & 0xFF) << 24 | rgba >>> 8); // RGBA -> 1.21 ARGB
	}
}
