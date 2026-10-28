package net.ecmoons.client.ability;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.ecmoons.EcMoons;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Matrix3f;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3f;

/**
 * Draws the wisp and the soul observers as a small, full-bright, camera-facing soul-flame sprite with a 4-frame
 * flicker (16x64 texture, frames stacked vertically). No model, no entity shadow.
 */
public class SoulSpriteRenderer<T extends Entity> extends EntityRenderer<T> {
    private final ResourceLocation texture;
    private final float size;
    public static int drawn; // tests

    public SoulSpriteRenderer(EntityRendererManager manager, String name, float size) {
        super(manager);
        this.texture = new ResourceLocation(EcMoons.MOD_ID, "textures/entity/" + name + ".png");
        this.size = size;
        this.shadowRadius = 0;
    }

    @Override
    public void render(T entity, float yaw, float pt, MatrixStack ms, IRenderTypeBuffer buffers, int light) {
        float t = entity.tickCount + pt;
        int frame = ((int) (t / 3) + entity.getId()) & 3;
        float v0 = frame / 4f, v1 = (frame + 1) / 4f;
        float s = size * (1f + 0.06f * (float) Math.sin(t * 0.35));
        ms.pushPose();
        ms.translate(0, entity.getBbHeight() * 0.5, 0);
        ms.mulPose(entityRenderDispatcher.cameraOrientation());
        ms.mulPose(Vector3f.YP.rotationDegrees(180f));
        ms.scale(s, s, s);
        IVertexBuilder vb = buffers.getBuffer(RenderType.entityTranslucent(texture));
        Matrix4f m = ms.last().pose();
        Matrix3f n = ms.last().normal();
        vertex(vb, m, n, -0.5f, -0.5f, 0f, v1);
        vertex(vb, m, n, 0.5f, -0.5f, 1f, v1);
        vertex(vb, m, n, 0.5f, 0.5f, 1f, v0);
        vertex(vb, m, n, -0.5f, 0.5f, 0f, v0);
        ms.popPose();
        drawn++;
        super.render(entity, yaw, pt, ms, buffers, light);
    }

    private static void vertex(IVertexBuilder vb, Matrix4f m, Matrix3f n, float x, float y, float u, float v) {
        vb.vertex(m, x, y, 0).color(255, 255, 255, 235).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0)
                .normal(n, 0, 1, 0).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return texture;
    }
}
