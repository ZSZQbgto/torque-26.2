package com.zszq.torque.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Ease;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;

/**
 * 把 {@link AnimationLibrary} 里的 Bedrock 动画接到模组的骨骼上。
 *
 * <p><b>状态</b>：七路权重叠加，总和恒为 1——静止播 {@code idle}、走路 {@code walk}、冲刺 {@code run}、
 * 潜行 {@code sneak}、潜行移动 {@code sneak_walk}、鞘翅滑翔 {@code fly}、创造飞行 {@code hover}：
 *
 * <pre>
 * ground     = (1 - fly) · (1 - hover)
 * idle       = (1 - move) · (1 - sneak) · ground
 * walk       = move · (1 - sneak) · (1 - run) · ground
 * run        = move · (1 - sneak) · run · ground
 * sneak      = (1 - move) · sneak · ground
 * sneak_walk = move · sneak · ground
 * fly        = fly
 * hover      = hover
 * </pre>
 *
 * 每个权重都是连续量（移动程度、潜行程度、跑步程度都按 tick 记录、按 {@code partialTick} 插值），
 * 所以状态切换是渐变，不会硬切。飞行是两路互斥的覆盖状态，接管时地面五路整体退到后面。
 *
 * <p><b>挥臂</b>：{@code animation.swing} 只提供手肘那点弯曲，手臂的挥动本身仍是原版
 * {@code setupAttackAnimation} 的增量，叠加在混合好的姿态上（见 {@link #addSwingArm}）。
 *
 * <p><b>时间</b>：动画本身是连续曲线（{@link AnimationChannel#sample} 每个渲染帧求值），没有"帧"的概念。
 * {@code Entity#moveDist} 每 tick 才累加一次，所以额外补上"这一 tick 已经走了多远 × partialTick"，
 * 一个动画循环 = 两声脚步 = {@code moveDist} +2。待机 / 潜行 / 飞行是原地动作，按真实时间走。
 *
 * <p><b>坐标系</b>：动画是 Blockbench / Bedrock 的（Y 向上、Z 轴朝后、右臂在 +X），模型部件是
 * Minecraft 的（Y 向下、Z 轴同样朝后、右臂在 -X）——两者差 180° 绕 Z：X、Y 取反、Z 同向。
 * 所以换算只有这一处：旋转是 X、Y 取反、Z 照抄，位移也是 X、Y 取反、Z 照抄。
 * 资源包里的 json 一律写 Blockbench 的原始数值，导出那一层不做任何符号处理。
 *
 * <p><b>骨骼层级</b>：Blockbench 项目和 Minecraft 模型是同一棵树——头、双臂、双腿都跟 {@code body}
 * 平级，蹲下时的下沉 / 前倾由各自的关键帧承担。渲染这一层不再补父子关系，看到什么就是什么。
 */
public final class AnimationBridge {
    /** 静止站立 */
    private static final String IDLE = "animation.idle";
    /** 走路 */
    private static final String WALK = "animation.walk";
    /** 冲刺 */
    private static final String RUN = "animation.run";
    /** 潜行站立 */
    private static final String SNEAK = "animation.sneak";
    /** 潜行移动 */
    private static final String SNEAK_WALK = "animation.sneak_walk";
    /** 鞘翅滑翔（fall flying） */
    private static final String FLY = "animation.fly";
    /** 创造飞行 / 悬浮 */
    private static final String HOVER = "animation.hover";
    /** 玩家挥臂（攻击）的手肘弯曲 */
    private static final String SWING = "animation.swing";

