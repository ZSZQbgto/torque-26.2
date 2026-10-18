package com.zszq.torque.client.model;

/**
 * 一条肢体（手臂或腿）在贴图上占的区域，连同它在模型里的盒体摆放。
 *
 * <p>原版 {@code ModelPart.Cube} 把盒体的四个侧面按 x-、-z、x+、+z 的顺序摊在贴图上，四列的宽度依次是
 * <b>前后厚度、左右宽度、前后厚度、左右宽度</b>；v 方向上面 4 行再放端盖。所以 {@code depth} 和
 * {@code width} 都得留着，正常手臂两者都是 4 像素，slim 的宽度只有 3 像素：
 *
 * <pre>
 *   列    normal arm (40, 16)   slim arm (40, 16)
 *   x-    u 40..44   宽 4       u 40..44   宽 4
 *   -z    u 44..48   宽 4       u 44..47   宽 3
 *   x+    u 48..52   宽 4       u 47..51   宽 4
 *   +z    u 52..56   宽 4       u 51..54   宽 3
 * </pre>
 *
 * 按固定步长 4 去写就会让 slim 一路偏过去，最后一列直接采到皮肤上的空白区域。
 *
 * <p>套在肢体外面的盔甲外壳用 {@link #shell}：几何体整圈往外涨 {@code inflate} 像素，贴图列宽却还是按
 * 盒体本来的尺寸算。原版 {@code Cube} 也是「用没涨过的尺寸取 UV、用涨过的尺寸摆顶点」，盔甲贴图就是照着
 * 这个规矩画的，所以两层盒体可以共用同一套列宽。
 *
 * <p>{@code mirror} 是给左侧肢体用的：原版盔甲贴图左右共用一块区域，左侧靠水平镜像铺上去。
 * {@code texHeight} 是贴图的边长：皮肤 64，盔甲 32——盔甲贴图只有皮肤的一半高，v 得按 32 归一化。
 */
public record LimbBox(
    float texU,
    float texV,
    float minX,
    float maxX,
    float topY,
    float depth,
    float inflate,
    boolean mirror,
    float texHeight
) {
    /** 原版肢体的前后厚度：手臂和腿都是 4 像素 */
    public static final float DEFAULT_DEPTH = 4.0F;
    /** 皮肤贴图是 64 × 64 */
    public static final float SKIN_TEX_SIZE = 64.0F;
    /** 盔甲贴图是 64 × 32 */
    public static final float ARMOR_TEX_SIZE = 32.0F;

    /** 皮肤上的肢体：贴图 64 × 64，不涨、不镜像 */
    public LimbBox(float texU, float texV, float minX, float maxX, float topY) {
        this(texU, texV, minX, maxX, topY, DEFAULT_DEPTH, 0.0F, false, SKIN_TEX_SIZE);
    }

    /**
     * 套在肢体外面的盔甲外壳：盒体四周各往外涨 {@code inflate} 像素，贴图换成 64 × 32 的盔甲图，
     * 左侧肢体（{@code mirror}）复用右侧那块区域。
     */
    public static LimbBox shell(float texU, float texV, float minX, float maxX, float topY, float inflate, boolean mirror) {
        return new LimbBox(texU, texV, minX, maxX, topY, DEFAULT_DEPTH, inflate, mirror, ARMOR_TEX_SIZE);
    }

    /** 盒体在 x 方向的宽度：正常手臂和腿都是 4 像素，slim 手臂 3 像素 */
    public float width() {
        return this.maxX - this.minX;
    }

    /** 顶点真正落在的 x 范围：外壳比盒体四周各宽出 {@code inflate} 像素 */
    public float geomMinX() {
        return this.minX - this.inflate;
    }

    /** 顶点真正落在的 x 范围：外壳比盒体四周各宽出 {@code inflate} 像素 */
    public float geomMaxX() {
        return this.maxX + this.inflate;
    }

    /** 关节在模型坐标系里的 y：手臂是 4，腿是 6 */
    public float jointY() {
        return this.topY + LimbProfile.JOINT_V;
    }

    /** x = minX 那一列（贴图 x-）的左沿 */
    public float u0() {
        return this.texU;
    }

    /** x- 列与身前那列的分界 */
    public float u1() {
        return this.texU + this.depth;
    }

    /** 身前那列与 x+ 列的分界，同时也是端盖左边那条线 */
    public float u2() {
        return this.u1() + this.width();
    }

    /** x+ 列与身后那列的分界 */
    public float u3() {
        return this.u2() + this.depth;
    }

    /** 身后那列的右沿 */
    public float u4() {
        return this.u3() + this.width();
    }

    /** 端盖右边那条线 */
    public float u2PlusWidth() {
        return this.u2() + this.width();
    }
}
