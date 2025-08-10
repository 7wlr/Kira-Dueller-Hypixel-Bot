package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.EntityUtils
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Arc agressif "human-like" :
 * - Toujours charge au maximum (≈ 1s) pour un tir full power.
 * - Si l’adversaire se retourne ou revient trop près : on LÂCHE la flèche immédiatement et on repasse épée.
 * - AUCUN autotracking/auto-CPS ici.
 */
interface Bow {

    /** Fenêtre de charge "max-power" (ms), avec légère variance humaine. */
    val bowMinHoldMs: Int get() = 950  // ~19 ticks
    val bowMaxHoldMs: Int get() = 1050 // ~21 ticks

    /** Distance de "pression" à laquelle on doit lâcher la flèche et repasser épée. */
    val bowCancelCloseDistance: Float get() = 6.0f

    /**
     * Utilisation de l’arc : tire à full charge, sauf pression → tire tout de suite.
     * @param distance distance actuelle (Float)
     * @param afterShot callback appelé UNE fois quand la flèche part
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Inventory.setInvItem("bow")

        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)
        Mouse.rClick(hold) // maintien du clic droit pendant 'hold' ms

        val self = this as? BotBase
        var fired = false

        val interval = TimeUtils.setInterval({
            val player = Minecraft.getMinecraft().thePlayer ?: return@setInterval
            val opp = self?.opponent() ?: return@setInterval

            if (fired) return@setInterval

            val d: Float = EntityUtils.getDistanceNoY(player, opp)
            val facingUs = !EntityUtils.entityFacingAway(player, opp)

            // Si l’adversaire re-engage → on LÂCHE la flèche maintenant et on repasse épée
            if (facingUs || d < bowCancelCloseDistance) {
                fired = true
                Mouse.rClickUp()              // relâche → flèche part même si pas full charge
                Inventory.setInvItem("sword")
                afterShot()
            }
        }, 50, 50)

        // Fin de charge programmée (full power)
        TimeUtils.setTimeout({
            interval?.cancel()
            if (!fired) {
                fired = true
                Inventory.setInvItem("sword") // repasse épée juste après le tir full charge
                afterShot()
            }
        }, hold + RandomUtils.randomIntInRange(20, 40))
    }
}