    /**
     * walkAnimationSpeed 要放到多大才算"完全在动"。
     *
     * <p>原版的 speed 在走路时约 0.25、冲刺约 0.5；潜行只有走路的 0.3 倍，也就是 0.08 上下。
     * 系数给 3.0 时潜行移动的权重只有 0.24，前进动作会被站立蹲姿吃掉大半，看起来就是"潜行没有前进动画"。
     * 4.0 让正常走路和潜行都能到满权重，同时静止附近仍然连续。
     */
    private static final float SPEED_TO_WEIGHT = 4.0F;
    /** 走路 / 冲刺的水平速度（格每 tick），中间这一段是走跑混合的过渡区间 */
    private static final float WALK_SPEED = 0.23F;
    private static final float RUN_SPEED = 0.27F;
    /** 拿不到实体时，用 walkAnimationSpeed 猜跑步的比重 */
    private static final float FALLBACK_RUN_SPEED = 0.95F;
    private static final float FALLBACK_RUN_RANGE = 0.05F;
    /** 原版 {@code moveDist} 的换算系数：每格 × 0.6 */
    private static final float MOVE_DIST_SCALE = 0.6F;
    /** 低于这个水平速度（格每 tick）就算静止；潜行约 0.065、走路约 0.23 */
    private static final float MOVING_SPEED = 0.03F;
    /** 一个动画循环 = 两声脚步 */
    private static final float STEPS_PER_CYCLE = 2.0F;
    /** 拿不到实体时的后备：walkAnimationPos 每 4 个单位 = 1 格，两声 = 3.333 格 */
    private static final float WALK_POS_PER_CYCLE = 13.3333F;

    /** 采样暂存，渲染线程单线程访问 */
    private static final float[] SCRATCH = new float[3];
    private static final float[] SCRATCH_OUT = new float[3];
    /** 挥臂动画的采样暂存，和常规混合分开，避免互相覆盖 */
    private static final float[] SWING_SCRATCH = new float[3];

    /** 权重每 tick 逼近目标值的比例：越小越柔和（0.35 ≈ 8 tick，约 0.4 秒收敛） */
    private static final float SMOOTH_STEP = 0.35F;

    /** 诊断开关：打开后每 2 秒打一行权重，确认状态判定是否符合预期 */
    private static final boolean DEBUG = true;
    private static long DEBUG_AT = 0L;
    private static long DEBUG_LEG_AT = 0L;
    private static long DEBUG_WALK_AT = 0L;

    /** 需要平滑的权重，按实体分开记录 */
    private static final SmoothWeight MOVE_WEIGHT = new SmoothWeight(SMOOTH_STEP);
    private static final SmoothWeight RUN_WEIGHT = new SmoothWeight(SMOOTH_STEP);
    private static final SmoothWeight SNEAK_WEIGHT = new SmoothWeight(SMOOTH_STEP);
    private static final SmoothWeight FLY_WEIGHT = new SmoothWeight(SMOOTH_STEP);
    private static final SmoothWeight HOVER_WEIGHT = new SmoothWeight(SMOOTH_STEP);

    private AnimationBridge() {
    }

    // ------------------------------------------------------------------ 状态

    /**
     * 0..1：静止 0，动起来 1。
     *
     * <p>不能用 {@code walkAnimationSpeed} 判断潜行：原版这个值是移动距离<b>平方</b>缩放的，
     * 潜行速度只有走路的 0.3 倍，实测只有 0.0002，算出来的权重几乎为 0——
     * 表现就是「潜行前进完全没有动画」。这里直接看实体的水平速度。
     */
    private static float weight(AvatarRenderState state) {
        Entity entity = entity(state);
        if (entity == null) {
            return Mth.clamp(state.walkAnimationSpeed * SPEED_TO_WEIGHT, 0.0F, 1.0F);
        }

        float target = Mth.clamp(horizontalSpeed(entity) / MOVING_SPEED, 0.0F, 1.0F);
        return smooth(MOVE_WEIGHT, state, entity, target);
    }

