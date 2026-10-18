package com.zszq.torque.client.model;

/**
 * 一条肢体（手臂或腿）的侧面轮廓：从侧面看是一条由 7 层折线构成的带状图形，每层有左右两个点。
 *
 * <p>轮廓用侧视图的 2D 坐标 (u, v) 描述：
 * <ul>
 *   <li>{@code u} 是前后方向，0 在图的左边，4 在图的右边；</li>
 *   <li>{@code v} 是长度方向，0 是肢体的顶端（肩 / 髋），12 是末端（手 / 脚）。</li>
 * </ul>
 * 层的名字沿用规则里的字母顺序：0 = M/N、1 = K/L、2 = A/B、3 = C/D、4 = E/F、5 = G/H、6 = I/J。
 * 0..2 层固定不动，3、4 层在关节附近做补偿，4..6 层绕关节转过 p——全部用 {@code t = tan(p / 2)}
 * 表达，{@code t} 从 0 到 √3 对应 0° 到 120°。{@code t} 过 1（也就是 90°）之后，
 * B、D、F 并到同一个点上，肢体不再有"外翻"的那一角。
 *
 * <p>两份层点分别属于父骨骼（{@link Frame#UPPER}，直接写在模型坐标系里）和子骨骼
 * （{@link Frame#LOWER}，是它自己"绕关节旋转之前"的坐标系）。两份共用第 {@link #JOINT_LAYER} 层
 * 当接缝，而且子骨骼那份的第 4 层是按「转过去之后正好落回父骨骼的第 4 层」反解出来的，
 * 所以两个坐标系里的接缝在空间上完全重合，拼起来看不到缝。
 *
 * <p>手肘往前弯、膝盖往后弯，两者的区别都收在 {@link Bend} 里：它只决定图左落在模型的 +z
 * 还是 -z 上，其余每一层的数值两边完全一样。
 *
 * <p>同一个轮廓还能整条往外涨 {@code inflate} 像素（{@link #of(float, Bend, float)}），用来画套在肢体
 * 外面的盔甲外壳。涨法就是原版 {@code CubeDeformation} 对盒体做的事——每个面沿自己的外法线往外推
 * {@code inflate} 像素，只不过这里推的是一条弯折的带子：每层点都沿轮廓的外法线挪出去，折点走角平分线
 * （miter），两端顺着端盖方向补上。所以外壳和皮肤上的肢体处处等距，弯到哪儿都贴着，贴着的那层贴图也就
 * 跟着轮廓一起变形。
 */
public final class LimbProfile {
    /** 轮廓的层数 */
    public static final int LAYER_COUNT = 7;
    /** 关节所在的层：父骨骼画到这里，子骨骼从这里接着画 */
    public static final int JOINT_LAYER = 4;
    /** 关节沿肢体长度方向的位置：第 4 层，也就是从顶端往下 6 像素 */
    public static final float JOINT_V = 6.0F;
    /** 原版肢体的横截面厚度：前后 4 像素 */
    public static final float THICKNESS = 4.0F;

    /** 关节角最多能弯到 120° */
    public static final float MAX_ANGLE = (float) Math.toRadians(120.0);
    /** t = tan(p / 2) 的上限：p 到 120° 时正好是 tan 60° = √3 */
    public static final float MAX_T = (float) Math.tan(MAX_ANGLE * 0.5D);

    /** 小于这个角度就当作完全伸直，免得 tan 在 0 附近抖 */
    private static final float MIN_ANGLE = 1.0E-4F;
    /**
     * 短于半像素的段当作退化：弯过 90° 之后 B、D、F 会并到一起，剩下的都是零点几像素的碎段，
     * 方向完全由舍入误差决定，拿去推法线会把外壳带偏。
     */
    private static final float MIN_SEGMENT = 0.5F;
    /** 合成出来的方向短于这个长度就不用了（两条法线几乎反向） */
    private static final float MIN_NORMAL = 1.0E-3F;
    /** 折点沿角平分线最多伸出去几倍的 {@code inflate}，防止尖角在接缝外侧拉出一根长刺 */
    private static final float MAX_MITER = 3.0F;

    /** 每层的四个分量在 {@link #upper}、{@link #lower} 里的排布 */
    private static final int LEFT_Z = 0;
    private static final int LEFT_V = 1;
    private static final int RIGHT_Z = 2;
    private static final int RIGHT_V = 3;
    private static final int VALUES_PER_LAYER = 4;

    /** 轮廓属于哪根骨骼 */
    public enum Frame {
        /** 父骨骼（大臂 / 大腿），层 0..{@link #JOINT_LAYER} 有效 */
        UPPER,
        /** 子骨骼（小臂 / 小腿），层 {@link #JOINT_LAYER}..{@link #LAYER_COUNT} - 1 有效 */
        LOWER
    }

