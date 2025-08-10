package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.EntityUtils
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Arc agressif (Hypixel Classic):
 * - Charge toujours au maximum (≈ 1150–1300 ms).
 * - Si l’adversaire revient/se retourne pendant la charge → LÂCHER tout de suite et repasser épée.
 * - AUCUN auto-CPS ici.
 */
interface Bow {

    /** Fenêtre de charge max (ms) */
    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /** Distance où on relâche immédiatement la flèche si l’ennemi revient trop près. */
    val bowCancelCloseDistance: Float get() = 6.0f

    /**
     * Utilise l’arc: full charge par défaut, release anticipé si pression.
     * @param distance Distance actuelle (Float)
     * @param afterShot Callback appelé UNE fois quand la flèche part
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Inventory.setInvItem("bow")

        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)
        Mouse.setUsingProjectile(true)
        Mouse.rClick(hold) // Maintient clic droit pendant 'hold' ms (puis relâche)

        val self = this as? BotBase
        var fired = false

        val interval = TimeUtils.setInterval({
            val player = Minecraft.getMinecraft().thePlayer ?: return@setInterval
            val opp = self?.opponent() ?: return@setInterval
            if (fired) return@setInterval

            val d: Float = EntityUtils.getDistanceNoY(player, opp)
            val facingUs = !EntityUtils.entityFacingAway(player, opp)

            // Pression → on lâche MAINTENANT et on repasse épée
            if (facingUs || d < bowCancelCloseDistance) {
                fired = true
                Mouse.rClickUp()
                Mouse.setUsingProjectile(false)
                Inventory.setInvItem("sword")
                afterShot()
            }
        }, 50, 50)

        // Fin de charge (full power)
        TimeUtils.setTimeout({
            interval?.cancel()
            if (!fired) {
                fired = true
                Mouse.setUsingProjectile(false)
                Inventory.setInvItem("sword")
                afterShot()
            }
        }, hold + RandomUtils.randomIntInRange(20, 40))
    }
}
