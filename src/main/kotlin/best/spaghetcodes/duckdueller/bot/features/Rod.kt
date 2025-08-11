package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Canne Hypixel Classic :
 * - Switch → léger pré-délai → clic droit bref (lancer) → court temps de vol → retour épée.
 * - Pas d’auto-CPS ici.
 */
interface Rod {

    fun useRod() {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        // Switch rod + petit ‘settle’
        Inventory.setInvItem("rod")
        val preDelay = RandomUtils.randomIntInRange(50, 90)
        val clickMs = RandomUtils.randomIntInRange(80, 110)
        val settleAfter = RandomUtils.randomIntInRange(260, 420)

        TimeUtils.setTimeout({
            // sécurité: vérifier qu’on tient bien la rod
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            // Clic droit bref → lance la ligne
            Mouse.rClick(clickMs)

            // Revenir épée après un petit temps de vol
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                TimeUtils.setTimeout({
                    Mouse.setUsingProjectile(false)
                }, RandomUtils.randomIntInRange(80, 140))
            }, clickMs + settleAfter)
        }, preDelay)
    }
}
