package com.zszq.torque.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 鞘翅滑翔时，原版 {@code AvatarRenderer#setupRotations} 会先把整个模型绕 X 转
 * {@code -90° - xRot}，把身体放平并跟随视线俯仰。
 *
 * <p>模组的「身体放平」由 {@code animation.fly} 里 {@code body} 的关键帧负责（Blockbench 里摆的
 * -85°），两层叠加会接近倒立，所以这里把原版那次 X 旋转改成只保留视线俯仰
 * {@code -xRot}，放平交给动画。它后面的 {@code flyingYRot} 保留——角色仍然朝运动方向。
 *
 * <p>转弯时再给整个模型加一个绕前后轴的侧倾（像飞机压坡度）：用 {@code flyingYRot}
 * （运动方向与视线的水平夹角）算目标坡度，直线飞行回正，转弯时夹角越大坡度越深。
 *
 * <p>俯仰 / 侧倾的旋转中心从脚部挪到腰部，模型不会被甩出画面中心；最后再整体下压一点，
 * 让动画绕腰部放平后的模型中心和实体 / 烟花粒子对齐。
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    /** 视线与运动方向每差 1° 对应的侧倾角 */
    private static final float BANK_PER_DEGREE = 1.6F;
    /** 最大侧倾角，避免快速甩视角时翻过去 */
    private static final float MAX_BANK = 90.0F;
    /** 侧倾的平滑系数：越小越软 */
    private static final float BANK_SMOOTH = 0.3F;
    /** 俯仰 / 侧倾的旋转中心高度（格）：绕腰部转，模型不会被甩出画面中心 */
    private static final float MODEL_CENTER_Y = 1.0F;
    /** 滑翔姿态整体下移量（格）：动画绕腰部放平，模型中心比原版绕脚部放平高，压回去和实体 / 粒子对齐 */
    private static final float MODEL_DROP = 0.85F;

    /** 每个玩家的当前侧倾角，按 id 分开记录 */
    private static final Map<Integer, float[]> BANKING = new HashMap<>();

    @Redirect(
        method = "setupRotations",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;",
            ordinal = 0
        )
    )
    private Quaternionf torque$fallFlyingTilt(
        Axis axis,
        float degrees,
        AvatarRenderState state,
        PoseStack poseStack,
        float bodyRot,
        float entityScale
    ) {
        float scale = state.fallFlyingScale();
        float pitch = -state.xRot * scale;
        float roll = bank(state);

        // 原调用是 poseStack.mulPose(Axis.XP.rotationDegrees(...))；这里自己完成旋转并返回单位四元数。
        // 旋转中心从脚部挪到腰部，俯仰和侧倾时人物保持在画面中间，而不是被甩出去。
        poseStack.translate(0.0F, MODEL_CENTER_Y, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
        poseStack.translate(0.0F, -MODEL_CENTER_Y, 0.0F);

        // 动画的放平是绕腰部转的，模型中心比原版（绕脚部放平）高一截；
        // 往下压回去，让模型和实体位置 / 烟花粒子对齐。
        poseStack.translate(0.0F, -MODEL_DROP, 0.0F);

        return new Quaternionf();
    }

    /**
     * 用 {@code flyingYRot}（运动方向与视线的水平夹角）算坡度：直线飞行时夹角为 0，身体回正；
     * 转弯时运动方向落后于视线，夹角越大坡度越深。按帧做指数平滑，避免切换时跳变。
     */
    private static float bank(AvatarRenderState state) {
        float target = 0.0F;
        if (state.shouldApplyFlyingYRot) {
            target = Mth.clamp(
                -(float) Math.toDegrees(state.flyingYRot) * BANK_PER_DEGREE,
                -MAX_BANK,
                MAX_BANK
            );
        }

        float[] slot = BANKING.computeIfAbsent(state.id, id -> new float[]{0.0F});
        slot[0] = Mth.lerp(BANK_SMOOTH, slot[0], target);
        return slot[0];
    }
}
