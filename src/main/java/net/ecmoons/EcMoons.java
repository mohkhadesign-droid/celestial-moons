package net.ecmoons;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(EcMoons.MOD_ID)
public class EcMoons {
    public static final String MOD_ID = "ecmoons";
    public static final Logger LOGGER = LogManager.getLogger("ecmoons");

    public EcMoons() {
        LOGGER.info("Enhanced Celestials: Ten Moons loading");
    }
}
