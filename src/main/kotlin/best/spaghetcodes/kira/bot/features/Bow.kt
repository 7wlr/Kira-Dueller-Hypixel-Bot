package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Arc – séquence simple (1.8.9) :
 * 1) switch bow
 * 2) petit settle (compat ping/packs)
 * 3) hold rClick (charge) via rClickForce
 * 4) release auto + retour épée
 *
 * Aucune décision “quand tirer” ici : laissé aux profils (Classic/ClassicV2/…).
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /**
     * Tir "safe" avec petit settle avant la charge (le plus fiable en 1.8.9).
     * Utilise rClickForce pour garantir le press/release même si rClickDown était déjà true.
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        // 1) switch
        Inventory.setInvItem("bow")

        // 2) settle court (compat latence/ressource pack)
        val preDelay = RandomUtils.randomIntInRange(60, 110)
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        TimeUtils.setTimeout({
            // Sécurité : s’assurer qu’on tient bien l’arc
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("bow")) {
                Inventory.setInvItem("bow")
            }

            // 3) charge (press/release garanti)
            Mouse.rClickForce(hold)

            // 4) après la charge, petit tail puis retour épée + callback
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                afterShot()
            }, hold + RandomUtils.randomIntInRange(80, 140))
        }, preDelay)
    }

    /**
     * Variante "immédiate" (sans settle) pour les ouvertures agressives.
     * Si tu vois un drop client, préfère useBow().
     */
    fun useBowImmediateFull(afterShot: () -> Unit = {}) {
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        Inventory.setInvItem("bow")
        Mouse.rClickForce(hold)

        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            afterShot()
        }, hold + RandomUtils.randomIntInRange(80, 140))
    }
}
