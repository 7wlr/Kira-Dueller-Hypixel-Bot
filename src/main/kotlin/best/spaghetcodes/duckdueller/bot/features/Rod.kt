package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Canne Hypixel Classic :
 * - UsingProjectile = true AVANT le switch,
 * - Switch → léger pré-délai → clic droit bref (lancer) → court temps de vol → retour épée.
 */
interface Rod {

    val mc: Minecraft get() = Minecraft.getMinecraft()

    fun useRod() {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        Inventory.setInvItem("rod")
        val preDelay = RandomUtils.randomIntInRange(50, 90)
        val clickMs = RandomUtils.randomIntInRange(80, 110)
        val settleAfter = RandomUtils.randomIntInRange(260, 420)

        TimeUtils.setTimeout({
            // sécurité: vérifier qu'on a bien la rod en main
            val held = mc.thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            Mouse.rClick(clickMs)

            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                TimeUtils.setTimeout({
                    Mouse.setUsingProjectile(false)
                }, RandomUtils.randomIntInRange(80, 140))
            }, clickMs + settleAfter)
        }, preDelay)
    }
}
