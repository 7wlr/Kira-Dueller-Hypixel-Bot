package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Arc – séquence simple (style OP) :
 * 1) switch bow
 * 2) petit settle (compat ping/packs)
 * 3) hold rClick (charge)
 * 4) release + retour épée
 *
 * Aucune logique de "quand tirer" ici : ça reste dans les profils.
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /**
     * Tir "safe" avec petit settle avant la charge (compat OP).
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        Inventory.setInvItem("bow")

        val preDelay = RandomUtils.randomIntInRange(60, 110)
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        TimeUtils.setTimeout({
            // sécurité : s’assurer qu’on tient bien l’arc
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("bow")) {
                Inventory.setInvItem("bow")
            }

            // charge
            Mouse.rClick(hold)

            // release + retour épée + callback
            TimeUtils.setTimeout({
                Mouse.rClickUp()
                Inventory.setInvItem("sword")
                afterShot()
            }, hold + RandomUtils.randomIntInRange(80, 140))
        }, preDelay)
    }

    /**
     * Variante "immédiate" (zéro settle) si tu veux une ouverture très agressive.
     * Si tu observes un drop côté client, repasse sur useBow() pour cette phase.
     */
    fun useBowImmediateFull(afterShot: () -> Unit = {}) {
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        Inventory.setInvItem("bow")
        Mouse.rClick(hold)

        TimeUtils.setTimeout({
            Mouse.rClickUp()
            Inventory.setInvItem("sword")
            afterShot()
        }, hold + RandomUtils.randomIntInRange(80, 140))
    }
}
