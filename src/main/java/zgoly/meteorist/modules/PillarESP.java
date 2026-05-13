package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

public class PillarESP extends Module {

    // ── Setting Groups ──────────────────────────────────────────────────────

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgPlayers  = settings.createGroup("Players");
    private final SettingGroup sgSpawners = settings.createGroup("Spawners");
    private final SettingGroup sgRender   = settings.createGroup("Render");

    // ── General ─────────────────────────────────────────────────────────────

    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("search-radius")
        .description("Horizontal chunk radius to scan for targets.")
        .defaultValue(5)
        .range(1, 16)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("How many ticks between each scan (lower = more frequent, more CPU).")
        .defaultValue(20)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    // ── Players ──────────────────────────────────────────────────────────────

    private final Setting<Boolean> showPlayers = sgPlayers.add(new BoolSetting.Builder()
        .name("show-players")
        .description("Draw pillars on nearby players.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreSelf = sgPlayers.add(new BoolSetting.Builder()
        .name("ignore-self")
        .description("Don't draw a pillar on yourself.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> playerColor = sgPlayers.add(new ColorSetting.Builder()
        .name("player-color")
        .description("Fill color of player pillars.")
        .defaultValue(new SettingColor(255, 50, 50, 80))
        .build()
    );

    private final Setting<SettingColor> playerOutlineColor = sgPlayers.add(new ColorSetting.Builder()
        .name("player-outline-color")
        .description("Outline color of player pillars.")
        .defaultValue(new SettingColor(255, 50, 50, 255))
        .build()
    );

    // ── Spawners ─────────────────────────────────────────────────────────────

    private final Setting<Boolean> showSpawners = sgSpawners.add(new BoolSetting.Builder()
        .name("show-spawners")
        .description("Draw pillars on mob spawners (skeleton, zombie, etc).")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> spawnerColor = sgSpawners.add(new ColorSetting.Builder()
        .name("spawner-color")
        .description("Fill color of spawner pillars.")
        .defaultValue(new SettingColor(255, 165, 0, 80))
        .build()
    );

    private final Setting<SettingColor> spawnerOutlineColor = sgSpawners.add(new ColorSetting.Builder()
        .name("spawner-outline-color")
        .description("Outline color of spawner pillars.")
        .defaultValue(new SettingColor(255, 165, 0, 255))
        .build()
    );

    // ── Render ───────────────────────────────────────────────────────────────

    private final Setting<Double> pillarWidth = sgRender.add(new DoubleSetting.Builder()
        .name("pillar-width")
        .description("Width (and depth) of each pillar in blocks.")
        .defaultValue(1.0)
        .range(0.1, 4.0)
        .sliderRange(0.1, 4.0)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Whether to render fill, outline, or both.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Boolean> extendUp = sgRender.add(new BoolSetting.Builder()
        .name("extend-to-build-limit")
        .description("Extend pillars up to world height limit (320).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> extendDown = sgRender.add(new BoolSetting.Builder()
        .name("extend-to-bedrock")
        .description("Extend pillars down to bedrock level (-64).")
        .defaultValue(true)
        .build()
    );

    // ── State ────────────────────────────────────────────────────────────────

    private final List<PlayerEntry>  playerPillars  = new ArrayList<>();
    private final List<BlockPos>     spawnerPillars = new ArrayList<>();
    private int tickCounter = 0;

    // ── Constructor ──────────────────────────────────────────────────────────

    public PillarESP() {
        super(AddonTemplate.CATEGORY, "pillar-esp",
            "Draws tall pillars above/below players and spawners so you can spot them through terrain.");
    }

    @Override
    public void onDeactivate() {
        playerPillars.clear();
        spawnerPillars.clear();
    }

    // ── Tick: scan world ─────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        tickCounter++;
        if (tickCounter < scanInterval.get()) return;
        tickCounter = 0;

        // Players
        playerPillars.clear();
        if (showPlayers.get()) {
            for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
                if (ignoreSelf.get() && player == mc.player) continue;
                playerPillars.add(new PlayerEntry(player.getBlockPos(), player.getName().getString()));
            }
        }

        // Spawners – iterate loaded chunks in radius
        spawnerPillars.clear();
        if (showSpawners.get()) {
            int cx = mc.player.getBlockPos().getX() >> 4;
            int cz = mc.player.getBlockPos().getZ() >> 4;
            int radius = searchRadius.get();

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx + dx, cz + dz);
                    if (chunk == null) continue;

                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof MobSpawnerBlockEntity) {
                            spawnerPillars.add(be.getPos());
                        }
                    }
                }
            }
        }
    }

    // ── Render ───────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null) return;

        double half = pillarWidth.get() / 2.0;
        int worldMin = extendDown.get() ? mc.world.getBottomY() : mc.player.getBlockY();
        int worldMax = extendUp.get()   ? mc.world.getTopY()    : mc.player.getBlockY();

        // Draw player pillars
        for (PlayerEntry entry : playerPillars) {
            drawPillar(event, entry.pos, half, worldMin, worldMax,
                playerColor.get(), playerOutlineColor.get());
        }

        // Draw spawner pillars
        for (BlockPos pos : spawnerPillars) {
            drawPillar(event, pos, half, worldMin, worldMax,
                spawnerColor.get(), spawnerOutlineColor.get());
        }
    }

    private void drawPillar(Render3DEvent event, BlockPos origin,
                            double half, int yMin, int yMax,
                            Color fill, Color outline) {
        double x = origin.getX() + 0.5;
        double z = origin.getZ() + 0.5;

        Box pillar = new Box(
            x - half, yMin,  z - half,
            x + half, yMax,  z + half
        );

        event.renderer.box(pillar, fill, outline, shapeMode.get(), 0);
    }

    // ── Helper record ────────────────────────────────────────────────────────

    private static class PlayerEntry {
        final BlockPos pos;
        final String name;
        PlayerEntry(BlockPos pos, String name) { this.pos = pos; this.name = name; }
    }
}
