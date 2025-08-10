package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.EntityUtils
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

interface Rod {

    private val mc_: Minecraft
        get() = Minecraft.getMinecraft()

    /**
     * Lance de canne "distance-aware" pour Hypixel Classic:
     * - Garde la canne assez longtemps pour que le flotteur touche à 6–10 blocs,
     * - puis repasse à l'épée au bon timing.
     * - AUCUN auto-CPS ici.
     */
    fun useRod() {
        if (Mouse.isUsingProjectile()) return

        val self = this as? BotBase
        val player = mc_.thePlayer ?: return
        val opp = self?.opponent() ?: return
        val distance: Float = EntityUtils.getDistanceNoY(player, opp)

        // Durée du clic droit (sortie du flotteur)
        val clickMs = RandomUtils.randomIntInRange(100, 140)

        // Temps de vol approximatif du flotteur en fonction de la distance (ms)
        val flightMs = when {
            distance < 4f    -> RandomUtils.randomIntInRange(180, 240)
            distance < 6.7f  -> RandomUtils.randomIntInRange(240, 300)
            distance < 9.7f  -> RandomUtils.randomIntInRange(300, 380)
            else             -> RandomUtils.randomIntInRange(380, 460)
        }

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        // Équipe la canne et lance
        Inventory.setInvItem("rod")
        Mouse.rClick(clickMs)

        // On arrête le statut "projectile" peu après le lancer,
        // mais on ne repasse épée qu'après le temps de vol.
        TimeUtils.setTimeout({
            Mouse.setUsingProjectile(false)
        }, RandomUtils.randomIntInRange(40, 80))

        // Revenir épée après un délai suffisant pour que le flotteur CONNECTE
        TimeUtils.setTimeout({
            if (mc_.thePlayer.heldItem != null &&
                !mc_.thePlayer.heldItem.unlocalizedName.lowercase().contains("bow")) {
                Inventory.setInvItem("sword")
            }
        }, clickMs + flightMs + RandomUtils.randomIntInRange(60, 120))
    }
}
