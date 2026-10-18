package com.zszq.torque.client.model;

import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * 把 {@link LimbProfile} 的侧面轮廓铺成一块块带贴图的四边形，画进 {@link VertexConsumer}。
 *
 * <p>肢体是侧面轮廓沿 x 方向拉出来的：每一段有 4 个面——两个大侧面（x 两端）就是轮廓
 * 本身，轮廓前后两列点又各沿 x 铺出一条窄面；两端再补上顶端、末端两个端盖，整条肢体就是封闭的。
 * 每段占贴图上 2 行，6 段正好吃满原版肢体侧面的 12 行。
 *
 * <p>贴图只用原版皮肤里这条肢体那一块，按 {@link LimbBox} 算出来的四列摆放：
 *
 * <pre>
 *          u0    u1    u2    u3    u4
 *   +v     ┌─────┬───────────┬─────┐
 *          │  x- │  端盖 (4×4) │  x+ │
 *          ├─────┼─────┬─────┼─────┤
 *          │  x- │ -z  │  x+ │ +z  │  6 段 × 2 行 = 12 行
 *          └─────┴─────┴─────┴─────┘
 * </pre>
 *
 * normal 的四列都是 4 像素，slim 的宽度只有 3，所以 -z 和 +z 那两列会窄一个像素；
 * 具体边界一律由 {@link LimbBox} 算，不能按固定步长写死。
 *
 * <p>盔甲外壳复用同一套铺法，差别只有三处：x 范围按 {@link LimbBox#geomMinX()} 往外套一圈、
 * 贴图高度按 64 × 32 归一化（{@link LimbBox#texHeight()}）、左侧肢体把整块几何体沿盒体中线翻过来再
 * 反一次顶点顺序（{@link LimbBox#mirror()}），也就是原版 {@code Cube} 的 mirror。
 */
public final class LimbMesh {
    /** 1 像素 = 1/16 格 */
    public static final float PIXEL = 0.0625F;

    /** 贴图的宽度（像素）：皮肤和盔甲都是 64 */
    private static final float TEXTURE_WIDTH = 64.0F;
    /** 每段在贴图上占的行数 */
    private static final float ROWS_PER_SEGMENT = 2.0F;
    /** 一个面 4 个顶点，每个顶点 5 个数：x、y、z、u、v */
    private static final int VERTEX_STRIDE = 5;
    private static final int FACE_SIZE = 4 * VERTEX_STRIDE;
    /** 法线长度小于这个值就当作退化面，直接给 0 */
    private static final float MIN_NORMAL_LENGTH = 1.0E-6F;

    /**
     * 画父骨骼那半条肢体（大臂 / 大腿）：层 0 到第 {@link LimbProfile#JOINT_LAYER} 层之间的 4 段，
     * 加上顶端的端盖。调用前 PoseStack 里必须已经放过肢体骨骼的变换。
     */
    public void renderParent(VertexConsumer buffer, Pose pose, LimbProfile profile, LimbBox box, int lightCoords, int overlayCoords, int color) {
        Painter painter = new Painter(buffer, pose, profile, box, lightCoords, overlayCoords, color);

        for (int layer = 0; layer < LimbProfile.JOINT_LAYER; layer++) {
            painter.segment(LimbProfile.Frame.UPPER, layer, layer + 1);
        }

        painter.parentCap();
    }

    /**
     * 画子骨骼那半条肢体（小臂 / 小腿）：层 {@link LimbProfile#JOINT_LAYER} 到最后一层之间的 2 段，
     * 加上末端的端盖。调用前 PoseStack 里必须已经放过「绕关节转过 p」的子骨骼变换。
     */
    public void renderChild(VertexConsumer buffer, Pose pose, LimbProfile profile, LimbBox box, int lightCoords, int overlayCoords, int color) {
        Painter painter = new Painter(buffer, pose, profile, box, lightCoords, overlayCoords, color);

        for (int layer = LimbProfile.JOINT_LAYER; layer < LimbProfile.LAYER_COUNT - 1; layer++) {
            painter.segment(LimbProfile.Frame.LOWER, layer, layer + 1);
        }

        painter.childCap();
    }

    /**
     * 一次绘制的暂存：绑好顶点缓冲、贴图位置和光照，然后一段一段地往上摆面。
     * 每次 {@code render*} 都新建一个，所以 {@link LimbMesh} 自己没有状态，可以放心复用。
     */
    private static final class Painter {
        private final VertexConsumer buffer;
        private final Pose pose;
        private final LimbProfile profile;
        private final LimbBox box;
        private final int lightCoords;
        private final int overlayCoords;
        private final int color;
        /**
         * 图左那一点是不是落在 z 较大的那一侧（身后）。手肘往前弯，图左在身后；膝盖往后弯，图左在身前。
         * 摆面时一律按"身前 / 身后"两列来取点，所以只要记住这一位，手和脚就共用同一套贴图规则。
         */
        private final boolean leftIsFar;
        /** 当前这个面的 4 个顶点 */
        private final float[] face = new float[FACE_SIZE];
        private final Vector3f normal = new Vector3f();
        /** 端盖用的两行贴图：本体第一行，以及隔一个 depth 的那一行 */
        private final float capV;
        private final float capVFar;

        private Painter(VertexConsumer buffer, Pose pose, LimbProfile profile, LimbBox box, int lightCoords, int overlayCoords, int color) {
            this.buffer = buffer;
            this.pose = pose;
            this.profile = profile;
            this.box = box;
            this.lightCoords = lightCoords;
            this.overlayCoords = overlayCoords;
            this.color = color;
            this.leftIsFar = profile.leftZ(LimbProfile.Frame.UPPER, 0) > profile.rightZ(LimbProfile.Frame.UPPER, 0);
            this.capV = box.texV();
            this.capVFar = this.capV + box.depth();
        }

        /** 一段两端在「身后 / 身前」两列上的取点：z 和轮廓 v */
        private record Ring(float farZ, float farV, float nearZ, float nearV) {
        }

        private Ring ring(LimbProfile.Frame frame, int layer) {
            return new Ring(this.farZ(frame, layer), this.farV(frame, layer), this.nearZ(frame, layer), this.nearV(frame, layer));
        }

        /** 第 {@code layerFrom} 层到第 {@code layerTo} 层之间的 4 个面；贴图行号就是 {@code layerFrom} */
        private void segment(LimbProfile.Frame frame, int layerFrom, int layerTo) {
            float vFrom = this.box.texV() + this.box.depth() + ROWS_PER_SEGMENT * layerFrom;
            float vTo = vFrom + ROWS_PER_SEGMENT;
            Ring from = this.ring(frame, layerFrom);
            Ring to = this.ring(frame, layerTo);

            float x0 = this.box.geomMinX();
            float x1 = this.box.geomMaxX();
            float u0 = this.box.u0();
            float u1 = this.box.u1();
            float u2 = this.box.u2();
            float u3 = this.box.u3();
            float u4 = this.box.u4();

            // 两个大侧面：轮廓本身。x 较小那侧贴 x- 列，较大那侧左右对调
            this.sideFace(x0, from, to, u0, vFrom, u1, vTo, false);
            this.sideFace(x1, from, to, u3, vFrom, u2, vTo, true);
            // 身前、身后两列点各沿 x 铺出一条窄面
            this.edgeFace(from, to, x0, x1, u1, u2, vFrom, vTo, true);
            this.edgeFace(from, to, x0, x1, u4, u3, vFrom, vTo, false);
        }

        /** 一个 x 固定的大侧面：沿轮廓从身后到身前铺一圈；{@code mirrored} 时顶点顺序反过来（法线朝外） */
        private void sideFace(float x, Ring from, Ring to, float uFar, float vFrom, float uNear, float vTo, boolean mirrored) {
            if (mirrored) {
                this.quad(
                    x, this.y(from.farV()), from.farZ(), uFar, vFrom,
                    x, this.y(from.nearV()), from.nearZ(), uNear, vFrom,
                    x, this.y(to.nearV()), to.nearZ(), uNear, vTo,
                    x, this.y(to.farV()), to.farZ(), uFar, vTo
                );
            } else {
                this.quad(
                    x, this.y(from.farV()), from.farZ(), uFar, vFrom,
                    x, this.y(to.farV()), to.farZ(), uFar, vTo,
                    x, this.y(to.nearV()), to.nearZ(), uNear, vTo,
                    x, this.y(from.nearV()), from.nearZ(), uNear, vFrom
                );
            }
        }

        /** 一列点沿 x 铺出来的窄面：{@code front} 时取身前那一列，否则取身后那一列 */
        private void edgeFace(Ring from, Ring to, float x0, float x1, float uAtX0, float uAtX1, float vFrom, float vTo, boolean front) {
            if (front) {
                this.quad(
                    x0, this.y(from.nearV()), from.nearZ(), uAtX0, vFrom,
                    x0, this.y(to.nearV()), to.nearZ(), uAtX0, vTo,
                    x1, this.y(to.nearV()), to.nearZ(), uAtX1, vTo,
                    x1, this.y(from.nearV()), from.nearZ(), uAtX1, vFrom
                );
            } else {
                this.quad(
                    x0, this.y(from.farV()), from.farZ(), uAtX0, vFrom,
                    x1, this.y(from.farV()), from.farZ(), uAtX1, vFrom,
                    x1, this.y(to.farV()), to.farZ(), uAtX1, vTo,
                    x0, this.y(to.farV()), to.farZ(), uAtX0, vTo
                );
            }
        }

        /** 顶端端盖：第 0 层切下来的那一刀，法线朝下 */
        private void parentCap() {
            this.cap(LimbProfile.Frame.UPPER, 0, this.box.u1(), this.box.u2(), true);
        }

        /** 末端端盖：最后一层切下来的那一刀，法线朝上 */
        private void childCap() {
            this.cap(
                LimbProfile.Frame.LOWER,
                LimbProfile.LAYER_COUNT - 1,
                this.box.u2(),
                this.box.u2PlusWidth(),
                false
            );
        }

        /**
         * 一个端盖：在 {@code layer} 那一层切出来的四边形。
         *
         * @param nearFirst 是否按"身前 → 身后"的顺序摆顶点；两个端盖的法线朝向相反，所以要反过来一遍
         */
        private void cap(LimbProfile.Frame frame, int layer, float uLeft, float uRight, boolean nearFirst) {
            float y = this.y(this.profile.leftV(frame, layer));
            float nearZ = this.nearZ(frame, layer);
            float farZ = this.farZ(frame, layer);
            float x0 = this.box.geomMinX();
            float x1 = this.box.geomMaxX();

            if (nearFirst) {
                this.quad(
                    x0, y, nearZ, uLeft, this.capVFar,
                    x1, y, nearZ, uRight, this.capVFar,
                    x1, y, farZ, uRight, this.capV,
                    x0, y, farZ, uLeft, this.capV
                );
            } else {
                this.quad(
                    x0, y, farZ, uLeft, this.capV,
                    x1, y, farZ, uRight, this.capV,
                    x1, y, nearZ, uRight, this.capVFar,
                    x0, y, nearZ, uLeft, this.capVFar
                );
            }
        }

        /** 第 {@code layer} 层落在身前（z 较小）那一侧的点的 z */
        private float nearZ(LimbProfile.Frame frame, int layer) {
            return this.leftIsFar ? this.profile.rightZ(frame, layer) : this.profile.leftZ(frame, layer);
        }

        /** 第 {@code layer} 层落在身前那一侧的点的 v */
        private float nearV(LimbProfile.Frame frame, int layer) {
            return this.leftIsFar ? this.profile.rightV(frame, layer) : this.profile.leftV(frame, layer);
        }

        /** 第 {@code layer} 层落在身后（z 较大）那一侧的点的 z */
        private float farZ(LimbProfile.Frame frame, int layer) {
            return this.leftIsFar ? this.profile.leftZ(frame, layer) : this.profile.rightZ(frame, layer);
        }

        /** 第 {@code layer} 层落在身后那一侧的点的 v */
        private float farV(LimbProfile.Frame frame, int layer) {
            return this.leftIsFar ? this.profile.leftV(frame, layer) : this.profile.rightV(frame, layer);
        }

        /** 侧视图的 v 换成模型里的 y */
        private float y(float v) {
            return this.box.topY() + v;
        }

        /** 一个四边形。顶点要按「从外侧看是逆时针」给，法线由几何直接算出来 */
        private void quad(
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3
        ) {
            this.setVertex(0, x0, y0, z0, u0, v0);
            this.setVertex(1, x1, y1, z1, u1, v1);
            this.setVertex(2, x2, y2, z2, u2, v2);
            this.setVertex(3, x3, y3, z3, u3, v3);

            if (this.box.mirror()) {
                this.mirror();
            }

            // 面积向量用 Newell 法求。弯过 90° 之后 B、D、F 并成一点，四边形会退化成三角形，
            // 这时候只拿前三个顶点做叉积会得到零向量，光照会整个暗下去
            float nx = 0.0F;
            float ny = 0.0F;
            float nz = 0.0F;
            for (int i = 0; i < 4; i++) {
                int a = i * VERTEX_STRIDE;
                int b = ((i + 1) & 3) * VERTEX_STRIDE;
                float ax = this.face[a];
                float ay = this.face[a + 1];
                float az = this.face[a + 2];
                float bx = this.face[b];
                float by = this.face[b + 1];
                float bz = this.face[b + 2];
                nx += (ay - by) * (az + bz);
                ny += (az - bz) * (ax + bx);
                nz += (ax - bx) * (ay + by);
            }

            float length = Mth.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > MIN_NORMAL_LENGTH) {
                nx /= length;
                ny /= length;
                nz /= length;
            } else {
                // 真的退化成一个点或一条线，没有面积，法线给 0 就行
                nx = 0.0F;
                ny = 0.0F;
                nz = 0.0F;
            }

            this.pose.transformNormal(nx, ny, nz, this.normal);

            for (int i = 0; i < 4; i++) {
                int base = i * VERTEX_STRIDE;
                this.buffer
                    .addVertex(this.pose, this.face[base] * PIXEL, this.face[base + 1] * PIXEL, this.face[base + 2] * PIXEL)
                    // setUv 必须排在 setColor 前面：盔甲纹饰的 buffer 被 sprite 包了一层，
                    // 而 SpriteCoordinateExpander 的 setColor(int) 返回的是里面那个 buffer——
                    // 链式一旦走到它，后面的 setUv 就绕过图集坐标映射了，纹饰会采样到图集左上角
                    .setUv(this.face[base + 3] / TEXTURE_WIDTH, this.face[base + 4] / this.box.texHeight())
                    .setColor(this.color)
                    .setOverlay(this.overlayCoords)
                    .setLight(this.lightCoords)
                    .setNormal(this.normal.x, this.normal.y, this.normal.z);
            }
        }

        private void setVertex(int index, float x, float y, float z, float u, float v) {
            int base = index * VERTEX_STRIDE;
            this.face[base] = x;
            this.face[base + 1] = y;
            this.face[base + 2] = z;
            this.face[base + 3] = u;
            this.face[base + 4] = v;
        }

        /**
         * 左侧肢体：整块几何体沿盒体中线翻到另一边，再把顶点顺序倒过来。
         *
         * <p>贴图跟着顶点走，所以翻完之后原来挂在 x- 那一列的面跑到 x+ 去——这正是原版 {@code Cube}
         * 的 mirror：左右共用一块贴图，左边的图案是右边的镜像。顶点顺序一起倒过来，绕向和法线才仍然朝外。
         */
        private void mirror() {
            float center = this.box.geomMinX() + this.box.geomMaxX();
            for (int i = 0; i < 4; i++) {
                int base = i * VERTEX_STRIDE;
                this.face[base] = center - this.face[base];
            }

            for (int i = 0; i < 2; i++) {
                int a = i * VERTEX_STRIDE;
                int b = (3 - i) * VERTEX_STRIDE;
                for (int k = 0; k < VERTEX_STRIDE; k++) {
                    float tmp = this.face[a + k];
                    this.face[a + k] = this.face[b + k];
                    this.face[b + k] = tmp;
                }
            }
        }
    }
}
