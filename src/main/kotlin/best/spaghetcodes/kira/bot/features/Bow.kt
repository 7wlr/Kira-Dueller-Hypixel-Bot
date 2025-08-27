package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Arc (Hypixel Classic/OP)
 * Deux voies :
 *  - useBow(distance): chemin "safe" (petit pré-délai) — utilisé historiquement par OP
 *  - useBowImmediateFull(): chemin immédiat (zéro délai) — idéal pour Classic agressif
 *
 * Objectif : éviter les cas "arc en main sans tirer" et revenir proprement à l'épée.
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /**
     * Chemin "safe" conservé pour compat OP (pré-délai léger).
     * Maintien explicit rClick(hold) puis release + retour épée.
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        // Switch sur l’arc + petit ‘settle’ (compat packs / ping OP)
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

    /**
     * Chemin "immédiat" sans aucun pré-délai : switch ➜ rClick(hold) tout de suite.
     * A utiliser quand on veut zéro latence (ex. Classic agressif).
     */
    fun useBowImmediateFull(afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        // Switch instant + clic droit immédiat
        Inventory.setInvItem("bow")
        Mouse.rClick(hold)

        // Release + retour épée
        TimeUtils.setTimeout({
            Mouse.setUsingProjectile(false)
            Inventory.setInvItem("sword")
            afterShot()
        }, hold + RandomUtils.randomIntInRange(90, 150))
    }
}
