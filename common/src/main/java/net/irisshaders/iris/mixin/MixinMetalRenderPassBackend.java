package net.irisshaders.iris.mixin;

import dev.graft.v1_21.MetalRenderPassBackend;
import net.irisshaders.iris.metal.GraftShadersState;
import net.irisshaders.iris.metal.MetalPassTarget;
import net.irisshaders.iris.mixinterface.CustomPass;
import net.irisshaders.iris.mixinterface.RenderPassInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Metal counterpart of MixinRenderPass (GlRenderPass): Iris's custom pass + the framebuffer the pass draws into. */
@Mixin(value = MetalRenderPassBackend.class, remap = false)
public class MixinMetalRenderPassBackend implements RenderPassInterface, MetalPassTarget {
	@Unique
	private CustomPass iris$customPass;
	@Unique
	private int graftshaders$target;

	/** Creating a pass binds its own attachments, like glBindFramebuffer in Mojang's GL encoder. */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void graftshaders$onCreate(CallbackInfo ci) {
		GraftShadersState.framebuffer = 0;
	}

	@Override
	public void iris$setCustomPass(CustomPass pass) {
		this.iris$customPass = pass;
	}

	@Override
	public CustomPass iris$getCustomPass() {
		return this.iris$customPass;
	}

	@Override
	public int graftshaders$target() {
		return graftshaders$target;
	}

	@Override
	public void graftshaders$setTarget(int framebuffer) {
		this.graftshaders$target = framebuffer;
	}
}
