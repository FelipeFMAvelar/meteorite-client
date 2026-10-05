/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Ambience;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void updateRenderState(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo ci) {
        Ambience ambience = Modules.get().get(Ambience.class);
        if (!ambience.isActive()) return;

        if (ambience.endSky.get()) state.skybox = DimensionType.Skybox.END;
        if (ambience.customSkyColor.get()) state.skyColor = ambience.skyColor().getVec3f();
    }

    // TODO 26.3: Reimplement end-sky color modulator. Old target
    // SkyRenderer.renderEndSky + DynamicUniforms.writeTransform no longer exists;
    // SkyRenderer now takes pre-written GpuBufferSlice + SkyRenderState.
}
