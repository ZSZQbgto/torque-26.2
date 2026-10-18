package com.zszq.torque.client.animation;

/**
 * 一个骨骼上的三条通道。Bedrock 的动画按骨骼名挂值，骨骼名就是 Blockbench 里的组名
 * （{@code rightArm}、{@code rightForeleg}、{@code cape} …），和这个模组的骨骼一一对应。
 */
public record BoneAnimation(AnimationChannel rotation, AnimationChannel position, AnimationChannel scale) {
    public static final BoneAnimation EMPTY = new BoneAnimation(AnimationChannel.EMPTY, AnimationChannel.EMPTY, AnimationChannel.EMPTY);

    public boolean isEmpty() {
        return this.rotation.isEmpty() && this.position.isEmpty() && this.scale.isEmpty();
    }
}
