package dev.kyresn.mcreflex.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.kyresn.mcreflex.api.DlssGMode;
import dev.kyresn.mcreflex.api.DlssMode;
import dev.kyresn.mcreflex.api.ReflexMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public final class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc_reflex_tools");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/mc_reflex_tools.json");

    private static ModConfig INSTANCE = new ModConfig();

    // Default to ON_PLUS_BOOST (On + Boost)
    public ReflexMode reflexMode = ReflexMode.ON_PLUS_BOOST;
    // Auto limit to 95% of monitor refresh rate
    public boolean autoGsyncFrameLimit = true;
    public int customFrameLimitFps = 0;
    public DlssMode dlssMode = DlssMode.MAX_QUALITY;
    public DlssGMode dlssGMode = DlssGMode.OFF;
    public int dlssGFramesToGenerate = 1;

    public static ModConfig get() {
        return INSTANCE;
    }

    public static void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
            if (loaded != null) {
                INSTANCE = loaded;
                LOGGER.info("Loaded configuration from {}", CONFIG_FILE.getAbsolutePath());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration, using defaults", e);
        }
    }

    public static void save() {
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save configuration", e);
        }
    }
}
