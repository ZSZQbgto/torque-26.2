package com.zszq.torque.client.model;

import com.zszq.torque.client.animation.AnimationBridge;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * 玩家模型：把原版的四条肢体藏掉，改由 {@link LimbMesh} 按「大臂 / 小臂」「大腿 / 小腿」绘制。
 *
 * <p>袖口和裤腿（{@code *_sleeve} / {@code *_pants}）都是对应肢体的子 part，肢体不可见时
 * 它们也不会被画出来，不会在关节旁边留下一条悬空的装饰。
 *
 * <p>{@code AvatarRenderer#renderHand} 在第一人称渲染前会把手臂的 {@code visible} 重新置回 true，
 * 所以这里藏掉肢体只影响第三人称，手持手臂照常显示。
 *
 * <p>走路时这里顺便把资源包里的 {@code animation.walk} 混到躯干 / 头和四条大臂大腿上
 * （见 {@code AnimationBridge}）：这些部件虽然不直接画，但它们的旋转是 {@code BentLimbLayer}
 * 画弯曲肢体时的坐标来源，所以动作能顺着传下去。小臂 / 小腿没有对应的 part，由那一层自己处理。
 */
public class TorquePlayerModel extends PlayerModel {
    public TorquePlayerModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(AvatarRenderState state) {
        super.setupAnim(state);
        // super 会按 render state 设置可见性，所以必须在它之后覆盖
        this.rightArm.visible = false;
        this.leftArm.visible = false;
        this.rightLeg.visible = false;
        this.leftLeg.visible = false;

        this.applyWalkAnimation(state);
    }

    /** 把走 / 跑动画混到（已经由 {@code super} 摆好的）部件旋转上；两个动画的切换在那一层混合 */
    private void applyWalkAnimation(AvatarRenderState state) {
        // 先把原版蹲伏给的位移清掉，否则会和动画里的蹲姿叠在一起
        resetCrouchOffsets(state);

        // 躯干和头交给共享逻辑，盔甲模型用的是同一份，头盔才不会和头脱开
        AnimationBridge.applyBodyAndHead(this.body, this.head, state);

        // 四肢：动画直接摆到部件上。Blockbench 项目和模型是同一棵树（四肢与 body 平级），
        // 所以这里不需要再补父子关系；这些部件本身是隐藏的，但它们的姿态是 BentLimbLayer 画弯曲肢体的坐标来源
        AnimationBridge.blendRotation(this.rightArm, state, "rightArm");
        AnimationBridge.blendPosition(this.rightArm, state, "rightArm");
        AnimationBridge.blendRotation(this.leftArm, state, "leftArm");
        AnimationBridge.blendPosition(this.leftArm, state, "leftArm");
        AnimationBridge.blendRotation(this.rightLeg, state, "rightLeg");
        AnimationBridge.blendPosition(this.rightLeg, state, "rightLeg");
        AnimationBridge.blendRotation(this.leftLeg, state, "leftLeg");
        AnimationBridge.blendPosition(this.leftLeg, state, "leftLeg");
    }

    /**
     * 清掉原版蹲伏给部件加的位移。
     *
     * <p>{@code HumanoidModel#setupAnim} 在 {@code isCrouching} 时会自己加一整套位移：
     * {@code body.y += 3.2}、{@code head.y += 4.2}、两条手臂各 {@code y += 3.2}、两条腿各 {@code z += 4}。
     * 另外 {@code AvatarRenderer#getRenderOffset} 还会把整个模型再往下挪 2 像素。
     * 模组的蹲姿完全由动画决定，这些叠上去就会变成「body 比腿根低一截、腿根从身后伸出来、脚插进地面」。
     * 所以这里把部件位置退回初始值，再把整体那 2 像素一并抵消掉。
     */
    private void resetCrouchOffsets(AvatarRenderState state) {
        float lift = state.isCrouching ? VANILLA_CROUCH_RENDER_OFFSET : 0.0F;
        for (ModelPart part : List.of(this.body, this.head, this.rightArm, this.leftArm, this.rightLeg, this.leftLeg)) {
            PartPose initial = part.getInitialPose();
            part.x = initial.x();
            part.y = initial.y() - lift;
            part.z = initial.z();
        }
    }

    /** {@code AvatarRenderer#getRenderOffset} 蹲伏时把整个模型下移的像素数 */
    private static final float VANILLA_CROUCH_RENDER_OFFSET = 2.0F;
}
