package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Canne à pêche – séquence simple et robuste (1.8.9) :
 * 1) switch rod
 * 2) micro-settle (~1 tick)
 * 3) rClick court (cast)
 * 4) garder la canne un bref moment
 * 5) revenir à l’épée
 *
 * AUCUN état global ici (pas de setUsingProjectile) pour éviter les conflits.
 * Utilise rClickForce pour garantir le press/release même si rClickDown était déjà true.
 */
interface Rod {

    /**
     * Mid range (~3–6.5 blocs) : rétention un peu plus longue (fiabilise le KB).
     */
    fun useRod() {
        // 1) switch sur la canne
        Inventory.setInvItem("rod")

        // 2) petit settle (≈ 1 tick)
        val preDelay = RandomUtils.randomIntInRange(55, 75)
        val clickHold = RandomUtils.randomIntInRange(80, 110)    // durée du rClick
        val retainMs  = RandomUtils.randomIntInRange(180, 220)   // garder la canne en main

        TimeUtils.setTimeout({
            // Sécurité : si le switch n’a pas pris (pack/ping), on réapplique
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            // 3) cast (force le press/release)
            Mouse.rClickForce(clickHold)

            // 4) petite rétention puis 5) retour épée
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
            }, retainMs)
        }, preDelay)
    }

    /**
     * Close range (<~3 blocs) : rétention plus courte (120–150 ms).
     * On garde tout de même ~1 tick de settle (clé de fiabilité).
     */
    fun useRodImmediate() {
        // 1) switch sur la canne
        Inventory.setInvItem("rod")

        // 2) micro-settle (~1 tick)
        val preDelay = RandomUtils.randomIntInRange(50, 60)
        val clickHold = RandomUtils.randomIntInRange(70, 95)
        val retainMs  = RandomUtils.randomIntInRange(120, 150)

        TimeUtils.setTimeout({
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            // 3) cast (force le press/release)
            Mouse.rClickForce(clickHold)

            // 4) courte rétention puis 5) retour épée
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
            }, retainMs)
        }, preDelay)
    }
}
