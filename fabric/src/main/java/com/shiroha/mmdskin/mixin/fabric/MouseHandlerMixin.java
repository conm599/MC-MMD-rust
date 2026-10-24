package com.shiroha.mmdskin.mixin.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.shiroha.mmdskin.player.ragdoll.RagdollManager;
import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    /** 死亡屏双击判定窗口（毫秒），与 1.20.1 实现保持一致。 */
    @Unique
    private static final long MMD$DOUBLE_CLICK_WINDOW_MS = 350L;

    @Unique
    private long mmd$lastDeathClickMs;

    /**
     * 死亡屏劫持的点击处理：单击自由观战 / 双击复活。
     *
     * <p>必须放在<b>原始输入层</b>（{@code MouseHandler.onButton}）而不是
     * {@code DeathScreen.mouseClicked}：26.3 的输入层会在把事件派发到 Screen 之前
     * 把整个双击序列消费掉，DeathScreen 只收到孤立的一次单击，双击的第二下到不了 Screen
     * （实测：连续两次点击后死亡屏状态毫无变化）。原始输入层每一下按压都必然到达。
     */
    @Unique
    private boolean mmd$deathScreenHijackActive() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gui.screen() instanceof DeathScreen
                && minecraft.level != null
                && minecraft.player != null
                && !minecraft.level.getLevelData().isHardcore()
                && RagdollManager.getInstance().isDeathScreenHijackActive();
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (action == 1 && button.button() == InputConstants.MOUSE_BUTTON_LEFT
                && mmd$deathScreenHijackActive()) {
            long now = System.currentTimeMillis();
            if (now - this.mmd$lastDeathClickMs <= MMD$DOUBLE_CLICK_WINDOW_MS) {
                // 双击：复活。26.3 的 Minecraft 没有 setScreen，用 gui.setScreen(null)。
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.player.respawn();
                minecraft.gui.setScreen(null);
                this.mmd$lastDeathClickMs = 0L;
            } else {
                // 单击：切换自由观战（鼠标环视 / 滚轮缩放）
                RagdollManager.getInstance().toggleDeathCamObserve();
                this.mmd$lastDeathClickMs = now;
            }
            ci.cancel();
            return;
        }

        MMDCameraController controller = MMDCameraController.getInstance();
        if (!controller.shouldBlockInput()) return;

        // 旧版拦的是 GLFW button == 1（右键），对应 26.3 的 button() == 3。
        if (action == 1 && button.button() == 3 && controller.isPlaying()) {
            controller.toggleMouseGrab();
        }
        ci.cancel();
    }

    /**
     * Death cam observe mode: take over mouse look.
     *
     * <p>26.3 note: Screen does NOT declare mouseMoved / mouseScrolled (they are
     * default methods on GuiEventListener), so they cannot be injected into a Screen
     * subclass. MouseHandler.onMove is the real input entry point, and it also owns
     * the accumulated deltas that would otherwise turn the player.
     */
    @Inject(method = "onMove", at = @At("TAIL"))
    private void onDeathCamMove(long window, double xpos, double ypos, double dx, double dy, CallbackInfo ci) {
        RagdollManager ragdoll = RagdollManager.getInstance();
        if (!ragdoll.isDeathCamObserve()) {
            return;
        }
        ragdoll.deathCamMouseMove(dx, dy);
        // Do not let the mouse delta turn the player while free-observing.
        this.accumulatedDX = 0.0;
        this.accumulatedDY = 0.0;
    }

    /** Death cam observe mode: take over scroll zoom. */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onDeathCamScroll(long window, double scrollX, double scrollY, CallbackInfo ci) {
        RagdollManager ragdoll = RagdollManager.getInstance();
        if (!ragdoll.isDeathCamObserve()) {
            return;
        }
        ragdoll.deathCamScroll(scrollY);
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
