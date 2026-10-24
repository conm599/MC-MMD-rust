package com.shiroha.mmdskin.mixin.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.shiroha.mmdskin.client.deathscreen.DeathScreenButtons;
import com.shiroha.mmdskin.player.ragdoll.RagdollManager;
import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
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

    /** 鼠标在窗口里的原始像素坐标（GUI 坐标要自己换算）。 */
    @Shadow private double xpos;
    @Shadow private double ypos;

    /** 死亡屏按钮的命中判定用的 GUI 坐标（与 Screen 的 mouseX/mouseY 同一套换算）。 */
    @Unique
    private double mmd$scaledMouseX(Minecraft minecraft, net.minecraft.client.gui.screens.Screen screen) {
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        double scaled = window.getGuiScaledWidth();
        return scaled <= 0.0D ? this.xpos : this.xpos * (double) screen.width / scaled;
    }

    @Unique
    private double mmd$scaledMouseY(Minecraft minecraft, net.minecraft.client.gui.screens.Screen screen) {
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        double scaled = window.getGuiScaledHeight();
        return scaled <= 0.0D ? this.ypos : this.ypos * (double) screen.height / scaled;
    }

    /**
     * 死亡屏劫持的点击处理：右下角「自由观战」/「复活」两个按钮。
     *
     * <p>必须放在<b>原始输入层</b>（{@code MouseHandler.onButton}）而不是
     * {@code DeathScreen.mouseClicked}：26.3 的输入层会在把事件派发到 Screen 之前
     * 把点击消费掉（Screen 里的按钮拿不到点击）。原始输入层每一下按压都必然到达。
     */

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (action == 1 && button.button() == InputConstants.MOUSE_BUTTON_LEFT
                && DeathScreenButtons.isHijackedDeathScreen(Minecraft.getInstance())) {
            // 死亡屏劫持：右下角两个按钮（自由观战 / 复活），点哪个做哪个。
            // 26.3 的输入层会在把事件派发到 Screen 之前消费掉整个点击序列，
            // 所以判定必须留在原始输入层，Screen 里的按钮拿不到点击。
            Minecraft minecraft = Minecraft.getInstance();
            net.minecraft.client.gui.screens.Screen screen = minecraft.gui.screen();
            int index = DeathScreenButtons.indexAt(
                    mmd$scaledMouseX(minecraft, screen), mmd$scaledMouseY(minecraft, screen),
                    screen.width, screen.height);
            if (index >= 0) {
                if (DeathScreenButtons.actionOf(index) == DeathScreenButtons.Action.FREE_LOOK) {
                    RagdollManager.getInstance().toggleDeathCamObserve();
                } else {
                    minecraft.player.respawn();
                    minecraft.gui.setScreen(null);
                }
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