    /**
     * 0..1：跑步动画占的比重。
     *
     * <p>目标值按真实水平速度连续映射；冲刺状态再兜一层——地形、上坡压低速度时也不会闪回走路动画。
     */
    private static float runWeight(AvatarRenderState state) {
        Entity entity = entity(state);
        if (entity == null) {
            return Mth.clamp((state.walkAnimationSpeed - FALLBACK_RUN_SPEED) / FALLBACK_RUN_RANGE, 0.0F, 1.0F);
        }

        float target = Mth.clamp((horizontalSpeed(entity) - WALK_SPEED) / (RUN_SPEED - WALK_SPEED), 0.0F, 1.0F);
        if (entity instanceof LivingEntity living && living.isSprinting()) {
            target = 1.0F;
        }

        return smooth(RUN_WEIGHT, state, entity, target);
    }

    /**
     * 0..1：潜行占的比重（按下 / 松开潜行键时也走插值，不会硬切）。
     *
     * <p>注意不能用 {@code state.isCrouching}——原版只有 {@code HumanoidMobRenderer} 那一支会填这个字段，
     * {@code AvatarRenderer} 从头到尾不设置它，玩家的值恒为 false。所以直接问实体。
     */
    public static float sneakWeight(AvatarRenderState state) {
        Entity entity = entity(state);
        if (entity != null) {
            return smooth(SNEAK_WEIGHT, state, entity, entity.isCrouching() ? 1.0F : 0.0F);
        }

        return state.isCrouching ? 1.0F : 0.0F;
    }

    /**
     * 0..1：鞘翅滑翔占的比重。
     *
     * <p>用渲染状态里的 {@code isFallFlying}（客户端已经按实体同步过来），远距离玩家也能判断；
     * 和其他状态一样走 {@link SmoothWeight}，起翅 / 收翅不会硬切。
     */
    public static float flyWeight(AvatarRenderState state) {
        Entity entity = entity(state);
        float target = state.isFallFlying ? 1.0F : 0.0F;
        return entity == null ? target : smooth(FLY_WEIGHT, state, entity, target);
    }

    /**
     * 0..1：创造飞行（悬浮）占的比重。
     *
     * <p>{@code AvatarRenderState} 不带"正在创造飞行"的字段，只能问实体：玩家能力里的
     * {@code flying}。旁观者不渲染模型，顺手排掉；滑翔时即使能力开着也以滑翔为准。
     */
    public static float hoverWeight(AvatarRenderState state) {
        Entity entity = entity(state);
        boolean flying = entity instanceof Player player
            && !player.isSpectator()
            && !state.isFallFlying
            && player.getAbilities().flying;
        return entity == null ? 0.0F : smooth(HOVER_WEIGHT, state, entity, flying ? 1.0F : 0.0F);
    }

    /** 把目标值交给 {@link SmoothWeight} 过渡 */
    private static float smooth(SmoothWeight weight, AvatarRenderState state, Entity entity, float target) {
        return weight.value(entity.getId(), entity.tickCount, state.partialTick, target);
    }

    // ------------------------------------------------------------------ 挥臂

    /** 玩家当前是否在挥臂（原版挥臂期间，滑翔时不挥）。 */
    public static boolean swingActive(AvatarRenderState state) {
        return state.attackTime > 0.0F && !state.isFallFlying;
    }

    /** 这条骨骼是不是当前攻击手的前臂、并且正在挥臂 */
    private static boolean swingActive(AvatarRenderState state, String bone) {
        if (!swingActive(state)) {
            return false;
        }

        if ("rightForearm".equals(bone)) {
            return state.attackArm == HumanoidArm.RIGHT;
        }

        if ("leftForearm".equals(bone)) {
            return state.attackArm == HumanoidArm.LEFT;
        }

        return false;
    }

    /** 原版挥臂给躯干的扭身增量（{@code setupAttackAnimation} 里那段），叠加在动画姿态上 */
    public static void addSwingBody(ModelPart body, AvatarRenderState state) {
        if (swingActive(state)) {
            body.yRot += swingBodyYaw(state);
        }
    }

