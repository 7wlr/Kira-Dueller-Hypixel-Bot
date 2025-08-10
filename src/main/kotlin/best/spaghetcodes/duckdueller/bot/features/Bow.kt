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
 * - Full charge par défaut (≈ 1150–1300 ms).
 * - Si l'adversaire revient/se retourne: lâche immédiatement, puis repasse épée
 *   avec un léger délai pour ne PAS annuler le tir.
 * - AUCUN auto-CPS ici.
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /** Distance à laquelle on relâche tout de suite si pression. */
    val bowCancelCloseDistance: Float get() = 6.0f

    /**
     * Tient l’arc jusqu’au full-charge, sauf pression (release immédiat).
     * @param distance Float (aligné avec EntityUtils.getDistanceNoY)
     * @param afterShot callback appelé UNE fois après que la flèche est partie
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        // Équipe l’arc et démarre une vraie phase "projectile"
        Inventory.setInvItem("bow")
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)
        Mouse.setUsingProjectile(true)
        Mouse.rClick(hold) // maintient et relâche après 'hold' ms

        val self = this as? BotBase
        var fired = false

        // Surveillance de la pression pendant la charge
        val interval = TimeUtils.setInterval({
            val player = Minecraft.getMinecraft().thePlayer ?: return@setInterval
            val opp = self?.opponent() ?: return@setInterval
            if (fired) return@setInterval

            val d: Float = EntityUtils.getDistanceNoY(player, opp)
            val facingUs = !EntityUtils.entityFacingAway(player, opp)

            if (facingUs || d < bowCancelCloseDistance) {
                fired = true
                // Relâche MAINTENANT, puis laisse 60–110ms avant l'épée
                Mouse.rClickUp()
                Mouse.setUsingProjectile(false)
                TimeUtils.setTimeout({
                    Inventory.setInvItem("sword")
                    afterShot()
                }, RandomUtils.randomIntInRange(60, 110))
            }
        }, 50, 50)

        // Full charge atteint → repasse épée juste après (petit délai pour ne pas annuler)
        TimeUtils.setTimeout({
            interval?.cancel()
            if (!fired) {
                fired = true
                Mouse.setUsingProjectile(false)
                TimeUtils.setTimeout({
                    Inventory.setInvItem("sword")
                    afterShot()
                }, RandomUtils.randomIntInRange(60, 110))
            }
        }, hold + RandomUtils.randomIntInRange(20, 40))
    }
}
