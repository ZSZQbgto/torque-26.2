package com.zszq.torque.client.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份 Blockbench 导出的动画（Bedrock 格式）：{@code animations} 下面可以挂好几个，这里每个都算一条。
 *
 * <p>结构就三样东西：循环与否、总长（秒）、以及每个骨骼上的三条通道。骨骼名直接沿用
 * Blockbench 的组名，所以 {@code rightArm} / {@code rightForeleg} / {@code cape} 这些名字能直接
 * 对上模组里的骨骼。
 *
 * <p>坐标是 Bedrock 的（Y 向上、+Z 朝前）。换算到 Minecraft 的模型空间（Y 向下、-Z 朝前）时只反转
 * Y、Z 两个轴的旋转，X 不变——这一点在 {@code AnimationBridge} 里统一处理。
 */
public record BedrockAnimation(String name, boolean loop, float length, Map<String, BoneAnimation> bones) {
    public static final BedrockAnimation EMPTY = new BedrockAnimation("", false, 0.0F, Map.of());

    /** 这个动画里有没有这个骨骼（没写就是不动） */
    public BoneAnimation bone(String bone) {
        return this.bones.getOrDefault(bone, BoneAnimation.EMPTY);
    }

    /**
     * 把"从动画开始算起的时间"折成一个采样用的时间：循环动画绕回 [0, length)，
     * 不循环的夹在两端。
     */
    public float wrap(float time) {
        if (this.length <= 0.0F) {
            return 0.0F;
        }

        if (!this.loop) {
            return Math.clamp(time, 0.0F, this.length);
        }

        float wrapped = time % this.length;
        return wrapped < 0.0F ? wrapped + this.length : wrapped;
    }

    /**
     * 解析一个动画文件里的所有动画，返回 动画名 → 动画。
     *
     * <p>关键帧既可能是数组（{@code [0, -2, 1.5]}），也可能是带插值模式的对象
     * （{@code {"post": [...], "lerp_mode": "catmullrom"}}），两种都吃。
     */
    public static Map<String, BedrockAnimation> parseAll(JsonObject root) {
        JsonObject animations = root.getAsJsonObject("animations");
        if (animations == null) {
            return Map.of();
        }

        Map<String, BedrockAnimation> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }

            JsonObject json = entry.getValue().getAsJsonObject();
            boolean loop = json.has("loop") && json.get("loop").getAsBoolean();
            Map<String, BoneAnimation> bones = new HashMap<>();
            JsonObject boneObject = json.getAsJsonObject("bones");
            if (boneObject != null) {
                for (Map.Entry<String, JsonElement> bone : boneObject.entrySet()) {
                    if (bone.getValue().isJsonObject()) {
                        bones.put(bone.getKey(), parseBone(bone.getValue().getAsJsonObject()));
                    }
                }
            }

            float length = json.has("animation_length") ? json.get("animation_length").getAsFloat() : lastTime(bones);
            result.put(entry.getKey(), new BedrockAnimation(entry.getKey(), loop, length, Map.copyOf(bones)));
        }

        return Map.copyOf(result);
    }

    private static BoneAnimation parseBone(JsonObject json) {
        return new BoneAnimation(
            parseChannel(json.get("rotation")),
            parseChannel(json.get("position")),
            parseChannel(json.get("scale"))
        );
    }

    private static AnimationChannel parseChannel(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return AnimationChannel.EMPTY;
        }

        List<Float> times = new ArrayList<>();
        List<float[]> values = new ArrayList<>();
        List<AnimationChannel.LerpMode> modes = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            float time;
            try {
                time = Float.parseFloat(entry.getKey());
            } catch (NumberFormatException exception) {
                continue;
            }

            JsonElement value = entry.getValue();
            float[] vector = new float[3];
            AnimationChannel.LerpMode mode = AnimationChannel.LerpMode.LINEAR;

            if (value.isJsonArray()) {
                readVector(value.getAsJsonArray(), vector);
            } else if (value.isJsonObject()) {
                JsonObject frame = value.getAsJsonObject();
                JsonElement post = frame.has("post") ? frame.get("post") : frame.get("pre");
                if (post != null) {
                    if (post.isJsonArray()) {
                        readVector(post.getAsJsonArray(), vector);
                    } else {
                        // scale 可以只写一个数
                        float uniform = post.getAsFloat();
                        vector[0] = uniform;
                        vector[1] = uniform;
                        vector[2] = uniform;
                    }
                }

                if (frame.has("lerp_mode")) {
                    mode = switch (frame.get("lerp_mode").getAsString()) {
                        case "catmullrom" -> AnimationChannel.LerpMode.CATMULLROM;
                        case "step" -> AnimationChannel.LerpMode.STEP;
                        default -> AnimationChannel.LerpMode.LINEAR;
                    };
                }
            } else {
                continue;
            }

            times.add(time);
            values.add(vector);
            modes.add(mode);
        }

        if (times.isEmpty()) {
            return AnimationChannel.EMPTY;
        }

        // JSON 的键顺序不保证按时间排，先按时间稳定排一遍
        Integer[] order = new Integer[times.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparing(times::get));

        float[] sortedTimes = new float[order.length];
        float[][] sortedValues = new float[order.length][];
        AnimationChannel.LerpMode[] sortedModes = new AnimationChannel.LerpMode[order.length];
        for (int i = 0; i < order.length; i++) {
            sortedTimes[i] = times.get(order[i]);
            sortedValues[i] = values.get(order[i]);
            sortedModes[i] = modes.get(order[i]);
        }

        return new AnimationChannel(sortedTimes, sortedValues, sortedModes);
    }

    private static void readVector(JsonArray array, float[] output) {
        for (int i = 0; i < 3 && i < array.size(); i++) {
            output[i] = array.get(i).getAsFloat();
        }
    }

    private static float lastTime(Map<String, BoneAnimation> bones) {
        float last = 0.0F;
        for (BoneAnimation bone : bones.values()) {
            last = Math.max(last, bone.rotation().lastTime());
            last = Math.max(last, bone.position().lastTime());
            last = Math.max(last, bone.scale().lastTime());
        }

        return last;
    }
}