    /**
     * 把原版的挥臂增量叠加到手臂上，而不是替换掉动画姿态。
     *
     * <p>原版 {@code setupAttackAnimation} 是直接改写手臂姿态，模组如果整条跳过混合，
     * 潜行 / 走路时手臂就会瞬移到「站立 + 原版挥臂」的位置，挥完再传回来。这里只把原版那段旋转增量
     * 加在已经混合好的姿态上，潜行姿态、走路摆动都保留；原版那 5 像素的位置偏移是给直筒手臂的，
     * 搬到这套更粗的肢体上会明显离开身体，所以位置不动。
     */
    public static void addSwingArm(ModelPart part, AvatarRenderState state, String bone) {
        if (!swingActive(state)) {
            return;
        }

        boolean right = "rightArm".equals(bone);
        boolean left = "leftArm".equals(bone);
        if (!right && !left) {
            return;
        }

        if ((state.attackArm == HumanoidArm.RIGHT) != right) {
            return;
        }

        float attackTime = state.attackTime;
        float bodyYaw = swingBodyYaw(state);

        // 挥动旋转（原版 WHACK 分支）：xRot 前挥、yRot 跟着扭身、zRot 带一点侧摆
        float swing = Ease.outQuart(attackTime);
        float arc = Mth.sin(swing * (float) Math.PI);
        float look = Mth.sin(attackTime * (float) Math.PI) * -(float) (Math.toRadians(state.xRot) - 0.7F) * 0.75F;
        part.xRot -= arc * 1.2F + look;
        part.yRot += bodyYaw * 2.0F;
        part.zRot += Mth.sin(attackTime * (float) Math.PI) * -0.4F;
    }

    /** 原版挥臂的扭身角：{@code body.yRot} 的目标值 */
    private static float swingBodyYaw(AvatarRenderState state) {
        float yaw = Mth.sin(Mth.sqrt(state.attackTime) * (float) (Math.PI * 2.0)) * 0.2F;
        return state.attackArm == HumanoidArm.LEFT ? -yaw : yaw;
    }

    /** 按挥动进度采样挥臂动画写进 {@code out}；没加载到动画或没有这条骨骼时返回 false */
    private static boolean swingAngles(AvatarRenderState state, String bone, float[] out) {
        BedrockAnimation animation = AnimationLibrary.INSTANCE.get(SWING);
        if (animation == null) {
            return false;
        }

        AnimationChannel channel = animation.bone(bone).rotation();
        if (channel.isEmpty()) {
            return false;
        }

        // attackTime 从 1 递减到 0，换算成 0 → 1 的进度再乘动画长度
        channel.sample((1.0F - state.attackTime) * animation.length(), out, false);
        return true;
    }

    /**
     * 动画该处在第几个循环：一个循环 = 原版两声脚步。
     *
     * <p>距离按实体自己累计：原版 {@code moveDist} 只在本机玩家的移动预测里推进，潜行时走几步就停住，
     * 动画也就跟着定住（角色还在前进，腿却不动了）。这里每个 tick 按实体的水平速度累加一次，
     * 帧内再补上"这一 tick 已经走了多远 × partialTick"，曲线在两次 tick 之间也连续推进。
     *
     * <p>拿不到实体时（远处玩家的位置是插值出来的）退回 {@code walkAnimationPos}，远处的披风照样会动。
     */
    private static float cycle(AvatarRenderState state) {
        Entity entity = entity(state);
        if (entity instanceof AbstractClientPlayer clientPlayer) {
            // 和视角摇晃（GameRenderer#bobView）吃同一份累计量：LocalPlayer#move 里按实际位移 ×0.6 累加，
            // 一个 sin 周期正好两个单位。这样任何速度（走路、潜行）下脚和镜头都是同一个节奏。
            return clientPlayer.avatarState().getInterpolatedWalkDistance(state.partialTick) / STEPS_PER_CYCLE;
        }

        if (entity != null) {
            return travelled(entity, state) * MOVE_DIST_SCALE / STEPS_PER_CYCLE;
        }

        return state.walkAnimationPos / WALK_POS_PER_CYCLE;
    }

