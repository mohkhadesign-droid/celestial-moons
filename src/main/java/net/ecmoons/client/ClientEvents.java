package net.ecmoons.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.ClientConfig;
import net.ecmoons.config.Settings;
import net.ecmoons.net.FrostStatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.potion.Effects;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderNameplateEvent;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;
import java.util.UUID;

/**
 * Client-only presentation: Mist fog and name tags, Frost overlay and particles, and Drift movement
 * (jump height and falling speed are simulated by the player's own client in 1.16, so they are applied here,
 * from the server's synchronised settings and Enhanced Celestials' synchronised current event).
 */
public final class ClientEvents {
    private static final ResourceLocation FROST_OVERLAY = new ResourceLocation(EcMoons.MOD_ID, "textures/gui/frost_overlay.png");
    private static final UUID DRIFT_GRAVITY = UUID.fromString("3c9c2f1e-5d1b-4f3e-8a77-0b4f1f6d2c19");
    private static final Random RANDOM = new Random();

    private static float mist;         // 0..1 fog strength, eased
    private static float frostOverlay; // 0..1 overlay strength, eased
    private static ClientWorld lastWorld;

    private ClientEvents() {}

    public static float mistFactor() {
        return mist;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientWorld world = mc.level;
        if (world != lastWorld) {
            lastWorld = world;
            mist = 0;
            frostOverlay = 0;
            ClientState.reset();
        }
        if (world == null || mc.player == null || mc.isPaused()) return;
        Settings s = Settings.client();

        float mistTarget = Moons.is(world, MoonType.MIST) ? 1F : 0F;
        float step = 1F / Math.max(1, 20 * s.i("mist.fadeSeconds"));
        mist = approach(mist, mistTarget, step);

        boolean frost = Moons.is(world, MoonType.FROST);
        float frostTarget = 0F;
        if (frost) {
            byte st = ClientState.frostState();
            if (st == FrostStatePacket.CHILLED) frostTarget = 1F;
            else if (st == FrostStatePacket.EXPOSED) frostTarget = 0.15F + 0.45F * ClientState.frostProgress();
        }
        frostOverlay = approach(frostOverlay, frostTarget, 0.02F);

        // Frost Moon ice crystals are part of the atmosphere particles (AtmoParticles), within the shared budget.
    }

    private static float approach(float value, float target, float step) {
        if (value < target) return Math.min(target, value + step);
        if (value > target) return Math.max(target, value - step);
        return value;
    }

    // ---- Mist --------------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderFog(EntityViewRenderEvent.RenderFogEvent event) {
        if (event.getType() != FogRenderer.FogType.FOG_TERRAIN) return;
        Minecraft mc = Minecraft.getInstance();
        float haze = mc.level == null ? 0F : net.ecmoons.client.atmo.Atmosphere.haze(mc.level, (float) event.getRenderPartialTicks());
        if (mist <= 0.001F && haze <= 0.001F) return;
        if (!event.getInfo().getFluidInCamera().isEmpty()) return; // keep vanilla water/lava fog
        Entity cam = event.getInfo().getEntity();
        if (cam instanceof LivingEntity && ((LivingEntity) cam).hasEffect(Effects.BLINDNESS)) return;
        Settings s = Settings.client();
        float far = event.getFarPlaneDistance();
        float vanillaStart = far * 0.75F;
        float start = MathHelper.lerp(mist, vanillaStart, (float) s.d("mist.fogStart"));
        float end = MathHelper.lerp(mist, far, (float) s.d("mist.fogEnd"));
        // atmosphere haze: distant terrain fades into the moon's horizon colour; never closer than 48 blocks
        // (the War arena stays fully visible for combat)
        if (haze > 0F) {
            float hazeEnd = Math.max(48F, far * (1F - 0.45F * haze));
            float hazeStart = Math.max(8F, far * (0.75F - 0.6F * haze));
            if (hazeEnd < end) end = hazeEnd;
            if (hazeStart < start) start = hazeStart;
        }
        end = Math.min(end, far);
        start = Math.min(start, end - 1F);
        RenderSystem.fogStart(start);
        RenderSystem.fogEnd(end);
        RenderSystem.fogMode(GlStateManager.FogMode.LINEAR);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFogColor(EntityViewRenderEvent.FogColors event) {
        if (mist <= 0.001F) return;
        if (!event.getInfo().getFluidInCamera().isEmpty()) return;
        float blend = mist * (float) Settings.client().d("mist.fogColorBlend");
        float r = event.getRed(), g = event.getGreen(), b = event.getBlue();
        float grey = 0.30F * r + 0.59F * g + 0.11F * b + 0.06F;
        event.setRed(MathHelper.lerp(blend, r, grey * 0.97F));
        event.setGreen(MathHelper.lerp(blend, g, grey));
        event.setBlue(MathHelper.lerp(blend, b, grey * 1.05F));
    }

