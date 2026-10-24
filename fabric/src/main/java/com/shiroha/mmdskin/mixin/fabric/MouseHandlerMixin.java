package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MouseHandler Mixin — 舞台模式下拦截鼠标点击和视角旋转
 *
 * <p>26.3 起 GLFW 换成 SDL，鼠标按键回调由 {@code onPress(long, int, int, int)}
 * 换成了 {@code onButton(long, MouseButtonInfo, int)}：
 * 按键编号从 GLFW 的 0=左 1=右 2=中，变成 26.3 的 1=左 2=中 3=右。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onStageMousePress(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        MMDCameraController controller = MMDCameraController.getInstance();
        if (!controller.shouldBlockInput()) return;

        // 旧版拦的是 GLFW button == 1（右键），对应 26.3 的 button() == 3。
        if (action == 1 && button.button() == 3 && controller.isPlaying()) {
            controller.toggleMouseGrab();
        }
        ci.cancel();
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onStageTurnPlayer(CallbackInfo ci) {
        if (MMDCameraController.getInstance().shouldBlockInput()) {
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            ci.cancel();
        }
    }
}
