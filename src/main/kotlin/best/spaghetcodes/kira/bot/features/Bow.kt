package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Arc – séquence simple 1.8.9 :
 *  - Si un clic droit est en cours, on lève d’abord (anti-conflit)
 *  - Switch "bow"
 *  - Petit settle
 *  - Mouse.rClick(hold) (charge) -> relâche auto
 *  - Petit tail puis retour "sword"
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /** Tir "safe" avec petit settle avant la charge */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.rClickDown) {
            Mouse.rClickUp()
        }

        Inventory.setInvItem("bow")

        val preDelay = RandomUtils.randomIntInRange(60, 110)
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        TimeUtils.setTimeout({
            // S’assurer qu’on tient bien l’arc
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("bow")) {
                Inventory.setInvItem("bow")
            }

            // Charge (relâche auto via Mouse.rClick)
            Mouse.rClick(hold)

            // Petit tail puis retour épée + callback
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                afterShot()
            }, hold + RandomUtils.randomIntInRange(80, 140))
        }, preDelay)
    }

    /** Variante immédiate (sans settle) pour certaines ouvertures agressives */
    fun useBowImmediateFull(afterShot: () -> Unit = {}) {
        if (Mouse.rClickDown) {
            Mouse.rClickUp()
        }

        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        Inventory.setInvItem("bow")
        Mouse.rClick(hold)

        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            afterShot()
        }, hold + RandomUtils.randomIntInRange(80, 140))
    }
}
