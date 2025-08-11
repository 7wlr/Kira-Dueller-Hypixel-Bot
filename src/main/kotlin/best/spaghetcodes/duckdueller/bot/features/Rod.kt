package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils

/**
 * Canne Hypixel Classic :
 * - Switch → léger pré-délai → clic droit bref (lancer) → attendre court temps de vol → retour épée.
 * - Pas d’auto-CPS ici.
 */
interface Rod {

    fun useRod() {
        if (Mouse.isUsingProjectile()) return

        // switch rod + petit settle
        Inventory.setInvItem("rod")
        val preDelay = RandomUtils.randomIntInRange(50, 90)
        val clickMs = RandomUtils.randomIntInRange(80, 110)
        val settleAfter = RandomUtils.randomIntInRange(260, 420) // ~temps de vol court “safe” Hypixel

        TimeUtils.setTimeout({
            Mouse.setUsingProjectile(true)
            Mouse.rClick(clickMs)

            // revenir épée après un court temps de vol pour garantir la prise côté serveur
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                TimeUtils.setTimeout({
                    Mouse.setUsingProjectile(false)
                }, RandomUtils.randomIntInRange(80, 140))
            }, clickMs + settleAfter)
        }, preDelay)
    }
}
