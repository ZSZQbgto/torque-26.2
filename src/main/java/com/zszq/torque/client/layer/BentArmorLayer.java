package com.zszq.torque.client.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zszq.torque.client.model.LimbBox;
import com.zszq.torque.client.model.LimbMesh;
import com.zszq.torque.client.animation.AnimationBridge;
import com.zszq.torque.client.model.LimbProfile;
import java.util.List;
import java.util.Objects;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 给 {@link BentLimbLayer} 画出来的弯曲肢体套上盔甲外壳。
 *
 * <p>原版盔甲层用的是 {@code HumanoidModel} 的直筒盒子，套在弯过去的手臂 / 腿上会露出一截直的，
 * 所以那边只留头盔和躯干（模型换成 {@code TorqueArmorModel}），手臂和腿上的外壳改由这里重画：
 * 轮廓、关节角、接缝全部复用 {@link LimbProfile} / {@link LimbMesh}，只是每一层都沿轮廓的外法线
 * 等距离开一圈（胸甲 1 像素、护腿 0.4、靴子 0.9），贴图换成 64 × 32 的盔甲图。涨出来的数值就是原版
 * {@code CubeDeformation} 的 {@code OUTER_ARMOR_DEFORMATION} / {@code INNER_ARMOR_DEFORMATION}，
 * 腿再各减 0.1（{@code HumanoidModel#createBaseArmorMesh} 里的 {@code extend(-0.1F)}）。
 *
 * <p>贴图怎么取、染不染色、附魔光效、盔甲纹饰，都按 {@code EquipmentLayerRenderer} 的规矩走：一件装备的
 * {@code EquipmentAsset} 里有几层就画几层，可染色的层乘上染料颜色，剩下的交给
 * {@link IClientItemExtensions} 的扩展点，跟原版盔甲层保持一致。
 *
 * <p>几个原版刻意如此、这里也照做的细节：
 * <ul>
 *   <li>盔甲不分宽窄皮肤，slim 皮肤套的也是 4 像素宽的那套盒子——原版 {@code PLAYER_SLIM_ARMOR}
 *       挂的就是 {@code PLAYER_ARMOR} 那份定义；</li>
 *   <li>靴子画的还是整条腿的盒子，贴图在靴口以上本来就是透明的，所以只看得见脚踝那一圈；</li>
 *   <li>隐身只藏皮肤，盔甲照画，和原版一样；</li>
 *   <li>只认成年人的贴图布局，玩家不会是婴儿，所以不走 {@code HUMANOID_BABY} 那一套。</li>
 * </ul>
 *
 * <p>另外，自定义几何体没法参与原版的描边（发光）通道，所以发光状态下盔甲外壳没有描边。
 */
public class BentArmorLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    /** 胸甲：外甲，四边各涨 1 像素 */
    private static final float PLATE_INFLATE = 1.0F;
    /** 护腿：内甲 0.5 像素，腿再缩 0.1 */
    private static final float LEGGING_INFLATE = 0.4F;
    /** 靴子：外甲 1.0 像素，腿再缩 0.1 */
    private static final float BOOT_INFLATE = 0.9F;

    /** 盔甲模型里手臂的贴图原点，两条手臂共用一块，左边靠镜像 */
    private static final float ARM_TEX_U = 40.0F;
    private static final float ARM_TEX_V = 16.0F;
    /** 腿的贴图原点，同样左右共用 */
    private static final float LEG_TEX_U = 0.0F;
    private static final float LEG_TEX_V = 16.0F;

    /** 盔甲手臂的盒子：宽 4（slim 也是 4），肩在骨骼原点上方 2 像素 */
    private static final float ARM_WIDTH = 4.0F;
    private static final float RIGHT_ARM_MIN_X = -3.0F;
    private static final float LEFT_ARM_MIN_X = -1.0F;
    private static final float ARM_TOP_Y = -2.0F;

    /** 腿的盒子：髋就在骨骼原点上 */
    private static final float LEG_MIN_X = -2.0F;
    private static final float LEG_MAX_X = 2.0F;
    private static final float LEG_TOP_Y = 0.0F;

    private final LimbMesh mesh = new LimbMesh();
    private final EquipmentAssetManager equipmentAssets;
    private final TextureAtlas trimAtlas;

    public BentArmorLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityRendererProvider.Context context) {
        super(parent);
        this.equipmentAssets = context.getEquipmentAssets();
        this.trimAtlas = context.getAtlas(AtlasIds.ARMOR_TRIMS);
    }

    @Override
    public void submit(@NonNull PoseStack poseStack, @NonNull SubmitNodeCollector collector, int lightCoords, @NonNull AvatarRenderState state, float yRot, float xRot) {
        PlayerModel model = this.getParentModel();
        int overlayCoords = LivingEntityRenderer.getOverlayCoords(state, 0.0F);

        // 关节角跟皮肤那条层取的是同一份（都来自资源包里的动画），两边才会一起弯
        float rightElbow = AnimationBridge.joint(state, "rightForearm");
        float leftElbow = AnimationBridge.joint(state, "leftForearm");
        float rightKnee = AnimationBridge.joint(state, "rightForeleg");
        float leftKnee = AnimationBridge.joint(state, "leftForeleg");

        // 胸甲管两条手臂，护腿和靴子各管一遍两条腿（后画的靴子叠在护腿外面）
        this.submitPiece(
            poseStack, collector, lightCoords, overlayCoords, state.chestEquipment, EquipmentSlot.CHEST,
            EquipmentClientInfo.LayerType.HUMANOID,
            new Limb(model.rightArm, rightElbow, LimbProfile.of(LimbProfile.toT(Math.abs(rightElbow)), LimbProfile.Bend.FORWARD, PLATE_INFLATE), armBox(false)),
            new Limb(model.leftArm, leftElbow, LimbProfile.of(LimbProfile.toT(Math.abs(rightElbow)), LimbProfile.Bend.FORWARD, PLATE_INFLATE), armBox(true))
        );
        this.submitPiece(
            poseStack, collector, lightCoords, overlayCoords, state.legsEquipment, EquipmentSlot.LEGS,
            EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS,
            new Limb(model.rightLeg, rightKnee, LimbProfile.of(LimbProfile.toT(Math.abs(rightKnee)), LimbProfile.Bend.BACKWARD, LEGGING_INFLATE), legBox(false, LEGGING_INFLATE)),
            new Limb(model.leftLeg, leftKnee, LimbProfile.of(LimbProfile.toT(Math.abs(leftKnee)), LimbProfile.Bend.BACKWARD, LEGGING_INFLATE), legBox(true, LEGGING_INFLATE))
        );
        this.submitPiece(
            poseStack, collector, lightCoords, overlayCoords, state.feetEquipment, EquipmentSlot.FEET,
            EquipmentClientInfo.LayerType.HUMANOID,
            new Limb(model.rightLeg, rightKnee, LimbProfile.of(LimbProfile.toT(Math.abs(rightKnee)), LimbProfile.Bend.BACKWARD, BOOT_INFLATE), legBox(false, BOOT_INFLATE)),
            new Limb(model.leftLeg, leftKnee, LimbProfile.of(LimbProfile.toT(Math.abs(leftKnee)), LimbProfile.Bend.BACKWARD, BOOT_INFLATE), legBox(true, BOOT_INFLATE))
        );
    }

    /**
     * 一件盔甲：把它在这个槽位上的每一层贴图都铺到两条肢体上，最后补一遍纹饰。
     *
     * <p>流程跟 {@code EquipmentLayerRenderer#renderLayers} 一样——那套逻辑吃的是一整个
     * {@code Model}，而这里的几何体是自己的，所以只能照着走一遍。
     */
    private void submitPiece(
        PoseStack poseStack,
        SubmitNodeCollector collector,
        int lightCoords,
        int overlayCoords,
        ItemStack itemStack,
        EquipmentSlot slot,
        EquipmentClientInfo.LayerType layerType,
        Limb right,
        Limb left
    ) {
        if (!HumanoidArmorLayer.shouldRender(itemStack, slot)) {
            return;
        }

        ResourceKey<EquipmentAsset> assetId = Objects.requireNonNull(itemStack.get(DataComponents.EQUIPPABLE)).assetId().orElseThrow();
        List<EquipmentClientInfo.Layer> layers = this.equipmentAssets.get(assetId).getLayers(layerType);
        if (layers.isEmpty()) {
            return;
        }

        IClientItemExtensions extensions = IClientItemExtensions.of(itemStack);
        int dyeColor = extensions.getDefaultDyeColor(itemStack);
        // 附魔光效只叠一遍，原版也是画完第一层就不再画了
        boolean glint = itemStack.hasFoil();

        for (int i = 0; i < layers.size(); i++) {
            EquipmentClientInfo.Layer layer = layers.get(i);
            int color = extensions.getArmorLayerTintColor(itemStack, layer, i, dyeColor);
            if (color == 0) {
                continue;
            }

            Identifier texture = ClientHooks.getArmorTexture(itemStack, layerType, layer, layer.getTextureLocation(layerType));
            this.submitShell(poseStack, collector, lightCoords, overlayCoords, RenderTypes.armorCutoutNoCull(texture), color, null, right, left);
            if (glint) {
                this.submitShell(poseStack, collector, lightCoords, overlayCoords, RenderTypes.armorEntityGlint(), color, null, right, left);
                glint = false;
            }
        }

        ArmorTrim trim = itemStack.get(DataComponents.TRIM);
        if (trim != null) {
            // 纹饰是纹饰图集里的一小块，UV 还是 0..1，交给 sprite 把坐标映射到图集那一格
            TextureAtlasSprite sprite = this.trimAtlas.getSprite(trim.layerAssetId(layerType.trimAssetPrefix(), assetId));
            RenderType renderType = Sheets.armorTrimsSheet(trim.pattern().value().decal());
            this.submitShell(poseStack, collector, lightCoords, overlayCoords, renderType, -1, sprite, right, left);
        }
    }

    /** 一层贴图同时铺到左右两条肢体上 */
    private void submitShell(
        PoseStack poseStack,
        SubmitNodeCollector collector,
        int lightCoords,
        int overlayCoords,
        RenderType renderType,
        int color,
        @Nullable TextureAtlasSprite sprite,
        Limb right,
        Limb left
    ) {
        this.submitLimb(poseStack, collector, renderType, right, lightCoords, overlayCoords, color, sprite);
        this.submitLimb(poseStack, collector, renderType, left, lightCoords, overlayCoords, color, sprite);
    }

    /** 一条肢体：外壳的上半截挂在父骨骼上，下半截挂在转过关节角的子骨骼上，和皮肤那条层一模一样 */
    private void submitLimb(
        PoseStack poseStack,
        SubmitNodeCollector collector,
        RenderType renderType,
        Limb limb,
        int lightCoords,
        int overlayCoords,
        int color,
        @Nullable TextureAtlasSprite sprite
    ) {
        poseStack.pushPose();
        limb.part().translateAndRotate(poseStack);

        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) ->
            this.mesh.renderParent(wrap(buffer, sprite), pose, limb.profile(), limb.box(), lightCoords, overlayCoords, color));

        poseStack.pushPose();
        float jointY = limb.box().jointY() * LimbMesh.PIXEL;
        poseStack.translate(0.0F, jointY, 0.0F);
        poseStack.mulPose(Axis.XP.rotation(limb.angle()));
        poseStack.translate(0.0F, -jointY, 0.0F);

        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) ->
            this.mesh.renderChild(wrap(buffer, sprite), pose, limb.profile(), limb.box(), lightCoords, overlayCoords, color));

        poseStack.popPose();
        poseStack.popPose();
    }

    /** 手臂的外壳盒体：贴图 (40, 16)，宽 4、厚 4，左边镜像 */
    private static LimbBox armBox(boolean left) {
        float minX = left ? LEFT_ARM_MIN_X : RIGHT_ARM_MIN_X;
        return LimbBox.shell(ARM_TEX_U, ARM_TEX_V, minX, minX + ARM_WIDTH, ARM_TOP_Y, BentArmorLayer.PLATE_INFLATE, left);
    }

    /** 腿的外壳盒体：贴图 (0, 16)，宽 4、厚 4，左边镜像 */
    private static LimbBox legBox(boolean left, float inflate) {
        return LimbBox.shell(LEG_TEX_U, LEG_TEX_V, LEG_MIN_X, LEG_MAX_X, LEG_TOP_Y, inflate, left);
    }

    private static VertexConsumer wrap(VertexConsumer buffer, @Nullable TextureAtlasSprite sprite) {
        return sprite == null ? buffer : sprite.wrap(buffer);
    }

    /** 一条等着套外壳的肢体；{@code angle} 是关节绕 +X 的弧度（来自动画） */
    private record Limb(ModelPart part, float angle, LimbProfile profile, LimbBox box) {
    }
}
