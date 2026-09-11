package dev.kyresn.mcreflex.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.kyresn.mcreflex.api.ReflexLatencyReport;
import dev.kyresn.mcreflex.api.ReflexMode;
import dev.kyresn.mcreflex.api.ReflexState;
import dev.kyresn.mcreflex.fabric.config.ModConfig;
import dev.kyresn.mcreflex.nvidia.NativeReflexProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Runtime control surface for Reflex: {@code /reflex status|mode|framelimit|debug}.
 *
 * <p>Commands mutate {@link ModConfig} and persist it; the per-tick options apply in
 * {@link McReflexToolsFabricClient} re-sends {@code slReflexSetOptions} on the next
 * tick, so changes take effect without a restart. Commands run on the game thread,
 * the same thread the tick apply reads the config on.
 */
public final class ReflexCommands {
    private ReflexCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("reflex")
                        .executes(context -> {
                            usage(context.getSource());
                            return 1;
                        })
                        .then(ClientCommands.literal("status").executes(context -> {
                            status(context.getSource());
                            return 1;
                        }))
                        .then(ClientCommands.literal("mode")
                                .executes(context -> {
                                    subcommandUsage(context.getSource(), "mode", "off|on|boost");
                                    return 1;
                                })
                                .then(ClientCommands.argument("mode", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestModes(builder))
                                        .executes(context -> {
                                            setMode(context.getSource(), StringArgumentType.getString(context, "mode"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("framelimit")
                                .executes(context -> {
                                    subcommandUsage(context.getSource(), "framelimit", "<fps>");
                                    return 1;
                                })
                                .then(ClientCommands.argument("fps", IntegerArgumentType.integer(0, 2000))
                                        .suggests((context, builder) -> suggestZeroOrOne(builder, false))
                                        .executes(context -> {
                                            setFrameLimit(context.getSource(), IntegerArgumentType.getInteger(context, "fps"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("debug")
                                .executes(context -> {
                                    subcommandUsage(context.getSource(), "debug", "0|1");
                                    return 1;
                                })
                                .then(ClientCommands.argument("enabled", IntegerArgumentType.integer(0, 1))
                                        .suggests((context, builder) -> suggestZeroOrOne(builder, true))
                                        .executes(context -> {
                                            setDebug(context.getSource(), IntegerArgumentType.getInteger(context, "enabled") != 0);
                                            return 1;
                                        })))));
    }

    private static CompletableFuture<Suggestions> suggestZeroOrOne(SuggestionsBuilder builder, boolean oneFirst) {
        if (oneFirst) {
            builder.suggest(1);
            builder.suggest(0);
        } else {
            builder.suggest(0);
        }
        return builder.buildFuture();
    }

    private static void usage(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(
                "Usage: /reflex status | mode off|on|boost | framelimit <fps> | debug 0|1"));
    }

    private static void subcommandUsage(FabricClientCommandSource source, String subcommand, String arguments) {
        source.sendError(Component.literal("Usage: /reflex " + subcommand + " " + arguments));
    }

    private static CompletableFuture<Suggestions> suggestModes(SuggestionsBuilder builder) {
        builder.suggest("off");
        builder.suggest("on");
        builder.suggest("boost");
        return builder.buildFuture();
    }

    private static void status(FabricClientCommandSource source) {
        ModConfig config = ModConfig.get();
        var provider = McReflexToolsFabricClient.getReflexProvider();

        source.sendFeedback(Component.literal(
                "Reflex provider: " + provider.getClass().getSimpleName()
                        + ", mode: " + config.reflexMode
                        + ", frame limit: " + (config.customFrameLimitFps > 0 ? config.customFrameLimitFps + " fps" : "off")
                        + ", debug: " + (config.debug ? "on" : "off")));

        if (provider instanceof NativeReflexProvider nativeProvider) {
            ReflexState state = nativeProvider.getReflexState();
            source.sendFeedback(Component.literal(
                    "lowLatencyAvailable=" + state.lowLatencyAvailable()
                            + ", sleeps=" + nativeProvider.sleepCount()
                            + ", pclPings=" + nativeProvider.pclPingCount()
                            + " (missed " + nativeProvider.pclPingMissedCount() + ")"
                            + ", orderViolations=" + nativeProvider.markerOrderViolationCount()
                            + ", staleMarkers=" + nativeProvider.staleMarkerCount()
                            + ", suppressedRenderSubmit=" + nativeProvider.suppressedMarkerCount()));

            ReflexLatencyReport report = nativeProvider.getLatencyReport();
            if (report.available()) {
                source.sendFeedback(Component.literal(
                        "Latency report: frameId=" + report.frameId()
                                + ", simStartToPresentUs=" + report.simStartToPresentUs()
                                + ", gpuActiveUs=" + report.gpuActiveRenderTimeUs()));
            } else {
                source.sendFeedback(Component.literal(
                        "Latency report: unavailable (driver has not published a frame; last SDK error="
                                + nativeProvider.lastSdkError() + ")"));
            }
        }
    }

    private static void setMode(FabricClientCommandSource source, String modeName) {
        ReflexMode mode = switch (modeName.toLowerCase(Locale.ROOT)) {
            case "off" -> ReflexMode.OFF;
            case "on" -> ReflexMode.ON;
            case "boost" -> ReflexMode.ON_PLUS_BOOST;
            default -> null;
        };
        if (mode == null) {
            source.sendError(Component.literal(
                    "Unknown Reflex mode '" + modeName + "'. Use off, on, or boost."));
            return;
        }

        ModConfig config = ModConfig.get();
        config.reflexMode = mode;
        ModConfig.save();
        McReflexToolsFabricClient.LOGGER.info("Reflex mode set to {} via /reflex command", mode);

        appliedFeedback(source, "mode=" + mode);
    }

    private static void setFrameLimit(FabricClientCommandSource source, int fps) {
        ModConfig config = ModConfig.get();
        config.customFrameLimitFps = fps;
        ModConfig.save();
        McReflexToolsFabricClient.LOGGER.info("Reflex frame limit set to {} fps via /reflex command", fps);

        appliedFeedback(source, "frameLimitFps=" + fps);
    }

    private static void setDebug(FabricClientCommandSource source, boolean enabled) {
        ModConfig config = ModConfig.get();
        config.debug = enabled;
        ModConfig.save();
        McReflexToolsFabricClient.LOGGER.info("Reflex debug logging {} via /reflex command", enabled ? "enabled" : "disabled");

        source.sendFeedback(Component.literal("Reflex debug logging " + (enabled ? "enabled" : "disabled")
                + " (lifecycle trace every " + (enabled ? 30 : 300) + " frames)."));
    }

    private static void appliedFeedback(FabricClientCommandSource source, String what) {
        var provider = McReflexToolsFabricClient.getReflexProvider();
        if (provider instanceof NativeReflexProvider) {
            source.sendFeedback(Component.literal(
                    "Reflex " + what + " saved; options re-applied on the next frame."));
        } else {
            source.sendFeedback(Component.literal(
                    "Reflex " + what + " saved; no active native provider right now, "
                            + "options apply once the Vulkan context becomes eligible."));
        }
    }
}