    /** 每个实体累计走过的距离（格），每 tick 按水平速度加一次 */
    private static final Map<Integer, float[]> TRAVELLED = new HashMap<>();

    private static float travelled(Entity entity, AvatarRenderState state) {
        float[] slot = TRAVELLED.computeIfAbsent(entity.getId(), _ -> new float[]{0.0F, entity.tickCount});

        if ((int) slot[1] != entity.tickCount) {
            slot[0] += horizontalSpeed(entity);
            slot[1] = entity.tickCount;
        }

        return slot[0] + horizontalSpeed(entity) * state.partialTick;
    }

    /** 实体这一 tick 的水平位移（格），客户端玩家拿到的就是预测速度 */
    private static float horizontalSpeed(Entity entity) {
        return (float) entity.getDeltaMovement().horizontalDistance();
    }

    /**
     * 动画当前时间（秒）。
     *
     * <p>移动类动画按走过的距离走（{@code moveDist}），这样循环和脚步声咬合；
     * 但待机 / 潜行 / 飞行是原地动作，用距离驱动会在松手瞬间冻住，所以它们改按真实时间走。
     */
    private static float time(AvatarRenderState state, BedrockAnimation animation, String name) {
        if (IDLE.equals(name) || SNEAK.equals(name) || FLY.equals(name) || HOVER.equals(name)) {
            return animation.wrap(state.ageInTicks / 20.0F);
        }

        float cycle = cycle(state);
        if (DEBUG && SNEAK_WALK.equals(name) && System.currentTimeMillis() - DEBUG_WALK_AT > 2000L) {
            DEBUG_WALK_AT = System.currentTimeMillis();
            Entity entity = entity(state);
            com.zszq.torque.Torque.LOGGER.info(
                "[torque] sneak_walk cycle={} moved={} speed={} move={} sneak={}",
                cycle,
                entity == null ? -1.0F : travelled(entity, state),
                entity == null ? -1.0F : horizontalSpeed(entity),
                weight(state), sneakWeight(state)
            );
        }

        return animation.wrap(cycle * animation.length());
    }

    private static Entity entity(AvatarRenderState state) {
        Level level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntity(state.id);
    }

    // ------------------------------------------------------------------ 采样

    /**
     * 七种状态的权重，由五个连续量组合出来，总和恒为 1。
     *
     * <p>飞行是两路互斥的覆盖状态：鞘翅滑翔和创造飞行一旦接管，地面上的五种状态整体乘
     * {@code (1 - fly) · (1 - hover)} 退到后面，这样起翅 / 落地都是连续的混合，而不是硬切。
     * 加新状态时只改这里：把它的权重表达式写进来、在 {@link #sample} 里多接一路动画即可。
     */
    private record Weights(float idle, float walk, float run, float sneak, float sneakWalk, float fly, float hover) {
        static Weights of(float move, float run, float sneak, float fly, float hover) {
            float ground = (1.0F - fly) * (1.0F - hover);
            return new Weights(
                (1.0F - move) * (1.0F - sneak) * ground,
                move * (1.0F - sneak) * (1.0F - run) * ground,
                move * (1.0F - sneak) * run * ground,
                (1.0F - move) * sneak * ground,
                move * sneak * ground,
                fly,
                hover
            );
        }
    }

