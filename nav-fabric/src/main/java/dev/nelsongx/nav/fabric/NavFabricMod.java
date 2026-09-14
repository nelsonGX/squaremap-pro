package dev.nelsongx.nav.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NavFabricMod implements ModInitializer {
  public static final String MOD_ID = "squaremap-pro";
  private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitialize() {
    LOGGER.info("squaremap-pro initialized");
  }
}
