package com.zszq.torque.client.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zszq.torque.client.animation.AnimationBridge;
import com.zszq.torque.client.model.CapeMesh;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/**
 * 披风：从上到下拆成 4 : 5 : 7 三段，每段用 {@link CapeMesh} 单独画。
 *
 * <p>尺寸是原版披风的 10 宽 × 16 高 × 1 厚：三段挂在同一个挂点 (0, 0, 2) 上，pivot 一节接一节
 * （{@code cape} 24、{@code capemid} 20、{@code capedown} 15，换成模型坐标就是各段顶端 0 / 4 / 9），
 * 所以它们是父子链：父段转，子段跟着走。
 *
 * <p>贴图用 Minecraft 原版披风那套单方块布局（64 × 32 的玩家披风 PNG）：三段的本体分别取
 * v 1..5、5..10、10..17，列固定为 0/1/11/12/22——不需要额外资源，也不受自定义 UV 的影响。
 *
 * <p>这个层替换掉原版的 {@code CapeLayer}，登场条件也照抄：不隐身、披风开着、皮肤带披风，
 * 而且胸甲没有 {@code WINGS} 层（鞘翅）；胸甲穿的是正经盔甲时整体往外挪一点，免得和胸甲穿模。
 */
public class BentCapeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    /** 三段各自的长度（像素）：4 + 5 + 7 = 16，正好是原版披风的高度 */
    private static final float[] SEGMENT_LENGTHS = {4.0F, 5.0F, 7.0F};
    private static final int SEGMENT_COUNT = SEGMENT_LENGTHS.length;
    /** 三段本体在披风贴图上的起始行：原版从 1 开始（第 0 行是顶端的端盖） */
    private static final float[] SEGMENT_V_FROM = {1.0F, 5.0F, 10.0F};
    /** 三段的骨骼名，和 Blockbench 里的组名一致 */
    private static final String[] SEGMENT_BONES = {"cape", "capemid", "capedown"};

    private final CapeMesh mesh = new CapeMesh();
    private final EquipmentAssetManager equipmentAssets;

    public BentCapeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EquipmentAssetManager equipmentAssets) {
        super(parent);
        this.equipmentAssets = equipmentAssets;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot) {
        if (state.isInvisible || !state.showCape || state.skin.cape() == null) {
            return;
        }

        if (this.hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.WINGS)) {
            return;
        }

        RenderType renderType = RenderTypes.entitySolid(state.skin.cape().texturePath());

        poseStack.pushPose();
        // 披风挂在躯干上，挂点就是原版 cape 部件的 (0, 0, 2)
        this.getParentModel().body.translateAndRotate(poseStack);
        poseStack.translate(0.0F, 0.0F, 2.0F * CapeMesh.PIXEL);
        if (this.hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.HUMANOID)) {
            poseStack.translate(0.0F, -0.053125F, 0.06875F);
        }
        AnimationBridge.applyCapeBase(poseStack, state);

        // 父子链：每段的 pivot 就在上一段的底端（Blockbench 里 capemid 20、capedown 15），
        // 所以先顺着长度挪到本段起点，再把本段的旋转叠到**同一个** PoseStack 上。旋转必须累积，
        // 子段才会跟着父段走——这里千万不能 push/pop，否则三段就是各转各的，链子会断开
        poseStack.pushPose();
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            if (i > 0) {
                poseStack.translate(0.0F, SEGMENT_LENGTHS[i - 1] * CapeMesh.PIXEL, 0.0F);
            }

            AnimationBridge.rotateCapeSegment(poseStack, state, SEGMENT_BONES[i]);
            float length = SEGMENT_LENGTHS[i];
            float vFrom = SEGMENT_V_FROM[i];
            collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) ->
                this.mesh.render(buffer, pose, length, vFrom, lightCoords, OverlayTexture.NO_OVERLAY, -1));
        }
        poseStack.popPose();

        poseStack.popPose();
    }

    /** 和原版 {@code CapeLayer} 一样：这件装备在这个槽位上有没有这一层 */
    private boolean hasLayer(ItemStack itemStack, EquipmentClientInfo.LayerType layerType) {
        Equippable equippable = itemStack.get(DataComponents.EQUIPPABLE);
        if (equippable != null && !equippable.assetId().isEmpty()) {
            return !this.equipmentAssets.get(equippable.assetId().get()).getLayers(layerType).isEmpty();
        }

        return false;
    }
}
