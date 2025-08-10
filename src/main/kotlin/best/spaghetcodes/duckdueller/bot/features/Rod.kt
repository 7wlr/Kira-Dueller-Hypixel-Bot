package best.spaghetcodes.duckdueller.bot.features

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.utils.EntityUtils
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Canne Hypixel Classic "human-like":
 * - Lancer réel (clic court),
 * - On GARDE la canne le temps de vol estimé selon la distance,
 * - Puis on revient épée (sans auto-CPS).
 *
 * Note: pas de setUsingProjectile ici pour ne pas interférer avec le tracking.
 */
interface Rod {

    private val mc_: Minecraft
        get() = Minecraft.getMinecraft()

    fun useRod() {
        if (Mouse.isUsingProjectile()) return

        val self = this as? BotBase
        val p = mc_.thePlayer ?: return
        val opp = self?.opponent() ?: return
        val d: Float = EntityUtils.getDistanceNoY(p, opp)

        // Clic droit court pour sortir le flotteur
        val clickMs = RandomUtils.randomIntInRange(90, 120)

        // Temps de vol du flotteur (grossier mais efficace sur Hypixel)
        val travelMs = when {
            d < 4f    -> RandomUtils.randomIntInRange(200, 260)
            d < 6.7f  -> RandomUtils.randomIntInRange(260, 330)
            d < 9.7f  -> RandomUtils.randomIntInRange(330, 420)
            else      -> RandomUtils.randomIntInRange(420, 520)
        }

        Mouse.stopLeftAC()

        Inventory.setInvItem("rod")
        Mouse.rClick(clickMs)

        // Retour épée APRÈS le temps de vol, pour laisser connecter le flotteur
        TimeUtils.setTimeout({
            if (mc_.thePlayer.heldItem != null &&
                !mc_.thePlayer.heldItem.unlocalizedName.lowercase().contains("bow")) {
                Inventory.setInvItem("sword")
            }
        }, clickMs + travelMs + RandomUtils.randomIntInRange(80, 140))
    }
}
