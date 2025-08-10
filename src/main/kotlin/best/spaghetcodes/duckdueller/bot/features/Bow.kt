package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.EntityUtils
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import best.spaghetcodes.duckdueller.utils.Utils.mc

/**
 * Comportement commun "arc agressif et humain" :
 * - Jamais d'autotracking ni d'auto-CPS ici (gérés ailleurs / désactivés).
 * - Charge l'arc puis TIRE, sauf si l'adversaire re-fixe ou revient trop près:
 *   -> annule la charge, relâche le clic droit, repasse épée immédiatement.
 * - Post-tir: repasse automatiquement à l'épée.
 */
interface Bow {

    /**
     * Fenêtre de charge "humaine" (en ms). Ajustable par profil via override si besoin.
     */
    val bowMinHoldMs: Int
        get() = 520
    val bowMaxHoldMs: Int
        get() = 720

    /**
     * Distance sous laquelle on annule la charge (adversaire trop proche).
     */
    val bowCancelCloseDistance: Double
        get() = 6.0

    /**
     * Utilisation de l’arc : non bloquante, avec annulation sécurisée.
     * @param distance distance actuelle (utilisée pour affiner certains timings)
     * @param afterShot callback appelé après un tir effectif (ex: incrémenter shotsFired)
     */
    fun useBow(distance: Double, afterShot: () -> Unit = {}) {
        // Empêche la re-entrée si on est déjà en phase "projectile"
        if (Mouse.isUsingProjectile()) return

        // Équipe l'arc
        Inventory.setInvItem("bow")

        // Durée de charge pseudo-aléatoire pour un rendu plus humain
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        // Début de la charge (clic droit maintenu par la lib)
        // NB: Mouse.rClick(hold) tient puis relâche après 'hold' ms.
        Mouse.rClick(hold)

        // Surveille la situation toutes les 50ms durant la charge, pour pouvoir annuler.
        // On caste prudemment 'this' en BotBase pour accéder à opponent() si dispo.
        val self = (this as? BotBase)

        var interval = TimeUtils.setInterval({
            val player = mc.thePlayer ?: return@setInterval
            val opp = self?.opponent() ?: return@setInterval

            val d = EntityUtils.getDistanceNoY(player, opp)
            val facingUs = !EntityUtils.entityFacingAway(player, opp)

            // Conditions d'annulation : l’ennemi te refixe OU revient trop près
            if (facingUs || d < bowCancelCloseDistance) {
                // On relâche immédiatement pour éviter de se faire punir,
                // puis on repasse épée et on arrête la surveillance.
                Mouse.rClickUp()
                Inventory.setInvItem("sword")
                interval?.cancel()
            }
        }, 50, 50)

        // Fin programmée de la charge : si non annulée, on laisse partir la flèche,
        // on repasse épée et on appelle le callback "afterShot".
        TimeUtils.setTimeout({
            interval?.cancel()
            Inventory.setInvItem("sword")
            afterShot()
        }, hold + RandomUtils.randomIntInRange(20, 40))
    }
}
