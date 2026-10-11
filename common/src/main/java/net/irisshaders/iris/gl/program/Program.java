package net.irisshaders.iris.gl.program;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.lwjgl.opengl.GL43C;

public final class Program extends GlResource {
	private final ProgramUniforms uniforms;
	private final ProgramSamplers samplers;
	private final ProgramImages images;

	Program(int program, ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images) {
		super(program);

		this.uniforms = uniforms;
		this.samplers = samplers;
		this.images = images;
	}

	public static void unbind() {
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		net.irisshaders.iris.metal.GraftShadersState.program = null;
	}

	public void use() {
		net.irisshaders.iris.metal.GraftShadersState.program = net.irisshaders.iris.metal.MetalProgram.get(getGlId());

		uniforms.update();
		samplers.update();
		images.update();
	}

	public void destroyInternal() {
		net.irisshaders.iris.metal.MetalProgram.delete(getGlId());
	}

	/**
	 * @return the OpenGL ID of this program.
	 * @deprecated this should be encapsulated eventually
	 */
	@Deprecated
	public int getProgramId() {
		return getGlId();
	}

	public int getActiveImages() {
		return images.getActiveImages();
	}
}