    /**
     * 把七路动画在 bone 上采样后混合，结果写进 {@link #SCRATCH_OUT}。
     *
     * <p>七路权重之和恒为 1，所以这里直接相加就是加权平均——<b>不要再按"实际有数据的权重"重归一化</b>。
     * 某个动画没写这根骨骼（比如 idle 的腿就是故意留空的）时，它应该贡献 0、把权重让给别的状态；
     * 重新归一化会把剩下那一点点权重的动画放大成 100%，典型症状就是松开潜行后腿一直卡在潜行的角度上。
     *
     * @return 有没有任何一路真正贡献了数据（没加载到动画时返回 false，调用方就保持原版姿态）
     */
    private static boolean sample(AvatarRenderState state, String bone, boolean rotation) {
        Weights weights = Weights.of(weight(state), runWeight(state), sneakWeight(state), flyWeight(state), hoverWeight(state));

        SCRATCH_OUT[0] = 0.0F;
        SCRATCH_OUT[1] = 0.0F;
        SCRATCH_OUT[2] = 0.0F;

        float total = 0.0F;
        total += addChannel(state, IDLE, bone, weights.idle(), rotation);
        total += addChannel(state, WALK, bone, weights.walk(), rotation);
        total += addChannel(state, RUN, bone, weights.run(), rotation);
        total += addChannel(state, SNEAK, bone, weights.sneak(), rotation);
        total += addChannel(state, SNEAK_WALK, bone, weights.sneakWalk(), rotation);
        total += addChannel(state, FLY, bone, weights.fly(), rotation);
        total += addChannel(state, HOVER, bone, weights.hover(), rotation);

        if (DEBUG && System.currentTimeMillis() - DEBUG_AT > 2000L) {
            DEBUG_AT = System.currentTimeMillis();
            com.zszq.torque.Torque.LOGGER.info(
                "[torque] bone={} idle={} walk={} run={} sneak={} sneakWalk={} fly={} hover={} -> {},{},{} total={}",
                bone, weights.idle(), weights.walk(), weights.run(), weights.sneak(), weights.sneakWalk(),
                weights.fly(), weights.hover(), SCRATCH_OUT[0], SCRATCH_OUT[1], SCRATCH_OUT[2], total
            );
        }

        return total > 0.0F;
    }

    private static float addChannel(AvatarRenderState state, String animationName, String bone, float weight, boolean rotation) {
        if (weight <= 0.0F) {
            return 0.0F;
        }

        BedrockAnimation animation = AnimationLibrary.INSTANCE.get(animationName);
        if (animation == null) {
            return 0.0F;
        }

        AnimationChannel channel = rotation ? animation.bone(bone).rotation() : animation.bone(bone).position();
        if (channel.isEmpty()) {
            return 0.0F;
        }

        channel.sample(time(state, animation, animationName), SCRATCH, animation.loop());
        accumulate(weight);
        return weight;
    }

    private static void accumulate(float weight) {
        SCRATCH_OUT[0] += AnimationBridge.SCRATCH[0] * weight;
        SCRATCH_OUT[1] += AnimationBridge.SCRATCH[1] * weight;
        SCRATCH_OUT[2] += AnimationBridge.SCRATCH[2] * weight;
    }

    // ------------------------------------------------------------------ 应用

    /**
     * 把骨骼的旋转写到部件上。完全由动画决定（待机动画会接管静止姿势）；
     * 一路动画都没加载到的时候保持 {@code super.setupAnim} 算出来的原版姿势。
     *
     * <p>X、Y 取反、Z 照抄——见类注释里的坐标系说明，这是 Blockbench 数值进模型之前唯一的一次换算。
     */
    public static void blendRotation(ModelPart part, AvatarRenderState state, String bone) {
        if (!sample(state, bone, true)) {
            // 七路动画都没有这根骨骼的数据：归零，别让原版 setupAnim 的残留角度把动作卡在最后一帧
            part.xRot = 0.0F;
            part.yRot = 0.0F;
            part.zRot = 0.0F;
            return;
        }

        part.xRot = (float) Math.toRadians(-SCRATCH_OUT[0]);
        part.yRot = (float) Math.toRadians(-SCRATCH_OUT[1]);
        part.zRot = (float) Math.toRadians(SCRATCH_OUT[2]);

        if (DEBUG && "rightLeg".equals(bone) && System.currentTimeMillis() - DEBUG_LEG_AT > 2000L) {
            DEBUG_LEG_AT = System.currentTimeMillis();
            com.zszq.torque.Torque.LOGGER.info(
                "[torque] part rightLeg xRot={}° yRot={}° zRot={}°",
                Math.toDegrees(part.xRot), Math.toDegrees(part.yRot), Math.toDegrees(part.zRot)
            );
        }
    }