    /**
     * 关节朝哪一边弯。手肘朝前、膝盖朝后，数值规则完全一样，只是图左落在相反的 z 上；
     * 这个方向同时也决定了子骨骼该往哪边转，见 {@code BentLimbLayer}。
     */
    public enum Bend {
        /** 往前弯（手肘）：图左在身后，z = 2 - u */
        FORWARD,
        /** 往后弯（膝盖）：图左在身前，z = u - 2 */
        BACKWARD
    }

    private final float[] upper = new float[LAYER_COUNT * VALUES_PER_LAYER];
    private final float[] lower = new float[LAYER_COUNT * VALUES_PER_LAYER];
    private final Bend bend;
    private final float inflate;

    private LimbProfile(Bend bend, float inflate) {
        this.bend = bend;
        this.inflate = inflate;
    }

    /**
     * 把关节角换算成 {@code t = tan(p / 2)}，并夹在 [0, {@link #MAX_T}] 里。
     */
    public static float toT(float angleRadians) {
        if (angleRadians < MIN_ANGLE) {
            return 0.0F;
        }

        return Math.min((float) Math.tan(angleRadians * 0.5D), MAX_T);
    }

    /**
     * 按 {@code t = tan(p / 2)} 算出所有层点，超范围的 {@code t} 会被夹进 [0, {@link #MAX_T}]。
     */
    public static LimbProfile of(float t, Bend bend) {
        return of(t, bend, 0.0F);
    }

    /**
     * 同上，并让整条轮廓沿外法线往外涨 {@code inflate} 像素——套在肢体外面的盔甲外壳就是这么来的。
     *
     * <p>直段是原地平移 {@code inflate}：厚度 4 变成 4 + 2·inflate，长度 12 变成 12 + 2·inflate，
     * 和原版涨过的盒体一致；弯折处在角平分线上走 {@code inflate / cos(θ/2)}，转角外侧因此不会漏出
     * 一截皮肤。整条轮廓用的是同一个 {@code inflate}，所以外壳到皮肤的距离处处相等。
     */
    public static LimbProfile of(float t, Bend bend, float inflate) {
        LimbProfile profile = new LimbProfile(bend, Math.max(0.0F, inflate));
        profile.compute(Math.clamp(t, 0.0F, MAX_T));
        return profile;
    }

    /** 这份轮廓朝哪一边弯 */
    public Bend bend() {
        return this.bend;
    }

    /** {@code frame} 坐标系里第 {@code layer} 层左点的 z（左 = 侧视图的左边界） */
    public float leftZ(Frame frame, int layer) {
        return this.values(frame)[layer * VALUES_PER_LAYER + LEFT_Z];
    }

    /** {@code frame} 坐标系里第 {@code layer} 层左点的 v */
    public float leftV(Frame frame, int layer) {
        return this.values(frame)[layer * VALUES_PER_LAYER + LEFT_V];
    }

    /** {@code frame} 坐标系里第 {@code layer} 层右点的 z（右 = 侧视图的右边界） */
    public float rightZ(Frame frame, int layer) {
        return this.values(frame)[layer * VALUES_PER_LAYER + RIGHT_Z];
    }

    /** {@code frame} 坐标系里第 {@code layer} 层右点的 v */
    public float rightV(Frame frame, int layer) {
        return this.values(frame)[layer * VALUES_PER_LAYER + RIGHT_V];
    }

    private float[] values(Frame frame) {
        return frame == Frame.UPPER ? this.upper : this.lower;
    }

