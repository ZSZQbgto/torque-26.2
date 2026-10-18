package com.zszq.torque.client.model;

import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3f;

/**
 * 披风薄板的一段：宽 10、厚 1，长度由调用方给（局部 y 从 0 顺着 +y 长下去）。
 *
 * <p>贴图走 Minecraft 原版披风的那套单方块布局（{@code PlayerCapeModel} 的 {@code texOffs(0, 0)}）：
 * 列依次是 u 0..1（厚）、1..11（宽）、11..12（厚）、12..22（宽），行上每个盒子占 1 行端盖 + 本体若干行。
 * 贴图是 64 × 32（原版披风的 PNG 就这个尺寸），所以 v 用 32 归一化。
 *
 * <p>三段各取本体的连续行：{@code cape} 1..5、{@code capemid} 5..10、{@code capedown} 10..17，
 * 端盖用本体上面那一行（段与段之间的端盖在内部，看不见，行重叠无所谓）。
 */
public final class CapeMesh {
    /** 1 像素 = 1/16 格 */
    public static final float PIXEL = 0.0625F;

    /** 原版披风贴图的尺寸 */
    private static final float TEX_WIDTH = 64.0F;
    private static final float TEX_HEIGHT = 32.0F;

    /** 单方块的列：厚 1、宽 10、厚 1、宽 10 */
    private static final float U0 = 0.0F;
    private static final float U1 = 1.0F;
    private static final float U2 = 11.0F;
    private static final float U3 = 12.0F;
    private static final float U4 = 22.0F;
    /** 底部端盖的右沿：u2 再往右一个宽度 */
    private static final float U2_PLUS_WIDTH = 21.0F;

    /** 披风的横截面：宽 10（x -5..5）、厚 1（z -1..0），和原版披风一致 */
    private static final float MIN_X = -5.0F;
    private static final float MAX_X = 5.0F;
    private static final float MIN_Z = -1.0F;
    private static final float MAX_Z = 0.0F;

    /**
     * 画一段披风。{@code length} 是这一段沿长度方向的像素数，{@code vFrom} 是本体第一行在贴图上的行号
     * （原版从 1 开始，0 那一行是顶端的端盖）。
     */
    public void render(VertexConsumer buffer, Pose pose, float length, float vFrom, int lightCoords, int overlayCoords, int color) {
        // 一段里所有面共用一个法线暂存，省掉每面一次小对象分配
        Vector3f normal = new Vector3f();
        float capV0 = vFrom - 1.0F;
        float vTo = vFrom + length;

        // 顶（y = 0）、底（y = length）两个端盖：都用本体上面那一行
        this.quad(
            buffer, pose, lightCoords, overlayCoords, color, normal, 0.0F, -1.0F, 0.0F,
            MAX_X, 0.0F, MAX_Z, U2, capV0,
            MIN_X, 0.0F, MAX_Z, U1, capV0,
            MIN_X, 0.0F, MIN_Z, U1, vFrom,
            MAX_X, 0.0F, MIN_Z, U2, vFrom
        );
        this.quad(
            buffer, pose, lightCoords, overlayCoords, color, normal, 0.0F, 1.0F, 0.0F,
            MAX_X, length, MIN_Z, U2_PLUS_WIDTH, vFrom,
            MIN_X, length, MIN_Z, U2, vFrom,
            MIN_X, length, MAX_Z, U2, capV0,
            MAX_X, length, MAX_Z, U2_PLUS_WIDTH, capV0
        );
        // 左右两条窄边：各自的 1 像素列
        this.quad(
            buffer, pose, lightCoords, overlayCoords, color, normal, -1.0F, 0.0F, 0.0F,
            MIN_X, 0.0F, MIN_Z, U1, vFrom,
            MIN_X, 0.0F, MAX_Z, U0, vFrom,
            MIN_X, length, MAX_Z, U0, vTo,
            MIN_X, length, MIN_Z, U1, vTo
        );
        this.quad(
            buffer, pose, lightCoords, overlayCoords, color, normal, 1.0F, 0.0F, 0.0F,
            MAX_X, 0.0F, MAX_Z, U3, vFrom,
            MAX_X, 0.0F, MIN_Z, U2, vFrom,
            MAX_X, length, MIN_Z, U2, vTo,
            MAX_X, length, MAX_Z, U3, vTo
        );
        // 前后两个大面：各占 10 像素宽
        this.quad(
            buffer, pose, lightCoords, overlayCoords, color, normal, 0.0F, 0.0F, -1.0F,
            MAX_X, 0.0F, MIN_Z, U2, vFrom,
            MIN_X, 0.0F, MIN_Z, U1, vFrom,
            MIN_X, length, MIN_Z, U1, vTo,
            MAX_X, length, MIN_Z, U2, vTo
        );
        this.quad(
            buffer, pose, lightCoords, overlayCoords, color, normal, 0.0F, 0.0F, 1.0F,
            MIN_X, 0.0F, MAX_Z, U4, vFrom,
            MAX_X, 0.0F, MAX_Z, U3, vFrom,
            MAX_X, length, MAX_Z, U3, vTo,
            MIN_X, length, MAX_Z, U4, vTo
        );
    }

    /** 一个四边形，顶点按「从外侧看是逆时针」给，法线由调用方给（薄板都是轴对齐的） */
    private void quad(
        VertexConsumer buffer,
        Pose pose,
        int lightCoords,
        int overlayCoords,
        int color,
        Vector3f normal,
        float normalX,
        float normalY,
        float normalZ,
        float x0, float y0, float z0, float u0, float v0,
        float x1, float y1, float z1, float u1, float v1,
        float x2, float y2, float z2, float u2, float v2,
        float x3, float y3, float z3, float u3, float v3
    ) {
        pose.transformNormal(normalX, normalY, normalZ, normal);
        this.vertex(buffer, pose, lightCoords, overlayCoords, color, normal, x0, y0, z0, u0, v0);
        this.vertex(buffer, pose, lightCoords, overlayCoords, color, normal, x1, y1, z1, u1, v1);
        this.vertex(buffer, pose, lightCoords, overlayCoords, color, normal, x2, y2, z2, u2, v2);
        this.vertex(buffer, pose, lightCoords, overlayCoords, color, normal, x3, y3, z3, u3, v3);
    }

    private void vertex(
        VertexConsumer buffer,
        Pose pose,
        int lightCoords,
        int overlayCoords,
        int color,
        Vector3f normal,
        float x,
        float y,
        float z,
        float u,
        float v
    ) {
        buffer
            .addVertex(pose, x * PIXEL, y * PIXEL, z * PIXEL)
            // 和 LimbMesh 一样：setUv 要排在 setColor 前面，buffer 被 sprite 包过的时候才不会被绕过
            .setUv(u / TEX_WIDTH, v / TEX_HEIGHT)
            .setColor(color)
            .setOverlay(overlayCoords)
            .setLight(lightCoords)
            .setNormal(normal.x, normal.y, normal.z);
    }
}
