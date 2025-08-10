package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.EntityUtils
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Comportement commun "arc agressif et humain":
 * - AUCUN autotracking ni auto-CPS ici.
 * - Charge puis tire, sauf si l’adversaire re-fixe ou revient trop près:
 *   -> annule la charge, relâche, repasse épée.
 * - Après tir: repasse automatiquement à l’épée.
 */
interface Bow {

    /** Fenêtre de charge "humaine" (ms). */
    val bowMinHoldMs: Int get() = 520
    val bowMaxHoldMs: Int get() = 720

    /** Distance de cancel si l’ennemi revient trop près. */
    val bowCancelCloseDistance: Double get() = 6.0

    /**
     * Utilisation de l’arc avec annulation sécurisée.
     * @param distance distance actuelle (info facultative)
     * @param afterShot callback après un tir effectif
     */
    fun useBow(distance: Double, afterShot: () -> Unit = {}) {
        // Évite la ré-entrée si déjà en phase projectile
        if (Mouse.isUsingProjectile()) return

        // Équipe l’arc
        Inventory.setInvItem("bow")

        // Durée de charge pseudo-aléatoire
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        // Lance la charge (clic droit maintenu puis relâché après 'hold' ms)
        Mouse.rClick(hold)

        val self = this as? BotBase
        var canceled = false

        // Surveille la situation toutes les 50ms pendant la charge
        val interval = TimeUtils.setInterval({
            val player = Minecraft.getMinecraft().thePlayer ?: return@setInterval
            val opp = self?.opponent() ?: return@setInterval

            if (canceled) return@setInterval

            val d = EntityUtils.getDistanceNoY(player, opp)
            val facingUs = !EntityUtils.entityFacingAway(player, opp)

            // Annuler si l’ennemi re-fixe ou s’approche trop
            if (facingUs || d < bowCancelCloseDistance) {
                canceled = true
                Mouse.rClickUp()
                Inventory.setInvItem("sword")
            }
        }, 50, 50)

        // Fin de charge programmée
        TimeUtils.setTimeout({
            interval?.cancel()
            Inventory.setInvItem("sword")
            if (!canceled) {
                afterShot()
            }
        }, hold + RandomUtils.randomIntInRange(20, 40))
    }
}
