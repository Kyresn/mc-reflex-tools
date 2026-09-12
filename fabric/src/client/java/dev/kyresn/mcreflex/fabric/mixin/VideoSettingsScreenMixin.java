package dev.kyresn.mcreflex.fabric.mixin;

import dev.kyresn.mcreflex.fabric.gui.ReflexOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("HEAD"))
    private void mcReflexTools$addReflexButton(CallbackInfo callbackInfo) {
        this.list.addHeader(Component.translatable("options.mcreflex.header.reflex"));
        this.list.addSmall(Button.builder(
                Component.translatable("options.mcreflex.button"),
                button -> this.minecraft.gui.setScreen(new ReflexOptionsScreen(this, this.options))
        ).build(), null);
    }
}