    /** Belt and braces with the server-side name-tag distance attribute (MistFeatures). */
    @SubscribeEvent
    public static void onNameplate(RenderNameplateEvent event) {
        if (mist < 0.5F || !(event.getEntity() instanceof PlayerEntity)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getEntity() == mc.player) return;
        double max = Settings.client().d("mist.nameplateMaxDistance");
        if (event.getEntity().distanceToSqr(mc.player) > max * max) event.setResult(Event.Result.DENY);
    }

    // ---- Frost overlay -------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onOverlay(RenderGameOverlayEvent.Pre event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        if (frostOverlay <= 0.01F || !ClientConfig.FROST_OVERLAY.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        float alpha = frostOverlay * ClientConfig.FROST_OVERLAY_OPACITY.get().floatValue();
        int w = event.getWindow().getGuiScaledWidth();
        int h = event.getWindow().getGuiScaledHeight();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, alpha);
        mc.getTextureManager().bind(FROST_OVERLAY);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuilder();
        buffer.begin(7, DefaultVertexFormats.POSITION_TEX);
        buffer.vertex(0.0D, h, -90.0D).uv(0.0F, 1.0F).endVertex();
        buffer.vertex(w, h, -90.0D).uv(1.0F, 1.0F).endVertex();
        buffer.vertex(w, 0.0D, -90.0D).uv(1.0F, 0.0F).endVertex();
        buffer.vertex(0.0D, 0.0D, -90.0D).uv(0.0F, 0.0F).endVertex();
        tessellator.end();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ---- Drift movement (local player only) ------------------------------------------------------------------

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getEntityLiving() != mc.player || mc.player == null) return;
        if (!Moons.is(mc.player.level, MoonType.DRIFT)) return;
        Vector3d v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(v.x, v.y + Settings.client().d("drift.jumpBoost"), v.z);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !(event.player instanceof ClientPlayerEntity)) return;
        ClientPlayerEntity player = (ClientPlayerEntity) event.player;
        if (player != Minecraft.getInstance().player) return;
        ModifiableAttributeInstance gravity = player.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
        if (gravity == null) return;
        boolean falling = player.getDeltaMovement().y <= 0 && !player.isOnGround() && !player.abilities.flying
                && !player.isFallFlying() && !player.isInWater() && !player.isInLava() && !player.isPassenger();
        boolean apply = falling && Moons.is(player.level, MoonType.DRIFT);
        AttributeModifier existing = gravity.getModifier(DRIFT_GRAVITY);
        if (apply) {
            double amount = Settings.client().d("drift.gravityMultiplier") - 1.0;
            if (existing == null || existing.getAmount() != amount) {
                if (existing != null) gravity.removeModifier(DRIFT_GRAVITY);
                gravity.addTransientModifier(new AttributeModifier(DRIFT_GRAVITY, "ecmoons drift", amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
            }
        } else if (existing != null) {
            gravity.removeModifier(DRIFT_GRAVITY);
        }
    }
}
