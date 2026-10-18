package com.zszq.torque.client.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zszq.torque.client.animation.AnimationBridge;
import com.zszq.torque.client.model.LimbBox;
import com.zszq.torque.client.model.LimbMesh;
import com.zszq.torque.client.model.LimbProfile;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.player.PlayerModelType;
import org.jspecify.annotations.NonNull;

/**
 * 把玩家的四条肢体都画成两截骨骼：大臂 / 大腿是父，小臂 / 小腿挂在关节上。
 *
 * <pre>
 * pushPose
 *   part.translateAndRotate          // 父骨骼，沿用原版动画
 *   画上半截：层 0..4 的 4 段 + 顶端端盖
 *   pushPose
 *     绕关节转 ±p                   // 子骨骼：手肘往前、膝盖往后
 *     画下半截：层 4..6 的 2 段 + 末端端盖
 *   popPose
 * popPose
 * </pre>
 *
 * <p>两条骨骼共用第 {@link LimbProfile#JOINT_LAYER} 层当接缝：父骨骼画到这一层，子骨骼从这一层
 * 接着画。子骨骼坐标系里这一层的坐标是按「转过 p 之后正好落回父骨骼那一层」反解出来的，所以两边
 * 的接缝在空间上完全重合，拼起来看不到缝。
 *
 * <p>手和脚用的是同一套轮廓（{@link LimbProfile}），差别只有贴图位置、盒体的起点，以及
 * {@link LimbProfile.Bend} 给出的弯曲方向。贴图就是皮肤里原版的四肢，不需要额外资源。
 *
 * <p>套在这两条肢体外面的盔甲由 {@code BentArmorLayer} 用同一套轮廓再画一遍，涨一圈、换成盔甲贴图。
 */
public class BentLimbLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    /** 右臂在皮肤上的贴图原点 */
    private static final float RIGHT_ARM_TEX_U = 40.0F;
    private static final float RIGHT_ARM_TEX_V = 16.0F;
    /** 左臂 */
    private static final float LEFT_ARM_TEX_U = 32.0F;
    private static final float LEFT_ARM_TEX_V = 48.0F;
    /** 右腿 */
    private static final float RIGHT_LEG_TEX_U = 0.0F;
    private static final float RIGHT_LEG_TEX_V = 16.0F;
    /** 左腿 */
    private static final float LEFT_LEG_TEX_U = 16.0F;
    private static final float LEFT_LEG_TEX_V = 48.0F;

    /** 手臂的 cube 在竖直方向从 -2 开始（肩在骨骼原点上方 2 像素） */
    private static final float ARM_TOP_Y = -2.0F;
    /** 腿的 cube 从 0 开始（髋就在骨骼原点上） */
    private static final float LEG_TOP_Y = 0.0F;

    /** 隐身但对自己可见时用的透明度，跟原版一致 */
    private static final int INVISIBLE_COLOR = 0x26FFFFFF;

    private final LimbMesh mesh = new LimbMesh();

    public BentLimbLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(@NonNull PoseStack poseStack, @NonNull SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot) {
        if (state.isInvisibleToPlayer) {
            return;
        }

        PlayerModel model = this.getParentModel();
        RenderType renderType = RenderTypes.entityTranslucent(state.skin.body().texturePath());
        int color = state.isInvisible ? INVISIBLE_COLOR : -1;
        int overlayCoords = LivingEntityRenderer.getOverlayCoords(state, 0.0F);

        boolean slim = state.skin.model() == PlayerModelType.SLIM;
        float armWidth = slim ? 3.0F : 4.0F;
        // 右臂的盒子在 x 方向从 -3 起（slim 是 -2），左臂从 -1 起；腿两条都是 -2..2
        float rightArmMinX = slim ? -2.0F : -3.0F;

        // 关节的实际旋转只来自资源包里的动画（没有动画就是伸直的）。
        // 轮廓的弯曲量跟着这个角度走，所以肢体和动画是同一套形变
        float rightElbow = AnimationBridge.joint(state, "rightForearm");
        float leftElbow = AnimationBridge.joint(state, "leftForearm");
        this.drawLimb(
            poseStack, collector, renderType, model.rightArm, rightElbow,
            LimbProfile.of(LimbProfile.toT(Math.abs(rightElbow)), LimbProfile.Bend.FORWARD),
            new LimbBox(RIGHT_ARM_TEX_U, RIGHT_ARM_TEX_V, rightArmMinX, rightArmMinX + armWidth, ARM_TOP_Y),
            lightCoords, overlayCoords, color
        );
        this.drawLimb(
            poseStack, collector, renderType, model.leftArm, leftElbow,
            LimbProfile.of(LimbProfile.toT(Math.abs(leftElbow)), LimbProfile.Bend.FORWARD),
            new LimbBox(LEFT_ARM_TEX_U, LEFT_ARM_TEX_V, -1.0F, -1.0F + armWidth, ARM_TOP_Y),
            lightCoords, overlayCoords, color
        );

        float rightKneeRotation = AnimationBridge.joint(state, "rightForeleg");
        this.drawLimb(
            poseStack, collector, renderType, model.rightLeg, rightKneeRotation,
            LimbProfile.of(LimbProfile.toT(Math.abs(rightKneeRotation)), LimbProfile.Bend.BACKWARD),
            new LimbBox(RIGHT_LEG_TEX_U, RIGHT_LEG_TEX_V, -2.0F, 2.0F, LEG_TOP_Y),
            lightCoords, overlayCoords, color
        );
        float leftKneeRotation = AnimationBridge.joint(state, "leftForeleg");
        this.drawLimb(
            poseStack, collector, renderType, model.leftLeg, leftKneeRotation,
            LimbProfile.of(LimbProfile.toT(Math.abs(leftKneeRotation)), LimbProfile.Bend.BACKWARD),
            new LimbBox(LEFT_LEG_TEX_U, LEFT_LEG_TEX_V, -2.0F, 2.0F, LEG_TOP_Y),
            lightCoords, overlayCoords, color
        );
    }

    /**
     * 一条肢体：先在父骨骼里画上半截，再挂上关节轴画下半截。
     * {@code jointRotation} 是关节绕 +X 的弧度（负角往前抬 = 手肘，正角往后抬 = 膝盖）。
     */
    private void drawLimb(PoseStack poseStack, SubmitNodeCollector collector, RenderType renderType, ModelPart part, float jointRotation, LimbProfile profile, LimbBox box, int lightCoords, int overlayCoords, int color) {
        poseStack.pushPose();
        part.translateAndRotate(poseStack);

        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) ->
            this.mesh.renderParent(buffer, pose, profile, box, lightCoords, overlayCoords, color));

        poseStack.pushPose();
        poseStack.translate(0.0F, box.jointY() * LimbMesh.PIXEL, 0.0F);
        poseStack.mulPose(Axis.XP.rotation(jointRotation));
        poseStack.translate(0.0F, -box.jointY() * LimbMesh.PIXEL, 0.0F);

        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) ->
            this.mesh.renderChild(buffer, pose, profile, box, lightCoords, overlayCoords, color));

        poseStack.popPose();
        poseStack.popPose();
    }
}
