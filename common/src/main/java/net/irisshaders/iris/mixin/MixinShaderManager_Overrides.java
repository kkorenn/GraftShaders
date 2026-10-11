package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import net.irisshaders.iris.metal.GraftShadersDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On GL, Iris swaps the compiled pipeline for its pack program here. On Metal the swap happens per draw (GraftShadersDraw),
 * which needs the vanilla RenderPipeline behind the backend pipeline a pass is drawing with: recorded here.
 */
@Mixin(RenderSystem.class)
public abstract class MixinShaderManager_Overrides {
	@Inject(method = "getCompiledPipelineNullable", at = @At("RETURN"))
	private static void iris$trackPipeline(RenderPipeline renderPipeline, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
		if (cir.getReturnValue() instanceof FrontendRenderPipeline frontend) {
			GraftShadersDraw.track(frontend.backendRenderPipeline(), renderPipeline);
		}
	}
}
