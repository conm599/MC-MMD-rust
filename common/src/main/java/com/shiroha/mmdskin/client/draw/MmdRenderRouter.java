// 负责决定 MMD 接管或原版回退并把模型提交到 Frame Queue。
package com.shiroha.mmdskin.client.draw;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.client.MmdClientRenderRuntime;
import com.shiroha.mmdskin.client.frame.MmdRenderSnapshot;
import com.shiroha.mmdskin.client.frame.ModelTransform;
import com.shiroha.mmdskin.client.gpu.MmdRenderPipelines;
import com.shiroha.mmdskin.compat.iris.IrisCompatibility;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.player.ragdoll.RagdollManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;

public final class MmdRenderRouter {
    private MmdRenderRouter() {
    }

    public static Result route(MmdRenderSnapshot extractedSnapshot, PoseStack poseStack,
                               int packedLight, boolean compatibilityConflict) {
        return route(extractedSnapshot, poseStack, null, null, packedLight, compatibilityConflict);
    }

    public static Result routePlayer(MmdRenderSnapshot extractedSnapshot, AvatarRenderState playerState,
                                     PoseStack poseStack, SubmitNodeCollector buffers,
                                     int packedLight, boolean compatibilityConflict) {
        return route(extractedSnapshot, poseStack, playerState, buffers, packedLight, compatibilityConflict);
    }

    private static Result route(MmdRenderSnapshot extractedSnapshot, PoseStack poseStack,
                                AvatarRenderState playerState, SubmitNodeCollector buffers,
                                int packedLight, boolean compatibilityConflict) {
        if (extractedSnapshot == null || compatibilityConflict || IrisCompatibility.isShadowPass()
                || !MmdRenderPipelines.isReady()) {
            return Result.FALLTHROUGH;
        }
        if (!extractedSnapshot.rendersBody() && !extractedSnapshot.glowing()) {
            return Result.FALLTHROUGH;
        }
        // 尸体模型就绪后，本体不再绘制——否则死亡瞬间会同时看到本体和尸体两重身影。
        // （1.20.1 是在 MobReplacementRenderer / PlayerMixinDelegate 里做同样的判定。）
        if (RagdollManager.getInstance().shouldSuppressEntityRender(extractedSnapshot.entityId())) {
            return Result.SUPPRESSED;
        }

        MmdClientRenderRuntime runtime = MmdClientRenderRuntime.current();
        var lease = runtime.acquire(extractedSnapshot.modelKey()).orElse(null);
        if (lease == null) {
            return Result.FALLTHROUGH;
        }
        if (runtime.firstPerson().defersWorldRoute(extractedSnapshot.entityId())) {
            lease.close();
            return Result.DEFERRED_LOCAL;
        }
        boolean queued = false;
        try {
            var model = lease.instance();
            Matrix4f modelMatrix = new Matrix4f(poseStack.last().pose())
                    .rotateY((float) Math.toRadians(-extractedSnapshot.pose().bodyYaw()));
            MmdRenderSnapshot snapshot = extractedSnapshot.withRenderTransform(
                    ModelTransform.from(modelMatrix), packedLight);
            runtime.frameUpdater().updateOnce(
                    runtime.frameId(), model, snapshot, runtime.frameDeltaSeconds());
            PipelineVariant pipeline = choosePipeline(snapshot);
            MmdDrawRequest request = new MmdDrawRequest(
                    model, snapshot, pipeline,
                    snapshot.visibility() == MmdRenderSnapshot.Visibility.TRANSLUCENT);
            if (snapshot.context() == MmdRenderSnapshot.Context.INVENTORY) {
                runtime.inventory().enqueue(request, lease);
            } else {
                runtime.frameQueue().enqueue(request, lease);
            }
            queued = true;
            if (playerState != null && buffers != null) {
                MmdHeldItemRenderer.render(playerState, model, poseStack, buffers,
                        packedLight, snapshot.pose().bodyYaw());
            }
            return Result.QUEUED;
        } finally {
            if (!queued) {
                lease.close();
            }
        }
    }

    private static PipelineVariant choosePipeline(MmdRenderSnapshot snapshot) {
        if (snapshot.glowing() && !snapshot.rendersBody()) {
            return PipelineVariant.GLOWING;
        }
        if (ConfigManager.isToonRenderingEnabled()) {
            return PipelineVariant.TOON;
        }
        return snapshot.visibility() == MmdRenderSnapshot.Visibility.TRANSLUCENT
                ? PipelineVariant.TRANSLUCENT
                : PipelineVariant.OPAQUE;
    }

    public enum Result {
        /** 交回原版渲染。 */
        FALLTHROUGH,
        /** MMD 模型已入队，原版主体不再绘制。 */
        QUEUED,
        /** 本地第一人称推迟到专门的通道渲染。 */
        DEFERRED_LOCAL,
        /** 尸体已接管，本体整体不画（原版主体也不画）。 */
        SUPPRESSED
    }
}
