// 负责用 NeoForge 26.3 的渲染事件驱动 MMD 的世界帧生命周期。
//
// 26.3 适配要点：Fabric 端的三个世界渲染钩子在 NeoForge 26.3 里有一一对应的事件：
// <ul>
//   <li>Fabric {@code LevelExtractionEvents.END_EXTRACTION}（开帧）
//       → NeoForge {@code ExtractLevelRenderStateEvent}；
//       但本实现改用 {@code GameRendererMixin} 在 {@code GameRenderer.renderLevel} 的 HEAD
//       开帧（语义等价：都早于实体提交）。</li>
//   <li>Fabric {@code LevelRenderEvents.COLLECT_SUBMITS}（第一人称本地模型提交）
//       → NeoForge {@code SubmitCustomGeometryEvent}：
//       该事件在粒子提交之后、不透明提交渲染之前触发，此时
//       {@code SubmitNodeCollector} 还没被消费，手持物仍可提交。</li>
//   <li>Fabric {@code LevelRenderEvents.END_MAIN}（冲刷世界帧）
//       → NeoForge {@code RenderLevelStageEvent.AfterLevel}：
//       该事件在 {@code LevelRenderer.render} 返回之后触发（主 Render Pass 已关闭、
//       没有别的 Pass 处于打开状态），地形与实体已经画进主目标（深度缓冲有效）。</li>
// </ul>
package com.shiroha.mmdskin.neoforge.render;

import com.shiroha.mmdskin.client.MmdClientRenderRuntime;
import com.shiroha.mmdskin.compat.iris.IrisCompatibility;
import com.shiroha.mmdskin.neoforge.compat.YsmCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

public final class NeoForgeRenderAdapter {
    /**
     * 26.3 的 {@code Lightmap} 不再暴露 {@code FULL_BRIGHT}，这里直接用 MC 的全亮 packed light 常量
     * （block = 0xF000，sky = 0xF000）。
     */
    private static final int FULL_BRIGHT_PACKED_LIGHT = 0xF000F0;

    /**
     * 第一人称本地模型提交：与 Fabric 端 {@code LevelRenderEvents.COLLECT_SUBMITS} 同位置。
     */
    @SubscribeEvent
    public void onCollectSubmits(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        MmdClientRenderRuntime runtime = MmdClientRenderRuntime.current();
        LocalPlayer player = minecraft.player;
        // 26.3 的 LevelRenderState.worldPartialTicks 就是 1.21.5 的
        // tickCounter().getGameTimeDeltaPartialTick(false)。
        float partialTick = event.getLevelRenderState().worldPartialTicks;
        runtime.firstPerson().localModels().contribute(
                minecraft, minecraft.gameRenderer.mainCamera(), partialTick,
                runtime.frameId(), runtime.frameDeltaSeconds(),
                player != null && YsmCompat.isYsmActive(player),
                event.getPoseStack(), event.getSubmitNodeCollector());
    }

    /**
     * 冲刷世界帧：与 Fabric 端 {@code LevelRenderEvents.END_MAIN} 同位置。
     */
    @SubscribeEvent
    public void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        if (IrisCompatibility.isShadowPass()) {
            MmdClientRenderRuntime.current().frameQueue().clear();
            return;
        }
        MmdClientRenderRuntime runtime = MmdClientRenderRuntime.current();
        var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        runtime.flushWorldFrame(
                camera.x, camera.y, camera.z, FULL_BRIGHT_PACKED_LIGHT);
    }
}