    /**
     * 算出 7 层轮廓。所有点先摆进父骨骼坐标系（子骨骼那两截先绕关节转过来），整条折线一起等距外扩，
     * 之后再拆回两份——接缝处的点是同一个点转过去 / 转回来，两个坐标系里的坐标因此始终重合。
     */
    private void compute(float t) {
        float t2 = t * t;
        float den = 1.0F + t2;
        boolean folded = t >= 1.0F;
        // 左列落在 bend 那一侧的 z 上，外法线也朝那一侧
        float outward = this.bend == Bend.FORWARD ? 1.0F : -1.0F;

        // 子骨骼坐标系 → 父骨骼坐标系：绕关节 (z = 0, v = 6) 转过 -p（手肘往前抬）/ +p（膝盖往后抬）
        float turnSin = -outward * 2.0F * t / den;
        float turnCos = (1.0F - t2) / den;

        // 父骨骼坐标系里的整条折线，每层 4 个数
        float[] points = new float[LAYER_COUNT * VALUES_PER_LAYER];

        // 层 0：M(0, 0)、N(4, 0)——顶端，永远不动
        this.place(points, 0, 0.0F, 0.0F, 4.0F, 0.0F);
        // 层 1：K(0, 2)、L(4, 2)
        this.place(points, 1, 0.0F, 2.0F, 4.0F, 2.0F);
        // 层 2：A(0, 4)、B(4, 4)，B 在 90° 之后顺着右边界往末端的方向并到 D 上
        this.place(points, 2, 0.0F, 4.0F, 4.0F, Math.min(4.0F, 6.0F - 2.0F * t));
        // 层 3：C 沿左边界往顶端的方向移，D 沿右边界往末端的方向移
        this.place(points, 3, 0.0F, 6.0F + 2.0F * t, 4.0F, 6.0F - 2.0F * t);
        // 层 4：E、F 绕关节 (2, 6) 转过 p，F 在 90° 之后并到 B、D 上
        this.place(
            points,
            JOINT_LAYER,
            4.0F * t * (t + 1.0F) / den,
            4.0F * (t2 + t + 2.0F) / den,
            folded ? 4.0F : 4.0F * (1.0F + t) / den,
            folded ? 6.0F - 2.0F * t : 4.0F * (t2 - t + 2.0F) / den
        );
        // 层 5、6：子骨骼坐标系里的原坐标，先转到父坐标系，整条折线才好一起外扩
        this.place(points, 5, 0.0F, 10.0F, 4.0F, 10.0F);
        turn(points, 5, turnSin, turnCos);
        this.place(points, 6, 0.0F, 12.0F, 4.0F, 12.0F);
        turn(points, 6, turnSin, turnCos);

        // 两列各沿外法线整条挪出去：左列朝 bend 那一侧，右列朝反方向。
        // 皮肤那层 inflate 是 0，offset 是恒等变换，直接跳过省掉每段六条边的法线计算
        if (this.inflate > 0.0F) {
            this.offset(points, LEFT_Z, LEFT_V, outward);
            this.offset(points, RIGHT_Z, RIGHT_V, -outward);
        }

        // 父骨骼直接用层 0..JOINT_LAYER
        System.arraycopy(points, 0, this.upper, 0, (JOINT_LAYER + 1) * VALUES_PER_LAYER);

        // 子骨骼的层 4..6：同一个点反向转过 p，正好落回「没转之前」的坐标系
        for (int layer = JOINT_LAYER; layer < LAYER_COUNT; layer++) {
            int i = layer * VALUES_PER_LAYER;
            this.putLower(layer, points[i + LEFT_Z], points[i + LEFT_V], points[i + RIGHT_Z], points[i + RIGHT_V], -turnSin, turnCos);
        }
    }

    /** 侧视图的 (u, v) 折到父骨骼坐标系的 (z, v)；左 / 右落到哪个 z 由 {@link #bend} 决定 */
    private void place(float[] points, int layer, float uLeft, float vLeft, float uRight, float vRight) {
        float half = THICKNESS * 0.5F;
        int i = layer * VALUES_PER_LAYER;
        points[i + LEFT_Z] = this.bend == Bend.FORWARD ? half - uLeft : uLeft - half;
        points[i + LEFT_V] = vLeft;
        points[i + RIGHT_Z] = this.bend == Bend.FORWARD ? half - uRight : uRight - half;
        points[i + RIGHT_V] = vRight;
    }

    /** 把整层绕关节原地转过 p */
    private static void turn(float[] points, int layer, float sin, float cos) {
        int i = layer * VALUES_PER_LAYER;
        turnPoint(points, i + LEFT_Z, i + LEFT_V, sin, cos);
        turnPoint(points, i + RIGHT_Z, i + RIGHT_V, sin, cos);
    }

    private static void turnPoint(float[] points, int zIndex, int vIndex, float sin, float cos) {
        float dz = points[zIndex];
        float dv = points[vIndex] - JOINT_V;
        points[zIndex] = dv * sin + dz * cos;
        points[vIndex] = dv * cos - dz * sin + JOINT_V;
    }

    /** 把父骨骼坐标系里的点反向转过 p 写进子骨骼那一份 */
    private void putLower(int layer, float leftZ, float leftV, float rightZ, float rightV, float sin, float cos) {
        int i = layer * VALUES_PER_LAYER;
        this.lower[i + LEFT_Z] = (leftV - JOINT_V) * sin + leftZ * cos;
        this.lower[i + LEFT_V] = (leftV - JOINT_V) * cos - leftZ * sin + JOINT_V;
        this.lower[i + RIGHT_Z] = (rightV - JOINT_V) * sin + rightZ * cos;
        this.lower[i + RIGHT_V] = (rightV - JOINT_V) * cos - rightZ * sin + JOINT_V;
    }

