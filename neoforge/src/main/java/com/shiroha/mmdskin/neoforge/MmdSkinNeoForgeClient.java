/** 文件职责：完成 NeoForge 客户端配置、运行时与平台事件入口初始化。 */
package com.shiroha.mmdskin.neoforge;

import com.shiroha.mmdskin.MmdSkin;
import com.shiroha.mmdskin.MmdSkinClient;
import com.shiroha.mmdskin.client.gpu.MmdRenderPipelines;
import com.shiroha.mmdskin.neoforge.config.MmdSkinConfig;
import com.shiroha.mmdskin.neoforge.register.MmdSkinRegisterClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/**
 * NeoForge 客户端入口。
 *
 * <p>26.3 适配：{@code @EventBusSubscriber} 不再有 {@code bus} 属性，
 * 事件自己实现 {@code IModBusEvent} 就走 mod 总线、否则走游戏总线。
 *
 * <p>1.21.x 的 {@code RegisterRenderStateModifiersEvent} 在 26.3 已被删除
 * （NeoForge 不再提供 render state modifier 机制），
 * 所以 MMD 快照改由 mixin 直接挂在原版 {@code LivingEntityRenderState} 上。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = MmdSkin.MOD_ID)
public final class MmdSkinNeoForgeClient {
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        MmdSkinRegisterClient.onRegisterKeyMappings(event);
    }

    @SubscribeEvent
    public static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        MmdSkinRegisterClient.onRegisterEntityRenderers(event);
    }

    @SubscribeEvent
    public static void onRegisterRenderPipelines(RegisterRenderPipelinesEvent event) {
        MmdRenderPipelines.all().forEach(event::registerPipeline);
        MmdRenderPipelines.activateRegisteredPipelines();
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        MmdSkinConfig.init();
        MmdSkinClient.initClient();
        MmdSkinRegisterClient.Register();
    }
}
