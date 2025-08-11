package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils

/**
 * Arc (Hypixel Classic):
 * - Switch → léger pré-délai → clic droit maintenu (full draw) → retour épée.
 * - Pas d’auto-CPS ici (géré ailleurs).
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /** Distance où l’appelant peut décider d’annuler si pression (géré côté bot). */
    val bowCancelCloseDistance: Float get() = 6.0f

    /**
     * Tire une flèche (full charge). L'appelant gère la pression/annulation éventuelle.
     * @param distance fournie pour compat, non utilisée ici (logique press côté bot).
     * @param afterShot callback après le tir.
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        // switch arc + petit settle
        Inventory.setInvItem("bow")
        val preDelay = RandomUtils.randomIntInRange(60, 110)
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        TimeUtils.setTimeout({
            Mouse.setUsingProjectile(true)
            Mouse.rClick(hold) // maintient puis relâche

            // laisser la release partir puis revenir épée
            TimeUtils.setTimeout({
                Mouse.setUsingProjectile(false)
                Inventory.setInvItem("sword")
                afterShot()
            }, hold + RandomUtils.randomIntInRange(90, 150))
        }, preDelay)
    }
}