    /**
     * 把一列点（{@code points} 里的 {@code zIndex} / {@code vIndex} 两列）沿外法线整条挪
     * {@code inflate} 像素。{@code outward} 是这一列朝哪一侧算外。
     *
     * <p>每段先求出方向和外法线，再让每个点沿相邻两段的角平分线走——直段就是平移 {@code inflate}，
     * 折点走 {@code inflate / cos(θ/2)}，正好让折点两侧的边都和皮肤的对应边隔开 {@code inflate}。
     * 顶端 / 末端补的是「端盖」那一条边，它的外法线就是肢体长度方向的反向 / 正向，所以端盖跟着
     * 一起涨，角点沿对角线走出去，跟原版盒体四个角的表现一样。
     *
     * <p>弯过 90° 之后 B、D、F 会并到一起，剩下的碎段不足半像素；这时向两侧找最近还有方向的段，
     * 跨过退化段的地方不做角平分线放大（那种位置已经没有"折角"可言），只平移 {@code inflate}。
     */
    private void offset(float[] points, int zIndex, int vIndex, float outward) {
        int segments = LAYER_COUNT - 1;
        float[] dirZ = new float[segments];
        float[] dirV = new float[segments];
        float[] normalZ = new float[segments];
        float[] normalV = new float[segments];
        boolean[] usable = new boolean[segments];

        for (int i = 0; i < segments; i++) {
            float dz = points[(i + 1) * VALUES_PER_LAYER + zIndex] - points[i * VALUES_PER_LAYER + zIndex];
            float dv = points[(i + 1) * VALUES_PER_LAYER + vIndex] - points[i * VALUES_PER_LAYER + vIndex];
            float length = (float) Math.sqrt(dz * dz + dv * dv);
            usable[i] = length > MIN_SEGMENT;
            if (usable[i]) {
                dirZ[i] = dz / length;
                dirV[i] = dv / length;
                // 朝 +z 一侧的外法线是 (dv, -dz)
                normalZ[i] = outward * dirV[i];
                normalV[i] = -outward * dirZ[i];
            }
        }

        float[] moved = points.clone();
        for (int i = 0; i < LAYER_COUNT; i++) {
            int before = nearestSegment(usable, i - 1, -1);
            int after = nearestSegment(usable, i, 1);

            float sumZ = 0.0F;
            float sumV = 0.0F;
            int count = 0;
            if (before >= 0) {
                sumZ += normalZ[before];
                sumV += normalV[before];
                count++;
            }
            if (after >= 0) {
                sumZ += normalZ[after];
                sumV += normalV[after];
                count++;
            }
            if (i == 0 && after >= 0) {
                // 顶端端盖朝肢体顶部的反方向
                sumZ -= dirZ[after];
                sumV -= dirV[after];
                count++;
            }
            if (i == LAYER_COUNT - 1 && before >= 0) {
                // 末端端盖朝末端方向
                sumZ += dirZ[before];
                sumV += dirV[before];
                count++;
            }

            float length = (float) Math.sqrt(sumZ * sumZ + sumV * sumV);
            if (count == 0 || length < MIN_NORMAL) {
                // 整条列都退化这种极端情况下没有方向可言，这点就不涨了
                continue;
            }

            // 只有「两条边真的挨着」才是折角，才走 2d / |sum|² 的角平分线；跨过退化段的地方只平移
            boolean corner = (before < 0 || before == i - 1) && (after < 0 || after == i) && count == 2;
            float scale = corner ? 2.0F * this.inflate / (length * length) : this.inflate / length;
            float offZ = sumZ * scale;
            float offV = sumV * scale;

            float offsetLength = (float) Math.sqrt(offZ * offZ + offV * offV);
            float limit = MAX_MITER * this.inflate;
            if (offsetLength > limit) {
                offZ *= limit / offsetLength;
                offV *= limit / offsetLength;
            }

            int base = i * VALUES_PER_LAYER;
            moved[base + zIndex] = points[base + zIndex] + offZ;
            moved[base + vIndex] = points[base + vIndex] + offV;
        }

        System.arraycopy(moved, 0, points, 0, points.length);
    }

    /** 从 {@code from} 开始按 {@code step} 找最近一条还有方向的段，找不到返回 -1 */
    private static int nearestSegment(boolean[] usable, int from, int step) {
        for (int i = from; i >= 0 && i < usable.length; i += step) {
            if (usable[i]) {
                return i;
            }
        }

        return -1;
    }
}
