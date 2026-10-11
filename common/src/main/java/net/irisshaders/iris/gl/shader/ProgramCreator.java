// This file is based on code from Sodium by JellySquid, licensed under the LGPLv3 license.

package net.irisshaders.iris.gl.shader;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.KHRDebug;

public class ProgramCreator {
	private static final Logger LOGGER = LogManager.getLogger(ProgramCreator.class);

	public static int create(String name, GlShader... shaders) {
		String vertex = null;
		String fragment = null;
		String geometry = null;
		String control = null;
		String evaluation = null;
		for (GlShader shader : shaders) {
			String[] src = GlShader.source(shader.getHandle());
			switch (src[0]) {
				case "VERTEX" -> vertex = src[2];
				case "FRAGMENT" -> fragment = src[2];
				case "GEOMETRY" -> geometry = src[2];
				case "TESSELATION_CONTROL" -> control = src[2];
				case "TESSELATION_EVAL" -> evaluation = src[2];
				case "COMPUTE" -> {
					return net.irisshaders.iris.metal.MetalProgram.create(name, net.irisshaders.iris.metal.MetalGlsl.linkCompute(name, src[2]));
				}
				default -> throw new ShaderCompileException(name, "Graft Shaders: " + src[0] + " shaders are not supported on Metal");
			}
		}
		java.util.Map<String, Integer> attributes = new java.util.HashMap<>();
		attributes.put("iris_Entity", 11);
		attributes.put("mc_Entity", 11);
		attributes.put("mc_midTexCoord", 12);
		attributes.put("at_tangent", 13);
		attributes.put("at_midBlock", 14);
		attributes.put("Position", 0);
		attributes.put("UV0", 1);
		// Iris's own pathway shaders name the same inputs with an iris_ prefix.
		attributes.put("iris_Position", 0);
		attributes.put("iris_UV0", 1);
		net.irisshaders.iris.metal.MetalGlsl.Linked linked = net.irisshaders.iris.metal.MetalGlsl.link(name, vertex, control, evaluation, geometry, fragment, attributes);
		return net.irisshaders.iris.metal.MetalProgram.create(name, linked);
	}
}
