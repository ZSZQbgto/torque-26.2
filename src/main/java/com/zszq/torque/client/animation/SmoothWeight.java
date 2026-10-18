package com.zszq.torque.client.animation;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.Mth;

/**
 * 按实体维护的平滑权重：每 tick 向目标值逼近 {@code step} 比例，帧内再用 {@code partialTick} 线性插值。
 *
 * <p>状态切换（走↔跑、站↔蹲）是布尔式的，直接用会让动画硬切；把目标值交给这里过渡，
 * 权重就变成连续量，混合出来的姿势也就连续了。
 *
 * <p>一个实例只负责一路权重，按实体 id 分开记录，多人场景互不干扰。
 */
public final class SmoothWeight {
    /** 每 tick 逼近目标值的比例：越小越柔和 */
    private final float step;

    /** 每个实体：[0] 上一 tick 的值、[1] 本 tick 的值、[2] tick 号 */
    private final Map<Integer, float[]> slots = new HashMap<>();

    public SmoothWeight(float step) {
        this.step = step;
    }

    /**
     * 取这个实体当前的平滑值。
     *
     * @param id          实体 id
     * @param tick        实体当前的 tick 计数（用于判断是否需要推进一帧）
     * @param partialTick 渲染插值系数
     * @param target      这一 tick 的目标值
     */
    public float value(int id, int tick, float partialTick, float target) {
        float[] slot = this.slots.get(id);
        if (slot == null) {
            this.slots.put(id, new float[]{target, target, tick});
            return target;
        }

        if ((int) slot[2] != tick) {
            slot[0] = slot[1];
            slot[1] = Mth.lerp(this.step, slot[1], target);
            slot[2] = tick;
        }

        return Mth.lerp(partialTick, slot[0], slot[1]);
    }
}
