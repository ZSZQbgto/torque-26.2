package com.zszq.torque.client.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * 鞘翅层：在渲染前把玩家模型的 {@code body} 变换叠上去。
 *
 * <p>原版 {@code WingsLayer} 直接画在实体空间，不经过 {@code body} 部件。原版滑翔靠
 * {@code AvatarRenderer#setupRotations} 把整个模型放平，鞘翅顺带一起转；模组把放平和微动
 * 交给 {@code animation.fly} 的 {@code body} 关键帧后，鞘翅收不到这些变换，就会像钉在背上一样
 * 不随躯干晃动。这里先 {@code body.translateAndRotate}，鞘翅就会跟着躯干一起呼吸、摇晃。
 *
 * <p>替换方式和 {@link BentCapeLayer} 一样：在 {@code TorqueClient#onAddLayers} 里找到原版层，
 * 用同一个 {@code EquipmentLayerRenderer} 建一个子类顶上去。
 */
public class TorqueWingsLayer extends WingsLayer<AvatarRenderState, PlayerModel> {
    public TorqueWingsLayer(
        RenderLayerParent<AvatarRenderState, PlayerModel> renderer,
        EntityModelSet modelSet,
        EquipmentLayerRenderer equipmentRenderer
    ) {
        super(renderer, modelSet, equipmentRenderer);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot) {
        poseStack.pushPose();
        this.getParentModel().body.translateAndRotate(poseStack);
        super.submit(poseStack, collector, lightCoords, state, yRot, xRot);
        poseStack.popPose();
    }
}
