package com.zszq.torque;

import com.zszq.torque.client.animation.AnimationLibrary;
import com.zszq.torque.client.layer.BentArmorLayer;
import com.zszq.torque.client.layer.BentCapeLayer;
import com.zszq.torque.client.layer.BentLimbLayer;
import com.zszq.torque.client.layer.TorqueWingsLayer;
import com.zszq.torque.client.model.TorqueArmorModel;
import com.zszq.torque.client.model.TorquePlayerModel;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.AddLayers;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = Torque.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = Torque.MODID, value = Dist.CLIENT)
public class TorqueClient {
    public TorqueClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        Torque.LOGGER.info("HELLO FROM CLIENT SETUP");
        Torque.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    /**
     * 把动画的加载器挂到资源重载链上：{@code assets/<namespace>/animations/**.json} 里的
     * Blockbench 导出的动画会在每次资源包重载时重新读一遍，资源包放同路径就能覆盖。
     */
    @SubscribeEvent
    static void onAddReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(Torque.MODID, "animations"), AnimationLibrary.INSTANCE);
    }

    /**
     * 把玩家渲染器换成藏掉原版四肢的模型，再挂上自定义几何体的渲染层：
     * {@link BentLimbLayer} 画皮肤上的两大截肢体，{@link BentArmorLayer} 画套在它们外面的盔甲，
     * 原版的 {@code CapeLayer} 换成拆成三段的 {@link BentCapeLayer}。
     *
     * <p>写 {@code renderer.model} 需要 accesstransformer 把 {@code LivingEntityRenderer#model}
     * 从 protected 放开。
     */
    @SubscribeEvent
    static void onAddLayers(AddLayers event) {
        EntityModelSet models = event.getEntityModels();

        for (PlayerModelType type : event.getSkins()) {
            AvatarRenderer<AbstractClientPlayer> renderer = event.getPlayerRenderer(type);
            if (renderer != null) {
                boolean slim = type == PlayerModelType.SLIM;
                renderer.model = new TorquePlayerModel(models.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
                renderer.addLayer(new BentLimbLayer(renderer));

                // 顺序有讲究：先皮肤后盔甲，同一片深度上盔甲才会盖在皮肤外面
                useBentArmorModels(renderer, models);
                renderer.addLayer(new BentArmorLayer(renderer, event.getContext()));
                useBentCape(renderer, event.getContext());
                useWingsLayer(renderer, models, event.getContext().getEquipmentRenderer());
            }
        }
    }

    /**
     * 把玩家盔甲层里那套直筒模型换成 {@link TorqueArmorModel}：四肢交给 {@link BentArmorLayer} 重画，
     * 模型这边只留头盔和躯干。
     *
     * <p>宽窄皮肤共用同一套网格——原版 {@code PLAYER_SLIM_ARMOR} 挂的就是 {@code PLAYER_ARMOR} 那份定义，
     * 手臂和腿反正都藏掉了，留下的脑袋和躯干两边一模一样。
     *
     * <p>需要 accesstransformer 打开 {@code LivingEntityRenderer#layers} 和
     * {@code HumanoidArmorLayer#modelSet}（后者还要去掉 final）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void useBentArmorModels(AvatarRenderer<AbstractClientPlayer> renderer, EntityModelSet models) {
        ArmorModelSet<ModelLayerLocation> locations = ModelLayers.PLAYER_ARMOR;
        ArmorModelSet<TorqueArmorModel> bent = new ArmorModelSet<>(
            new TorqueArmorModel(models.bakeLayer(locations.head())),
            new TorqueArmorModel(models.bakeLayer(locations.chest())),
            new TorqueArmorModel(models.bakeLayer(locations.legs())),
            new TorqueArmorModel(models.bakeLayer(locations.feet()))
        );

        for (RenderLayer<AvatarRenderState, PlayerModel> layer : renderer.layers) {
            if (layer instanceof HumanoidArmorLayer) {
                ((HumanoidArmorLayer) layer).modelSet = bent;
            }
        }
    }

    /**
     * 把原版披风层换成拆成三段的 {@link BentCapeLayer}，位置留在原来的序号上，免得打乱前后渲染顺序。
     *
     * <p>原版 {@code CapeLayer} 内部自己 bake 了一个 {@code PlayerCapeModel}，模型又没法换成自定义几何
     * （{@code Model#renderToBuffer} 是 final），所以只能整层换掉，登场条件在 {@link BentCapeLayer} 里照抄。
     */
    private static void useBentCape(AvatarRenderer<AbstractClientPlayer> renderer, EntityRendererProvider.Context context) {
        List<RenderLayer<AvatarRenderState, PlayerModel>> layers = renderer.layers;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i) instanceof CapeLayer) {
                layers.set(i, new BentCapeLayer(renderer, context.getEquipmentAssets()));
            }
        }
    }

    /**
     * 把原版鞘翅层换成 {@link TorqueWingsLayer}：它会在画鞘翅前叠上 {@code body} 的变换，
     * 这样鞘翅就能跟着 {@code animation.fly} / {@code animation.hover} 的躯干微动一起晃。
     *
     * <p>装备层渲染器直接复用 {@code Context} 里那一份，位置留在原序号上，渲染顺序不变。
     */
    private static void useWingsLayer(
        AvatarRenderer<AbstractClientPlayer> renderer,
        EntityModelSet models,
        EquipmentLayerRenderer equipmentRenderer
    ) {
        List<RenderLayer<AvatarRenderState, PlayerModel>> layers = renderer.layers;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i) instanceof WingsLayer<?, ?>) {
                layers.set(i, new TorqueWingsLayer(renderer, models, equipmentRenderer));
            }
        }
    }
}
