package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Gestion canne (Hypixel 1.8.9)
 *
 * Problème connu: le clic droit émis au même tick que le switch d'item
 * peut être ignoré. On attend ~1 tick avant de cliquer, puis on garde la canne
 * brièvement en main pour fiabiliser le paquet réseau, puis retour épée.
 *
 * Deux voies:
 *  - useRod(): voie "safe" (latence très légère) -> mid range
 *  - useRodImmediate(): voie "close" agressive, mais avec micro-tick garanti
 */
interface Rod {

    /**
     * Mid range (~3–6.5 blocs): petit pré-délai + clic + rétention ~200 ms.
     * C’est proche de ce que tu avais de plus stable auparavant.
     */
    fun useRod() {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        // Switch sur la canne
        Inventory.setInvItem("rod")

        // Attente d'un tick (fiabilise l'input côté 1.8.9)
        val preDelay = RandomUtils.randomIntInRange(55, 75)
        val clickHold = RandomUtils.randomIntInRange(80, 110)   // durée du rClick
        val retainMs  = RandomUtils.randomIntInRange(180, 220)  // temps canne en main après le cast

        TimeUtils.setTimeout({
            // Sécurité: si l'équipement n'a pas pris, réappliquer
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            // Cast: clic droit bref
            Mouse.rClick(clickHold)

            // Garder la canne un court instant, puis revenir à l'épée
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                Mouse.setUsingProjectile(false)
            }, retainMs)
        }, preDelay)
    }

    /**
     * Close range (<~3 blocs): micro-tick minimal + cast + rétention ~120–140 ms.
     * Évite le cas "prend la canne en main mais ne lance pas" sans ajouter de lourde latence.
     */
    fun useRodImmediate() {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        Inventory.setInvItem("rod")

        val preDelay = RandomUtils.randomIntInRange(50, 60)     // ~1 tick
        val clickHold = RandomUtils.randomIntInRange(70, 95)
        val retainMs  = RandomUtils.randomIntInRange(120, 150)  // close: 120–150 ms

        TimeUtils.setTimeout({
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            Mouse.rClick(clickHold)

            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                Mouse.setUsingProjectile(false)
            }, retainMs)
        }, preDelay)
    }
}