    /**
     * 把骨骼的旋转**叠加**到部件上，而不是替换。
     *
     * <p>用在原版已经摆好姿态的部件上（典型是头：{@code super.setupAnim} 已经把视线写在
     * {@code head.xRot/yRot} 上了），动画只往上加自己的摆动，视线不会被吃掉。
     */
    public static void addRotation(ModelPart part, AvatarRenderState state, String bone) {
        if (!sample(state, bone, true)) {
            return;
        }

        part.xRot += (float) Math.toRadians(-SCRATCH_OUT[0]);
        part.yRot += (float) Math.toRadians(-SCRATCH_OUT[1]);
        part.zRot += (float) Math.toRadians(SCRATCH_OUT[2]);
    }

    /**
     * 把动画摆到「躯干 + 头」上。玩家的 {@code PlayerModel} 和盔甲用的模型都调这一份——
     * 两边各自算的话头盔会和头脱开。
     *
     * <p>头和身体在模型里是平级的两个部件，动画数据也按这个层级写：躯干的前倾、下沉在 body 的关键帧里，
     * 头跟着下沉多少也写在 head 自己的关键帧里。所以这里只做两件事——body 直接照动画摆，
     * 头把动画的摆动叠到 {@code super.setupAnim} 已经算好的视线上。
     */
    public static void applyBodyAndHead(ModelPart body, ModelPart head, AvatarRenderState state) {
        blendRotation(body, state, "body");
        blendPosition(body, state, "body");
        // 在动画姿态上叠加原版挥臂的扭身，而不是丢掉动画姿态
        addSwingBody(body, state);

        // 原版滑翔会把 head.xRot 硬设成 -π/4，替掉视线俯仰；这里按滑翔权重插回视线角度，
        // 让头继续跟随视角（yRot 原版本来就保留视线），动画的 head 通道再叠在上面。
        float fly = flyWeight(state);
        if (fly > 0.0F) {
            head.xRot = Mth.lerp(fly, head.xRot, (float) Math.toRadians(state.xRot));
        }
        addRotation(head, state, "head");
        blendPosition(head, state, "head");
    }

    /**
     * 把骨骼的位移写到部件上。Bedrock 的 position 单位是像素，Y 向上、右臂在 +X，
     * 模型这边 Y 向下、右臂在 -X，所以 X、Y 取反、Z 照抄；
     * 每帧 {@code setupAnim} 都会先重置部件，这里直接加在原始位置上。
     */
    public static void blendPosition(ModelPart part, AvatarRenderState state, String bone) {
        if (!sample(state, bone, false)) {
            return;
        }

        part.x -= SCRATCH_OUT[0];
        part.y -= SCRATCH_OUT[1];
        part.z += SCRATCH_OUT[2];

        if (DEBUG && ("rightLeg".equals(bone) || "body".equals(bone)) && System.currentTimeMillis() - DEBUG_LEG_AT > 2000L) {
            DEBUG_LEG_AT = System.currentTimeMillis();
            com.zszq.torque.Torque.LOGGER.info(
                "[torque] part {} pos=({},{},{}) sample=({},{},{})",
                bone, part.x, part.y, part.z, SCRATCH_OUT[0], SCRATCH_OUT[1], SCRATCH_OUT[2]
            );
        }
    }

