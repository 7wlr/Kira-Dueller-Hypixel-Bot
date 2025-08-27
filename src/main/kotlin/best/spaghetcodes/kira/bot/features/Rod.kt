package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Canne à pêche – séquence simple et robuste (style OP) :
 * 1) switch rod
 * 2) micro-settle (≈ 1 tick)
 * 3) rClick court (cast)
 * 4) garder la canne un bref moment
 * 5) revenir à l’épée
 *
 * On NE gère PAS d'état global ici (pas de setUsingProjectile), pour éviter
 * les interactions indésirables avec d'autres modules.
 */
interface Rod {

    /**
     * Mid range (~3–6.5 blocs) : rétention un peu plus longue pour fiabiliser le KB.
     */
    fun useRod() {
        // switch sur la canne
        Inventory.setInvItem("rod")

        // ~1 tick de settle
        val preDelay = RandomUtils.randomIntInRange(55, 75)
        val clickHold = RandomUtils.randomIntInRange(80, 110)    // durée du rClick
        val retainMs  = RandomUtils.randomIntInRange(180, 220)   // garder la canne en main

        TimeUtils.setTimeout({
            // sécurité : si le switch n'a pas pris (pack/ping), on réapplique
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            // cast
            Mouse.rClick(clickHold)

            // petite rétention puis retour épée
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
            }, retainMs)
        }, preDelay)
    }

    /**
     * Close range (<~3 blocs) : rétention plus courte (120–150 ms).
     * NOTE : on garde quand même un micro-settle d’un tick — c’est la clé de fiabilité.
     */
    fun useRodImmediate() {
        Inventory.setInvItem("rod")

        val preDelay = RandomUtils.randomIntInRange(50, 60)      // ~1 tick
        val clickHold = RandomUtils.randomIntInRange(70, 95)
        val retainMs  = RandomUtils.randomIntInRange(120, 150)

        TimeUtils.setTimeout({
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            Mouse.rClick(clickHold)

            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
            }, retainMs)
        }, preDelay)
    }
}
