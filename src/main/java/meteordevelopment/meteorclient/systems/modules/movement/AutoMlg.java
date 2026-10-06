/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CushionItem;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class AutoMlg extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTypes = settings.createGroup("MLG Types");

    private final Setting<Double> minFallDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-fall-distance")
        .description("Minimum fall distance before attempting the MLG.")
        .defaultValue(4.0)
        .min(2.0)
        .max(20.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Boolean> anchor = sgGeneral.add(new BoolSetting.Builder()
        .name("anchor")
        .description("Centers the player to increase MLG accuracy.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> searchInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("search-inventory")
        .description("Moves the MLG item from your main inventory to your hotbar if it is not already there.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pickupWater = sgGeneral.add(new BoolSetting.Builder()
        .name("pickup-water")
        .description("Picks the water back up after a water bucket MLG.")
        .defaultValue(true)
        .visible(() -> isTypeEnabled(MlgMode.Water))
        .build()
    );

    private final Map<MlgMode, Setting<Boolean>> useType = new EnumMap<>(MlgMode.class);
    private final Map<MlgMode, Setting<Integer>> typePriority = new EnumMap<>(MlgMode.class);

    private boolean placedWater;
    private boolean placedMobBucket;
    private BlockPos targetPos;
    private int timer;
    private int moveCooldown;

    public AutoMlg() {
        super(Categories.Movement, "auto-mlg", "Automatically MLGs to prevent fall damage by placing water, a cushioning block, boat or cushion below you and sitting if needed.");

        // Per-type enable + priority. Lower priority number is tried first.
        for (MlgMode type : MlgMode.values()) {
            String base = type.name().toLowerCase();
            useType.put(type, sgTypes.add(new BoolSetting.Builder()
                .name("use-" + base)
                .description("Allow " + type + " MLGs.")
                .defaultValue(true)
                .build()
            ));
            typePriority.put(type, sgTypes.add(new IntSetting.Builder()
                .name(base + "-priority")
                .description("Lower is tried first when multiple MLG types are available.")
                .defaultValue(type.ordinal())
                .min(0)
                .max(100)
                .sliderMax(9)
                .build()
            ));
        }
    }

    @Override
    public void onActivate() {
        placedWater = false;
        placedMobBucket = false;
        targetPos = null;
        timer = 0;
        moveCooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;
        if (mc.player.getAbilities().instabuild) return;
        if (mc.player.isFallFlying()) return;
        if (mc.player.isPassenger()) return;

        if (timer > 20) {
            placedWater = false;
            placedMobBucket = false;
            timer = 0;
        }

        if (moveCooldown > 0) moveCooldown--;

        List<MlgMode> enabled = getEnabledSorted();
        if (enabled.isEmpty()) return;

        boolean waterEvaporates = Boolean.TRUE.equals(mc.level.environmentAttributes().getDimensionValue(EnvironmentAttributes.WATER_EVAPORATES));

        // Pick up placed water after landing, then recapture any fish/axolotl from mob buckets.
        if (placedWater && pickupWater.get()) {
            timer++;
            if (mc.player.getInBlockState().getBlock() == Blocks.WATER) {
                useBucket(InvUtils.findInHotbar(Items.BUCKET), false, targetPos);
            } else if (targetPos != null && mc.level.getBlockState(mc.player.blockPosition().below()).getBlock() == Blocks.POWDER_SNOW && mc.player.fallDistance == 0) {
                useBucket(InvUtils.findInHotbar(Items.BUCKET), false, targetPos.below());
            }
            if (placedMobBucket) {
                // Order matters: bucket pickup needs an empty bucket (water first),
                // mob recapture needs the resulting water bucket, so both run each tick.
                if (!tryRecaptureMob()) {
                    // No bucketable mob in range: already recovered, wandered off or died.
                    placedMobBucket = false;
                }
            }
        }

        if (mc.player.fallDistance < minFallDistance.get()) return;
        if (mc.player.getDeltaMovement().y > -0.3) return;
        if (mc.player.onGround()) return;
        if (EntityUtils.isAboveWater(mc.player)) return;

        // Find a block within reach below to MLG onto.
        BlockHitResult result = mc.level.clip(new ClipContext(mc.player.position(), mc.player.position().subtract(0, 6, 0), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (result == null || result.getType() != HitResult.Type.BLOCK) return;

        targetPos = result.getBlockPos().above();

        // Mount existing boats/cushions first in priority order (no item needed).
        for (MlgMode type : enabled) {
            if (!type.isRideable()) continue;
            if (tryMountRideable(type)) return;
        }

        // Place/use in priority order, hotbar first for instant reaction.
        // Water is skipped where it evaporates (e.g. Nether) so the next type is tried.
        for (MlgMode type : enabled) {
            if (type == MlgMode.Water && waterEvaporates) continue;
            FindItemResult findItemResult = findMlgItem(type);
            if (!findItemResult.found()) continue;

            if (anchor.get()) smartCenter();
            placeWith(type, findItemResult, result);
            return;
        }

        // Nothing in hotbar: move the highest-priority available item from inventory.
        if (searchInventory.get() && moveCooldown <= 0) {
            for (MlgMode type : enabled) {
                if (type == MlgMode.Water && waterEvaporates) continue;
                FindItemResult invResult = findMlgItemInventory(type);
                if (invResult.found() && !invResult.isHotbar() && !invResult.isOffhand()) {
                    // Move from main inventory (9-35) into the selected hotbar slot for next tick.
                    InvUtils.move().from(invResult.slot()).toHotbar(mc.player.getInventory().getSelectedSlot());
                    moveCooldown = 10;
                    break;
                }
            }
        }
    }

    private void placeWith(MlgMode type, FindItemResult item, BlockHitResult groundHit) {
        switch (type) {
            case Water -> {
                placedMobBucket = isMobBucketResult(item);
                useBucket(item, true, targetPos);
            }
            case Boat -> useRideableItem(item);
            case Cushion -> placeCushion(item, groundHit);
            default -> BlockUtils.place(targetPos, item, true, 10, true);
        }
    }

    private boolean isTypeEnabled(MlgMode type) {
        Setting<Boolean> setting = useType.get(type);
        return setting == null || setting.get();
    }

    private List<MlgMode> getEnabledSorted() {
        List<MlgMode> list = new ArrayList<>();
        for (MlgMode type : MlgMode.values()) {
            if (isTypeEnabled(type)) list.add(type);
        }
        list.sort((a, b) -> {
            Setting<Integer> pa = typePriority.get(a);
            Setting<Integer> pb = typePriority.get(b);
            int ia = pa == null ? a.ordinal() : pa.get();
            int ib = pb == null ? b.ordinal() : pb.get();
            if (ia != ib) return Integer.compare(ia, ib);
            return Integer.compare(a.ordinal(), b.ordinal());
        });
        return list;
    }

    private FindItemResult findMlgItem(MlgMode mode) {
        return switch (mode) {
            case Water -> InvUtils.findInHotbar(stack ->
                stack.getItem() instanceof BucketItem bucket && bucket.getContent() == Fluids.WATER);
            case Boat -> InvUtils.findInHotbar(stack -> stack.getItem() instanceof BoatItem);
            case Cushion -> InvUtils.findInHotbar(stack -> stack.getItem() instanceof CushionItem);
            default -> InvUtils.findInHotbar(mode.item);
        };
    }

    private FindItemResult findMlgItemInventory(MlgMode mode) {
        return switch (mode) {
            case Water -> InvUtils.find(stack ->
                stack.getItem() instanceof BucketItem bucket && bucket.getContent() == Fluids.WATER);
            case Boat -> InvUtils.find(stack -> stack.getItem() instanceof BoatItem);
            case Cushion -> InvUtils.find(stack -> stack.getItem() instanceof CushionItem);
            default -> InvUtils.find(mode.item);
        };
    }

    private boolean isMobBucketResult(FindItemResult result) {
        if (!result.found()) return false;
        if (result.isOffhand()) return mc.player.getOffhandItem().getItem() instanceof MobBucketItem;
        int slot = result.slot();
        if (slot < 0 || slot >= mc.player.getInventory().getContainerSize()) return false;
        return mc.player.getInventory().getItem(slot).getItem() instanceof MobBucketItem;
    }

    // Recapture a placed fish/axolotl/tadpole with a water bucket (Bucketable requires WATER_BUCKET in hand).
    private boolean tryRecaptureMob() {
        FindItemResult water = InvUtils.findInHotbar(Items.WATER_BUCKET);
        if (!water.found()) return true; // keep waiting for the water pickup to finish

        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!(entity instanceof Bucketable)) continue;
            if (!living.isAlive()) continue;
            if (!PlayerUtils.isWithin(entity, 6)) continue;
            double d = mc.player.distanceToSqr(entity);
            if (d < bestDist) {
                bestDist = d;
                best = entity;
            }
        }
        if (best == null) return false;

        Entity target = best;
        double yaw = Rotations.getYaw(target);
        double pitch = Rotations.getPitch(target);
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) return true;
        safeRotate(yaw, pitch, 10, true, () -> {
            EntityHitResult hit = new EntityHitResult(target, target.getBoundingBox().getCenter());
            if (water.isOffhand()) {
                mc.gameMode.interact(mc.player, target, hit, InteractionHand.OFF_HAND);
            } else {
                InvUtils.swap(water.slot(), true);
                mc.gameMode.interact(mc.player, target, hit, InteractionHand.MAIN_HAND);
                InvUtils.swapBack();
            }
        });
        return true;
    }

    private void useRideableItem(FindItemResult item) {
        if (!item.found()) return;

        // Look straight down to place the boat on the ground below (BoatItem.use raycasts up to 5 blocks).
        safeRotate(mc.player.getYRot(), 89.5, 10, true, () -> {
            if (item.isOffhand()) {
                mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            } else {
                InvUtils.swap(item.slot(), true);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                InvUtils.swapBack();
            }
        });
    }

    private void placeCushion(FindItemResult item, BlockHitResult groundHit) {
        if (!item.found()) return;

        // CushionItem.useOn requires the UP face of a block with an anchor below.
        // Look straight down after centering: reliably hits the top face without yaw instability.
        safeRotate(mc.player.getYRot(), 89.5, 10, true, () -> {
            if (item.isOffhand()) {
                BlockUtils.interact(groundHit, InteractionHand.OFF_HAND, true);
            } else {
                InvUtils.swap(item.slot(), true);
                BlockUtils.interact(groundHit, InteractionHand.MAIN_HAND, true);
                InvUtils.swapBack();
            }
        });
    }

    private void smartCenter() {
        double cx = Math.floor(mc.player.getX()) + 0.5;
        double cz = Math.floor(mc.player.getZ()) + 0.5;
        double dx = mc.player.getX() - cx;
        double dz = mc.player.getZ() - cz;
        // Only adjust when meaningfully off-center. Client-side only: the normal
        // movement packet carries the new position next tick, avoiding the extra
        // ServerboundMovePlayerPacket.Pos that triggers invalid_movement kicks.
        if (dx * dx + dz * dz > 0.015) {
            mc.player.setPos(cx, mc.player.getY(), cz);
        }
    }

    private void safeRotate(double yaw, double pitch, int priority, boolean clientSide, Runnable callback) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) return;
        double clampedPitch = Math.max(-90.0, Math.min(90.0, pitch));
        if (!Double.isFinite(clampedPitch)) return;
        Rotations.rotate(yaw, clampedPitch, priority, clientSide, callback);
    }

    private boolean tryMountRideable(MlgMode mode) {
        if (mc.player.isShiftKeyDown()) return false;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isRemoved()) continue;
            if (mode == MlgMode.Boat && !(entity instanceof AbstractBoat)) continue;
            if (mode == MlgMode.Cushion && !(entity instanceof Cushion)) continue;
            if (!PlayerUtils.isWithin(entity, 6)) continue;
            // Only mount entities at or below eye level to avoid mounting unrelated entities above.
            if (entity.getY() > mc.player.getEyeY() + 1) continue;

            mountNow(entity);
            return true;
        }

        return false;
    }

    // Tick-optimized sit: interact immediately instead of waiting for the rotation
    // callback (server checks distance, not look angle for entity interacts).
    private void mountNow(Entity entity) {
        double yaw = Rotations.getYaw(entity);
        double pitch = Rotations.getPitch(entity);
        if (Double.isFinite(yaw) && Double.isFinite(pitch)) {
            safeRotate(yaw, pitch, 100, true, null);
        }
        EntityHitResult hit = new EntityHitResult(entity, entity.getBoundingBox().getCenter());
        mc.gameMode.interact(mc.player, entity, hit, InteractionHand.MAIN_HAND);
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!isActive() || !Utils.canUpdate()) return;
        if (!isTypeEnabled(MlgMode.Boat) && !isTypeEnabled(MlgMode.Cushion)) return;
        if (mc.player.isPassenger() || mc.player.isFallFlying()) return;
        if (mc.player.fallDistance < minFallDistance.get()) return;
        if (mc.player.getDeltaMovement().y > -0.3) return;

        Entity entity = event.entity;
        if (entity.isRemoved()) return;
        if (entity instanceof AbstractBoat && !isTypeEnabled(MlgMode.Boat)) return;
        if (entity instanceof Cushion && !isTypeEnabled(MlgMode.Cushion)) return;
        if (!(entity instanceof AbstractBoat) && !(entity instanceof Cushion)) return;
        if (!PlayerUtils.isWithin(entity, 6)) return;

        mountNow(entity);
    }

    private void useBucket(FindItemResult item, boolean markPlaced, BlockPos blockPos) {
        if (!item.found() || blockPos == null) return;

        // Look straight down after centering: most reliable MLG angle, avoids yaw
        // instability when the target is directly below (atan2(0,0)).
        safeRotate(mc.player.getYRot(), 89.5, 10, true, () -> {
            if (item.isOffhand()) {
                mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            } else {
                InvUtils.swap(item.slot(), true);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                InvUtils.swapBack();
            }
        });

        // Only ever set here; cleared by timeout or once water is back and no mob is pending.
        // (Pickup attempts must not clear it: the useItem callback runs at packet-send
        // time, and mob recapture needs the following ticks to get the filled bucket.)
        if (markPlaced) this.placedWater = true;
    }

    @Override
    public String getInfoString() {
        List<MlgMode> enabled = getEnabledSorted();
        if (enabled.isEmpty()) return "none";
        return enabled.size() == 1 ? enabled.get(0).toString() : enabled.size() + " types";
    }

    public enum MlgMode {
        Water(Items.WATER_BUCKET, Blocks.WATER),
        PowderSnow(Items.POWDER_SNOW_BUCKET, Blocks.POWDER_SNOW),
        HayBale(Items.HAY_BLOCK, Blocks.HAY_BLOCK),
        Cobweb(Items.COBWEB, Blocks.COBWEB),
        Slime(Items.SLIME_BLOCK, Blocks.SLIME_BLOCK),
        Honey(Items.HONEY_BLOCK, Blocks.HONEY_BLOCK),
        TwistingVines(Items.TWISTING_VINES, Blocks.TWISTING_VINES),
        Boat(Items.OAK_BOAT, Blocks.AIR),
        Cushion(Items.CUSHION.pink(), Blocks.AIR);

        private final Item item;
        private final Block block;

        MlgMode(Item item, Block block) {
            this.item = item;
            this.block = block;
        }

        @SuppressWarnings("unused")
        public boolean isRideable() {
            return this == Boat || this == Cushion;
        }
    }
}
