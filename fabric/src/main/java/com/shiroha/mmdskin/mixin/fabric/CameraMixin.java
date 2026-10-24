// 负责把 Fabric 相机入口适配到舞台相机与实例化第一人称相机模块。
package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.client.MmdClientRenderRuntime;
import com.shiroha.mmdskin.fabric.compat.YsmCompat;
import com.shiroha.mmdskin.stage.client.camera.MMDCameraController;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    public abstract Entity entity();

    @Shadow
    public abstract boolean isDetached();

    @Shadow
    public abstract float getCameraEntityPartialTicks(DeltaTracker deltaTracker);

    /**
     * 26.3：旧的 {@code Camera.setup(BlockGetter, Entity, boolean, boolean, float)} 已被拆成
     * {@code update(DeltaTracker)}——相机位姿在 {@code alignWithEntity} 里算好，
     * 随后同一个方法里依次算 FOV、视旋转矩阵、裁剪视锥体和投影矩阵。
     *
     * <p>所以注入点选在 {@code alignWithEntity} 之后（而不是整个方法的 TAIL）：
     * 被覆盖的 position/rotation 之后还会被 {@code prepareCullFrustum} 和
     * {@code setupPerspective} 用到，不会出现"相机在别处、裁剪还在原地"的错位。
     */
    @Inject(
            method = "update",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
                    shift = At.Shift.AFTER))
    private void onCameraAligned(DeltaTracker deltaTracker, CallbackInfo callback) {
        Entity entity = this.entity();
        if (entity == null) {
            return;
        }
        float partialTick = this.getCameraEntityPartialTicks(deltaTracker);
        boolean detached = this.isDetached();

        MMDCameraController stageCamera = MMDCameraController.getInstance();
        if (stageCamera.isActive()) {
            stageCamera.checkEscapeKey();
            if (stageCamera.isActive()) {
                stageCamera.updateCamera();
                if (stageCamera.isActive()) {
                    setPosition(stageCamera.getCameraX(), stageCamera.getCameraY(), stageCamera.getCameraZ());
                    setRotation(stageCamera.getCameraYaw(), stageCamera.getCameraPitch());
                }
            }
            return;
        }

        if (entity instanceof LivingEntity living
                && YsmCompat.isYsmModelActive(living)
                && !YsmCompat.isDisableSelfModel()) {
            return;
        }
        var runtime = MmdClientRenderRuntime.currentIfInstalled().orElse(null);
        if (runtime == null) {
            return;
        }
        runtime.firstPerson().camera()
                .resolve(entity, partialTick, detached)
                .ifPresent(pose -> {
                    setPosition(pose.position().x, pose.position().y, pose.position().z);
                    if (pose.applyRotation()) {
                        setRotation(pose.yaw(), pose.pitch());
                    }
                });
    }

    /**
     * 舞台模式接管 FOV。
     *
     * <p>26.3 里 FOV 不再是逐值 uniform：{@code Camera.update} 先调用
     * {@code calculateFov(partialTick)} 把结果写进 {@code Camera.fov} 字段，
     * 再在同一个方法里把这个字段交给 {@code setupPerspective} 建投影矩阵。
     * 因此要接管真正的 3D 视角，必须改 {@code calculateFov} 的返回值——
     * 只改 {@code getFov()} 只会影响 HUD（投影矩阵用的是字段，不是 getFov()）。
     */
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void onCalculateFov(float partialTick, CallbackInfoReturnable<Float> cir) {
        MMDCameraController controller = MMDCameraController.getInstance();
        if (controller.isActive()) {
            cir.setReturnValue(controller.getCameraFov());
        }
    }
}
