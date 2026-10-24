// 负责劫持死亡界面：透明屏（只留一行提示）+ 单击自由观战 + 双击复活。
//
// 26.3 适配要点：
//   1. 死亡屏的绘制入口从 1.20.1 的 render(GuiGraphics,...) 换成了
//      extractRenderState(GuiGraphicsExtractor, int, int, float)；
//      背景（红色渐晕）是另一个入口 extractBackground(...)，两个都要覆盖才能"透明"。
//   2. 居中文字用 GuiGraphicsExtractor.centeredText(Font, Component, x, y, color)，
//      x 是水平中心坐标（等价于 1.20.1 的 drawCenteredString）。
//   3. 观战模式的"鼠标环视 / 滚轮缩放"不放在这里：26.3 的 Screen 不声明 mouseMoved /
//      mouseScrolled（它们是 GuiEventListener 的接口默认方法，mixin 无法注入），
//      所以改在 MouseHandlerMixin 里接管 MouseHandler 的 onMove / onScroll。
//   4. **点击判定也不放在这里**：26.3 的输入层会在派发到 Screen 之前把整个双击序列
//      消费掉（实测 DeathScreen.mouseClicked 只会收到孤立的一次单击，双击的第二下
//      根本到不了 Screen）。所以点击/双击判定已下沉到 MouseHandlerMixin 的
//      MouseHandler.onButton —— 原始输入层，每一下按压都必然到达。
package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.player.ragdoll.RagdollManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 死亡界面劫持：透明死亡屏（只留一行提示）。
 * 困难模式（hardcore）完全放行原版行为。
 *
 * <p>点击（单击观战 / 双击复活）在 {@code MouseHandlerMixin} 里处理。
 */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin {

    @Unique
    private DeathScreen mmd$self() {
        return (DeathScreen) (Object) this;
    }

    @Unique
    private boolean mmd$hijackActive() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null
                && minecraft.player != null
                && !minecraft.level.getLevelData().isHardcore()
                && RagdollManager.getInstance().isDeathScreenHijackActive();
    }

    /**
     * 跳过原版死亡屏的红色渐晕背景。
     *
     * <p>26.3 里背景与控件是两条独立的提取路径
     * （{@code Screen.extractRenderStateWithTooltipAndSubtitles} 先后调用
     * {@code extractBackground} 与 {@code extractRenderState}），只覆盖后者不够。
     */
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void mmd$onExtractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                         float partialTick, CallbackInfo ci) {
        if (mmd$hijackActive()) {
            ci.cancel();
        }
    }

    /**
     * 跳过原版标题 / 死因 / 分数 / 按钮，只画一行操作提示。
     */
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void mmd$onExtractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                          float partialTick, CallbackInfo ci) {
        if (!mmd$hijackActive()) {
            return;
        }
        ci.cancel();

        DeathScreen self = mmd$self();
        RagdollManager ragdoll = RagdollManager.getInstance();
        String key = ragdoll.isDeathCamObserve()
                ? "gui.mmdskin.death_screen_hint_observe"
                : "gui.mmdskin.death_screen_hint";
        graphics.centeredText(self.getFont(), Component.translatable(key),
                self.width / 2, self.height / 4 + 40, 0xFFFFFFFF);
    }
}
