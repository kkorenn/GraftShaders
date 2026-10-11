package net.irisshaders.iris.mixin;

import dev.graft.v26_2.MetalTexture;
import net.irisshaders.iris.metal.MetalTextureIds;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MetalTexture.class, remap = false)
public abstract class MixinMetalTexture implements GpuTextureInterface {
	@Unique
	private int iris$id;
	@Unique
	private boolean iris$mipmapNonLinear;

	@Override
	public int iris$getGlId() {
		if (iris$id == 0) {
			iris$id = MetalTextureIds.register((MetalTexture)(Object)this);
		}
		return iris$id;
	}

	@Override
	public void iris$markMipmapNonLinear() {
		iris$mipmapNonLinear = true;
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void iris$unregister(CallbackInfo ci) {
		if (iris$id != 0) {
			MetalTextureIds.unregister(iris$id);
		}
	}
}
