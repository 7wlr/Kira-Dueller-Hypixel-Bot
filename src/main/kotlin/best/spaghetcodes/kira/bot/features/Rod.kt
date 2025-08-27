package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Canne à pêche – séquence simple 1.8.9 :
 *  - Si un clic droit est en cours, on lève d’abord le clic (anti-conflit)
 *  - Switch "rod"
 *  - Micro-settle (~1 tick)
 *  - Mouse.rClick(hold) -> relâche auto
 *  - Petite rétention, puis retour "sword"
 */
interface Rod {

    /** Mid range ~3–6.5 blocs : rétention un peu plus longue (fiabilise KB) */
    fun useRod() {
        // Garde-fou : ne rien lancer si déjà en train de right-click
        if (Mouse.rClickDown) {
            Mouse.rClickUp()
        }

        // 1) switch sur la canne
        Inventory.setInvItem("rod")

        // 2) settle (~1 tick)
        val preDelay = RandomUtils.randomIntInRange(55, 75)
        val clickHold = RandomUtils.randomIntInRange(80, 110)     // durée du press
        val retainMs  = RandomUtils.randomIntInRange(180, 220)    // garder la canne en main

        TimeUtils.setTimeout({
            // Sécurité : si le switch n’a pas pris, on réapplique
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            // 3) cast (Mouse.rClick planifie le relâchement)
            Mouse.rClick(clickHold)

            // 4) petite rétention puis 5) retour épée
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
            }, retainMs)
        }, preDelay)
    }

    /** Close range <~3 blocs : rétention plus courte (120–150 ms) */
    fun useRodImmediate() {
        if (Mouse.rClickDown) {
            Mouse.rClickUp()
        }

        Inventory.setInvItem("rod")

        val preDelay = RandomUtils.randomIntInRange(50, 60)       // ~1 tick
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
