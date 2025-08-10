package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.EntityUtils
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Arc (Hypixel Classic):
 * - Full charge par défaut (~1150–1300 ms).
 * - Si pression: on relâche immédiatement, puis on repasse épée APRÈS un petit délai (sinon le tir peut être annulé).
 * - AUCUN auto-CPS ici.
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /** Distance où on force le release immédiat si l’ennemi est trop près. */
    val bowCancelCloseDistance: Float get() = 6.0f

    /**
     * Tient l’arc jusqu’au full-charge, sauf pression (release anticipé).
     * @param distance Float (aligné avec EntityUtils.getDistanceNoY)
     * @param afterShot callback UNE fois après que la flèche est réellement partie
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Inventory.setInvItem("bow")
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        Mouse.setUsingProjectile(true)
        Mouse.rClick(hold) // maintient puis relâche après 'hold' ms

        val self = this as? BotBase
        var fired = false

        // Surveille la pression pendant la charge
        val interval = TimeUtils.setInterval({
            val player = Minecraft.getMinecraft().thePlayer ?: return@setInterval
            val opp = self?.opponent() ?: return@setInterval
            if (fired) return@setInterval

            val d: Float = EntityUtils.getDistanceNoY(player, opp)
            val facingUs = !EntityUtils.entityFacingAway(player, opp)

            // On n'annule plus juste parce que l'ennemi nous regarde de loin.
            // Pression réelle: trop près, ou proche ET il nous fixe.
            val pressure = (d < bowCancelCloseDistance) || (facingUs && d <= 8f)
            if (pressure) {
                fired = true
                Mouse.rClickUp()          // lâcher maintenant → la flèche part
                Mouse.setUsingProjectile(false)
                TimeUtils.setTimeout({
                    Inventory.setInvItem("sword")
                    afterShot()
                }, RandomUtils.randomIntInRange(70, 110))
            }
        }, 50, 50)

        // Full charge atteint → on repasse épée juste après (petit délai pour ne pas annuler le tir)
        TimeUtils.setTimeout({
            interval?.cancel()
            if (!fired) {
                fired = true
                Mouse.setUsingProjectile(false)
                TimeUtils.setTimeout({
                    Inventory.setInvItem("sword")
                    afterShot()
                }, RandomUtils.randomIntInRange(70, 110))
            }
        }, hold + RandomUtils.randomIntInRange(20, 40))
    }
}
