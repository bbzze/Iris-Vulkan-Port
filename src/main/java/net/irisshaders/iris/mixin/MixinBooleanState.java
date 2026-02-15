package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.irisshaders.iris.gl.BooleanStateExtended;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.BooleanState.class)
public class MixinBooleanState implements BooleanStateExtended {
	@Shadow
	public boolean enabled;
	@Shadow
	@Final
	private int state;
	@Unique
	private boolean stateUnknown;

	@Inject(method = "setEnabled", at = @At("HEAD"), cancellable = true)
	private void iris$setUnknownState(boolean enable, CallbackInfo ci) {
		if (stateUnknown) {
			ci.cancel();
			this.enabled = enable;
			stateUnknown = false;
			if (enable) {
				IrisRenderSystem.glEnable(this.state);
			} else {
				IrisRenderSystem.glDisable(this.state);
			}
		}
	}

	@Override
	public void setUnknownState() {
		stateUnknown = true;
	}
}
