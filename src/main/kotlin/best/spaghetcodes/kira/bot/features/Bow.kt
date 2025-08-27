package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Gestion arc (Hypixel 1.8.9)
 *
 * Deux chemins conservés:
 *  - useBow(distance): chemin “safe” avec petit settle avant le hold.
 *  - useBowImmediateFull(): hold immédiat (pour les ouvertures agressives),
 *    puis release + retour épée proprement.
 *
 * La logique "quand tirer" reste côté bot; ici on fiabilise la séquence input.
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /**
     * Chemin “safe” (pré-délai léger) — compatible avec l’ancienne logique OP.
     * Maintient le clic droit pendant [hold], relâche, puis revient à l’épée.
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        Inventory.setInvItem("bow")

        val preDelay = RandomUtils.randomIntInRange(60, 110)
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        TimeUtils.setTimeout({
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("bow")) {
                Inventory.setInvItem("bow")
            }

            Mouse.rClick(hold)

            TimeUtils.setTimeout({
                // Fin du tir -> retour épée propre + callback
                Mouse.setUsingProjectile(false)
                Inventory.setInvItem("sword")
                afterShot()
            }, hold + RandomUtils.randomIntInRange(80, 140))
        }, preDelay)
    }

    /**
     * Chemin “immédiat” (zéro settle avant hold) — pour certaines ouvertures Classic.
     * Maintient rClick, puis release et retour épée.
     */
    fun useBowImmediateFull(afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        Inventory.setInvItem("bow")
        Mouse.rClick(hold)

        TimeUtils.setTimeout({
            Mouse.setUsingProjectile(false)
            Inventory.setInvItem("sword")
            afterShot()
        }, hold + RandomUtils.randomIntInRange(80, 140))
    }
}