    /**
     * 关节角（弧度，绕 +X 轴，正负与 {@code Axis.XP.rotation} 一致）。
     *
     * <p>只从资源包的动画里取，模组自己不硬编码任何动作：没有动画就伸直（0）。
     * json 里写的是 Blockbench 的数值，取反之后才是模型空间里的角度（见类注释）。
     * 挥臂时再把前臂的弯曲增量叠上去（{@code animation.swing} 只写这两根骨骼）。
     */
    public static float joint(AvatarRenderState state, String bone) {
        float angle = 0.0F;
        if (sample(state, bone, true)) {
            angle = -SCRATCH_OUT[0];
        }

        // 挥臂时把前臂的挥动叠到关节角上（sample 用 SCRATCH_OUT，这里用独立的 SWING_SCRATCH）
        if (swingActive(state, bone) && swingAngles(state, bone, SWING_SCRATCH)) {
            angle += -SWING_SCRATCH[0] * state.attackTime;
        }

        return (float) Math.toRadians(angle);
    }

    /**
     * 原版披风根部的那套旋转（{@code PlayerCapeModel} 用 {@code rotateBy} 叠出来的）：
     * cape 部件的初始 yRot = π 与 rotateBy 里的 Ry(-π) 抵消，剩下
     * {@code Rx(6 + capeLean/2 + capeFlap) · Rz(capeLean2/2) · Ry(180 - capeLean2/2)}。
     */
    private static Quaternionf vanillaCapeRotation(AvatarRenderState state) {
        float toRadians = (float) (Math.PI / 180.0);
        float xRot = (6.0F + state.capeLean / 2.0F + state.capeFlap) * toRadians;
        float zRot = state.capeLean2 / 2.0F * toRadians;
        float yRot = (180.0F - state.capeLean2 / 2.0F) * toRadians;
        return new Quaternionf().rotateX(xRot).rotateZ(zRot).rotateY(yRot);
    }

    /**
     * 披风根部的朝向：静止时用原版那套飘逸；一旦在动、潜行或者飞行，就淡出成只有朝背后的 180°，
     * 这样披风的表现严格等于资源包里的数值，不会被原版的 capeFlap / capeLean 盖过去。
     */
    public static void applyCapeBase(PoseStack poseStack, AvatarRenderState state) {
        Quaternionf vanilla = vanillaCapeRotation(state);
        // 飞行时披风完全交给动画（fly / hover 都有自己的披风通道），原版的 capeFlap / capeLean 必须淡出
        float move = Math.max(Math.max(weight(state), sneakWeight(state)), Math.max(flyWeight(state), hoverWeight(state)));
        if (move <= 0.001F) {
            poseStack.mulPose(vanilla);
            return;
        }

        Quaternionf facingBack = new Quaternionf().rotateY((float) Math.PI);
        poseStack.mulPose(vanilla.slerp(facingBack, move));
    }

    /**
     * 给披风的某一段施加动画旋转。调用时 PoseStack 的原点必须正好落在这一段的 pivot 上，
     * 而且旋转要**累积**在同一个 PoseStack 上（父段转完子段接着转，才是一条链）。
     * Blockbench 里三段分别是 {@code cape} 24、{@code capemid} 20、{@code capedown} 15。
     *
     * <p>注意这里是在 {@code applyCapeBase} **之后**的局部空间里动手的，而那一步带着
     * {@code Ry(≈180°)}（披风要朝着背后）。绕 Y 的 180° 会让 X、Z 的旋转方向翻过来：
     * 动画想把披风在世界里转到 {@code (-x, -y, +z)}，在这里就得写 {@code (x, -y, -z)}。
     */
    public static void rotateCapeSegment(PoseStack poseStack, AvatarRenderState state, String bone) {
        if (!sample(state, bone, true)) {
            return;
        }

        float toRadians = (float) (Math.PI / 180.0);
        poseStack.mulPose(Axis.XP.rotation(SCRATCH_OUT[0] * toRadians));
        poseStack.mulPose(Axis.YP.rotation(-SCRATCH_OUT[1] * toRadians));
        poseStack.mulPose(Axis.ZP.rotation(-SCRATCH_OUT[2] * toRadians));
    }
}
