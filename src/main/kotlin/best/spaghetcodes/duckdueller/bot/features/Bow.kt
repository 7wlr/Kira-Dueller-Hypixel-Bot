package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Arc (Hypixel Classic):
 * - UsingProjectile = true AVANT le switch (protège contre les reswitch/parades)
 * - Switch → léger pré-délai → clic droit maintenu (full draw) → retour épée.
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    val mc: Minecraft get() = Minecraft.getMinecraft()

    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        // switch + petit settle
        Inventory.setInvItem("bow")
        val preDelay = RandomUtils.randomIntInRange(60, 110)
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        TimeUtils.setTimeout({
            // sécurité: s'assurer qu'on tient bien un arc (pack/latence)
            val held = mc.thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("bow")) {
                Inventory.setInvItem("bow")
            }

            Mouse.rClick(hold)

            // laisser la release partir puis revenir épée
            TimeUtils.setTimeout({
                Mouse.setUsingProjectile(false)
                Inventory.setInvItem("sword")
                afterShot()
            }, hold + RandomUtils.randomIntInRange(90, 150))
        }, preDelay)
    }
}
