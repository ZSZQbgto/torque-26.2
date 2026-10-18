package com.zszq.torque.client.animation;

/**
 * 一条通道（rotation / position / scale）上的关键帧和采样。
 *
 * <p>Bedrock 动画里每个关键帧长这样：{@code "0.0": [0, -2, 1.5]}，或者带插值模式：
 * {@code "0.0": {"post": [0, -2, 1.5], "lerp_mode": "catmullrom"}}。
 * 这里只取 {@code post}（离开这一帧用的值）——导出器基本只写 post，{@code pre} 只有同一时间点
 * 需要不同的到达 / 离开值时才出现，走路这种循环动画用不到。
 *
 * <p>插值方式收在 {@link LerpMode} 里：{@code linear}、{@code catmullrom}（按相邻四帧算切线）、
 * {@code step}（保持上一帧的值）。采样时超出首尾就夹到端点，循环由调用方先把时间折回来。
 */
public final class AnimationChannel {
    public static final AnimationChannel EMPTY = new AnimationChannel(new float[0], new float[0][], new LerpMode[0]);

    /** 一个关键帧到下一帧之间怎么过渡 */
    public enum LerpMode {
        LINEAR,
        CATMULLROM,
        STEP
    }

    private final float[] times;
    private final float[][] values;
    /** {@code modes[i]} 是第 i 帧到第 i + 1 帧的插值方式 */
    private final LerpMode[] modes;

    public AnimationChannel(float[] times, float[][] values, LerpMode[] modes) {
        this.times = times;
        this.values = values;
        this.modes = modes;
    }

    public boolean isEmpty() {
        return this.times.length == 0;
    }

    /** 最晚的关键帧时间，用来补上没有写 {@code animation_length} 的动画 */
    public float lastTime() {
        return this.times.length == 0 ? 0.0F : this.times[this.times.length - 1];
    }

    /**
     * 在 {@code time} 处采样，结果写进 {@code output}（长度 3）。
     * 时间是秒，已经由调用方处理过循环；{@code loop} 只影响 Catmull-Rom 取邻帧的方式。
     */
    public void sample(float time, float[] output, boolean loop) {
        int count = this.times.length;
        if (count == 0) {
            output[0] = 0.0F;
            output[1] = 0.0F;
            output[2] = 0.0F;
            return;
        }

        if (time <= this.times[0]) {
            copy(this.values[0], output);
            return;
        }

        if (time >= this.times[count - 1]) {
            copy(this.values[count - 1], output);
            return;
        }

        int index = 0;
        while (index < count - 2 && this.times[index + 1] <= time) {
            index++;
        }

        float span = this.times[index + 1] - this.times[index];
        float progress = span <= 1.0E-6F ? 0.0F : (time - this.times[index]) / span;

        switch (this.modes[index]) {
            case STEP -> copy(this.values[index], output);
            case CATMULLROM -> this.catmullrom(index, progress, output, loop);
            default -> this.linear(index, progress, output);
        }
    }

    private void linear(int index, float t, float[] output) {
        float[] from = this.values[index];
        float[] to = this.values[index + 1];
        for (int i = 0; i < 3; i++) {
            output[i] = from[i] + (to[i] - from[i]) * t;
        }
    }

    /**
     * Catmull-Rom：用前后各一帧当切线依据。
     *
     * <p>循环动画的首尾要互相环绕着取邻帧（{@code t=0} 的前一帧是倒数第二帧、{@code t=length}
     * 的后一帧是第二帧），否则循环接缝处速度会突然停一下；不循环的动画才把端点复制一份。
     */
    private void catmullrom(int index, float t, float[] output, boolean loop) {
        int count = this.times.length;
        int prev = index - 1;
        int next = index + 2;

        if (loop) {
            if (prev < 0) {
                prev = Math.max(0, count - 2);
            }
            if (next >= count) {
                next = Math.min(count - 1, 1);
            }
        } else {
            prev = Math.max(0, prev);
            next = Math.min(count - 1, next);
        }

        float[] p0 = this.values[prev];
        float[] p1 = this.values[index];
        float[] p2 = this.values[index + 1];
        float[] p3 = this.values[next];
        float t2 = t * t;
        float t3 = t2 * t;

        for (int i = 0; i < 3; i++) {
            output[i] = 0.5F * (2.0F * p1[i]
                + (p2[i] - p0[i]) * t
                + (2.0F * p0[i] - 5.0F * p1[i] + 4.0F * p2[i] - p3[i]) * t2
                + (3.0F * p1[i] - p0[i] - 3.0F * p2[i] + p3[i]) * t3);
        }
    }

    private static void copy(float[] from, float[] to) {
        to[0] = from[0];
        to[1] = from[1];
        to[2] = from[2];
    }
}
