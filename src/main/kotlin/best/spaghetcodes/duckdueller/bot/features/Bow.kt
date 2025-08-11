package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Arc (Hypixel Classic):
 * - Switch → léger pré-délai → clic droit maintenu (full draw) → retour épée.
 * - Pas d’auto-CPS ici.
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        // Switch sur l’arc + petit ‘settle’ réseau
        Inventory.setInvItem("bow")
        val preDelay = RandomUtils.randomIntInRange(60, 110)
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        TimeUtils.setTimeout({
            // sécurité: s’assurer qu’on tient bien un arc (packs, latence)
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("bow")) {
                Inventory.setInvItem("bow")
            }

            // Maintien pour un tir full-charge
            Mouse.rClick(hold)

            // Laisser la release, puis retour épée
            TimeUtils.setTimeout({
                Mouse.setUsingProjectile(false)
                Inventory.setInvItem("sword")
                afterShot()
            }, hold + RandomUtils.randomIntInRange(90, 150))
        }, preDelay)
    }
}
