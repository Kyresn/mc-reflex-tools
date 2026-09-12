package dev.kyresn.mcreflex.fabric.gui;

import com.mojang.serialization.Codec;
import dev.kyresn.mcreflex.api.ReflexLatencyReport;
import dev.kyresn.mcreflex.api.ReflexMode;
import dev.kyresn.mcreflex.fabric.McReflexToolsFabricClient;
import dev.kyresn.mcreflex.fabric.config.ModConfig;
import dev.kyresn.mcreflex.nvidia.NativeReflexProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.Locale;

/**
 * Dedicated options screen for NVIDIA Reflex Low Latency configuration and telemetry.
 */
public final class ReflexOptionsScreen extends OptionsSubScreen {
    private static final Codec<ReflexMode> REFLEX_MODE_CODEC = Codec.STRING.xmap(ReflexMode::valueOf, ReflexMode::name);
    private static final Component TITLE = Component.translatable("options.mcreflex.title");
    private static final Component HEADER_REFLEX = Component.translatable("options.mcreflex.header.reflex");
    private static final Component HEADER_FRAMELIMIT = Component.translatable("options.mcreflex.header.framelimit");
    private static final Component HEADER_DIAGNOSTICS = Component.translatable("options.mcreflex.header.diagnostics");

    private OptionInstance<Integer> frameLimitOption;
    private EditBox frameLimitEditBox;
    private StringWidget telemetryWidget;

    public ReflexOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        ModConfig config = ModConfig.get();

        // 1. Reflex Mode (OFF, ON, ON_PLUS_BOOST)
        OptionInstance<ReflexMode> reflexModeOption = new OptionInstance<>(
                "options.mcreflex.mode",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.mcreflex.mode.tooltip")),
                (caption, value) -> getModeLabel(value),
                new OptionInstance.Enum<>(Arrays.asList(ReflexMode.values()), REFLEX_MODE_CODEC),
                config.reflexMode,
                value -> {
                    config.reflexMode = value;
                    ModConfig.save();
                }
        );

        // 2. Debug Lifecycle Trace boolean option
        OptionInstance<Boolean> debugOption = OptionInstance.createBoolean(
                "options.mcreflex.debug",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.mcreflex.debug.tooltip")),
                config.debug,
                value -> {
                    config.debug = value;
                    ModConfig.save();
                }
        );

        // 3. Reflex Frame Limiter Slider (0 = Off, 1 - 999 FPS)
        this.frameLimitOption = new OptionInstance<>(
                "options.mcreflex.frameLimit",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.mcreflex.frameLimit.tooltip")),
                (caption, value) -> value == 0
                        ? Component.translatable("options.off")
                        : Component.literal(value + " FPS"),
                new OptionInstance.IntRange(0, 999),
                Math.clamp(config.customFrameLimitFps, 0, 999),
                value -> {
                    config.customFrameLimitFps = value;
                    ModConfig.save();
                    if (this.frameLimitEditBox != null) {
                        String targetText = String.valueOf(value);
                        if (!this.frameLimitEditBox.getValue().equals(targetText)) {
                            this.frameLimitEditBox.setValue(targetText);
                        }
                    }
                }
        );

        // 4. Reflex Frame Limiter Manual Input Box with Enter / focus-lost confirmation
        this.frameLimitEditBox = new EditBox(this.font, 150, 20, Component.translatable("options.mcreflex.frameLimit.input")) {
            @Override
            public boolean keyPressed(KeyEvent event) {
                if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                    applyManualFrameLimit();
                    return true;
                }
                return super.keyPressed(event);
            }

            @Override
            public boolean charTyped(CharacterEvent event) {
                if (!Character.isDigit(event.codepoint())) {
                    return false;
                }
                return super.charTyped(event);
            }

            @Override
            public void setFocused(boolean focused) {
                super.setFocused(focused);
                if (!focused) {
                    applyManualFrameLimit();
                }
            }
        };
        this.frameLimitEditBox.setMaxLength(3);
        this.frameLimitEditBox.setValue(String.valueOf(config.customFrameLimitFps));

        // Section 1: Reflex Mode & Debug Trace (compact 150px half-width buttons side-by-side)
        this.list.addHeader(HEADER_REFLEX);
        this.list.addSmall(reflexModeOption, debugOption);

        // Section 2: Frame Limiter (Slider on Left, Manual Input on Right)
        this.list.addHeader(HEADER_FRAMELIMIT);
        AbstractWidget sliderWidget = this.frameLimitOption.createButton(this.options);
        this.list.addSmall(sliderWidget, this.frameLimitOption, this.frameLimitEditBox);

        // Section 3: Telemetry summary bar
        this.list.addHeader(HEADER_DIAGNOSTICS);
        this.telemetryWidget = new StringWidget(getTelemetryText(), this.font);
        this.list.addBig(this.telemetryWidget);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            applyManualFrameLimit();
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        applyManualFrameLimit();
        super.onClose();
    }

    @Override
    public void removed() {
        applyManualFrameLimit();
        super.removed();
    }

    private void applyManualFrameLimit() {
        if (this.frameLimitEditBox == null || this.frameLimitOption == null) {
            return;
        }
        String text = this.frameLimitEditBox.getValue().trim();
        try {
            int parsed = text.isEmpty() ? 0 : Integer.parseInt(text);
            int clamped = Math.clamp(parsed, 0, 999);
            ModConfig config = ModConfig.get();
            if (config.customFrameLimitFps != clamped) {
                config.customFrameLimitFps = clamped;
                ModConfig.save();
                this.frameLimitOption.set(clamped);
                this.resetOption(this.frameLimitOption);
                McReflexToolsFabricClient.LOGGER.info("Reflex frame limit set to {} FPS from the options screen", clamped);
            }
            this.frameLimitEditBox.setValue(String.valueOf(clamped));
        } catch (NumberFormatException ignored) {
            this.frameLimitEditBox.setValue(String.valueOf(ModConfig.get().customFrameLimitFps));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.telemetryWidget != null) {
            this.telemetryWidget.setMessage(getTelemetryText());
        }
    }

    private static Component getModeLabel(ReflexMode mode) {
        return switch (mode) {
            case OFF -> Component.translatable("options.mcreflex.mode.off");
            case ON -> Component.translatable("options.mcreflex.mode.on");
            case ON_PLUS_BOOST -> Component.translatable("options.mcreflex.mode.boost");
        };
    }

    private Component getTelemetryText() {
        var provider = McReflexToolsFabricClient.getReflexProvider();
        if (provider instanceof NativeReflexProvider nativeProvider) {
            ReflexLatencyReport report = nativeProvider.getLatencyReport();
            if (report.available()) {
                double latencyMs = report.simStartToPresentUs() / 1000.0;
                double gpuMs = report.gpuActiveRenderTimeUs() / 1000.0;
                String latencyStr = String.format(Locale.ROOT, "%.2f", latencyMs);
                String gpuStr = String.format(Locale.ROOT, "%.2f", gpuMs);
                String frameStr = Long.toUnsignedString(report.frameId());
                return Component.translatable("options.mcreflex.telemetry.live", latencyStr, gpuStr, frameStr)
                        .withStyle(ChatFormatting.GREEN);
            }
            return Component.translatable("options.mcreflex.telemetry.waiting")
                    .withStyle(ChatFormatting.YELLOW);
        }
        return Component.translatable("options.mcreflex.telemetry.unavailable")
                .withStyle(ChatFormatting.RED);
    }
}

