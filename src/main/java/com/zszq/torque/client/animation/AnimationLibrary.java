package com.zszq.torque.client.animation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zszq.torque.Torque;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 从资源包读 Blockbench 导出的动画：{@code assets/<namespace>/animations/**.json}，
 * 一个文件里 {@code animations} 下挂几个就收几个，按动画名（{@code animation.walk}）索引。
 *
 * <p>走的是原版资源重载，所以放进资源包同名路径就能覆盖模组自带的那份——模组默认带一份
 * {@code assets/torque/animations/player.json}，玩家想换动作直接把同路径的 json 塞进资源包即可。
 *
 * <p>整个类只做"读 + 解析"，怎么用见 {@link AnimationBridge}。
 */
public final class AnimationLibrary extends SimplePreparableReloadListener<Map<String, BedrockAnimation>> {
    public static final AnimationLibrary INSTANCE = new AnimationLibrary();

    /** 资源包里的目录名：assets/<namespace>/animations */
    private static final String DIRECTORY = "animations";

    private Map<String, BedrockAnimation> animations = Map.of();

    private AnimationLibrary() {
    }

    @Override
    protected Map<String, BedrockAnimation> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, BedrockAnimation> loaded = new HashMap<>();
        FileToIdConverter lister = FileToIdConverter.json(DIRECTORY);

        for (Map.Entry<Identifier, Resource> entry : lister.listMatchingResources(manager).entrySet()) {
            Identifier file = entry.getKey();
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                loaded.putAll(BedrockAnimation.parseAll(root));
            } catch (Exception exception) {
                Torque.LOGGER.error("Failed to read animation file {}", file, exception);
            }
        }

        return loaded;
    }

    @Override
    protected void apply(Map<String, BedrockAnimation> prepared, ResourceManager manager, ProfilerFiller profiler) {
        this.animations = Map.copyOf(prepared);
        Torque.LOGGER.info("Loaded {} bedrock animations from resource packs", this.animations.size());
    }

    /** 按动画名取（{@code animation.walk}），没加载到返回 null */
    public BedrockAnimation get(String name) {
        return this.animations.get(name);
    }
}
