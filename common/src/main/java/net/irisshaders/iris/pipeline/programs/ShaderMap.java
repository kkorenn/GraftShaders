package net.irisshaders.iris.pipeline.programs;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import it.unimi.dsi.fastutil.objects.Object2BooleanFunction;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import org.lwjgl.opengl.GL46C;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A specialized map mapping {@link ShaderKey} to {@link CompiledShaderProgram}.
 * Avoids much of the complexity / overhead of an EnumMap while ultimately
 * fulfilling the same function.
 */
public class ShaderMap {
	private final GlProgram[] shaders;

	public ShaderMap(ShaderLoadingMap loadingMap, Function<ShaderSupplier, Boolean> deletionFunction, Consumer<GlProgram> programConsumer) {
		ShaderKey[] ids = ShaderKey.values();

		this.shaders = new GlProgram[ids.length];

		loadingMap.forAllShaders((key, shader) -> {
			if (shader != null) {
				if (deletionFunction.apply(shader)) {
					net.irisshaders.iris.metal.MetalProgram.delete(shader.id().program());
					return;
				}

				checkLinkingState(key, shader);
				GlProgram shaderProgram = shader.shader().get();
				this.shaders[key.ordinal()] = shaderProgram;
				programConsumer.accept(shaderProgram);
			}
		});
	}

	private void checkLinkingState(ShaderKey key, ShaderSupplier shader) {
		// Metal programs are compiled and validated when linked (MetalGlsl); nothing to check here.
	}

	public GlProgram getShader(ShaderKey id) {
		return shaders[id.ordinal()];
	}
}
