package com.zszq.torque.client.model;

import com.zszq.torque.client.animation.AnimationBridge;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * 玩家盔甲层用的模型：只画头盔和躯干，四条肢体全部藏掉。
 *
 * <p>原版盔甲层把整件盔甲铺在 {@code HumanoidModel} 的直筒盒子上，手臂和腿藏掉之后就什么都不剩，
 * 而直筒的盒子也套不到按 {@link LimbProfile} 弯过去的肢体上。所以四肢改由
 * {@code BentArmorLayer} 按同一套轮廓重画，这里只留头和身体。
 *
 * <p>宽窄皮肤共用同一套网格——原版 {@code PLAYER_SLIM_ARMOR} 挂的就是 {@code PLAYER_ARMOR} 那份定义，
 * 手臂反正藏掉了，所以 {@code slim} 一律给 false。
 */
public class TorqueArmorModel extends PlayerModel {
    public TorqueArmorModel(ModelPart root) {
        super(root, false);
    }

    @Override
    public void setupAnim(AvatarRenderState state) {
        super.setupAnim(state);
        // super 会按 render state 把四肢重新置回可见，所以必须在它之后覆盖
        this.rightArm.visible = false;
        this.leftArm.visible = false;
        this.rightLeg.visible = false;
        this.leftLeg.visible = false;

        // 清掉原版蹲伏给部件加的位移，蹲姿完全交给动画
        this.resetCrouchOffsets(state);

        // 和玩家模型共用同一份「躯干 + 头」逻辑：头盔才会跟着头一起转、一起前倾，不会浮在原地
        AnimationBridge.applyBodyAndHead(this.body, this.head, state);

        // 四肢虽然由 BentArmorLayer 重画，但画的坐标来源就是这四个部件的姿态，
        // 所以这里也得按同一份动画摆一遍——不然蹲下时护腿和靴子会直挺挺地留在原地
        AnimationBridge.blendRotation(this.rightArm, state, "rightArm");
        AnimationBridge.blendPosition(this.rightArm, state, "rightArm");
        AnimationBridge.addSwingArm(this.rightArm, state, "rightArm");
        AnimationBridge.blendRotation(this.leftArm, state, "leftArm");
        AnimationBridge.blendPosition(this.leftArm, state, "leftArm");
        AnimationBridge.addSwingArm(this.leftArm, state, "leftArm");
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
