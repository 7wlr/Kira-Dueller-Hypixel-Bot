package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.ChatUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.kira
import net.minecraft.item.ItemStack

private val GOLDEN_APPLE_REGEX = Regex(
    pattern = """
        (
          (minecraft:)?golden[_\s]*apple |      # golden_apple / golden apple
          gold[_\s]*apple |                     # gold apple (rare)
          item\.applegold |                     # unlocalized ancien
          applegold | apple\s*gold |            # applegold / apple gold
          pomme[_\s]*dor(?:ée|ee)               # pomme dorée / doree
        )
    """.trimIndent(),
    options = setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
)

private val PLAIN_APPLE_REGEX = Regex(
    pattern = """\b(apple|pomme)\b""",
    options = setOf(RegexOption.IGNORE_CASE)
)

private fun stackSearchKey(stack: ItemStack): String {
    return (stack.unlocalizedName + " " + stack.displayName).lowercase()
}

private fun matchesGoldenApple(stack: ItemStack?): Boolean {
    val it = stack ?: return false
    return GOLDEN_APPLE_REGEX.containsMatchIn(stackSearchKey(it))
}

private fun matchesPlainApple(stack: ItemStack?): Boolean {
    val it = stack ?: return false
    return PLAIN_APPLE_REGEX.containsMatchIn(stackSearchKey(it))
}

private fun matchesAnyGap(stack: ItemStack?): Boolean {
    return matchesGoldenApple(stack) || matchesPlainApple(stack)
}

/**
 * Gestion "pomme dorée" par regex (sans liste fermée).
 * On détecte d'abord les variantes GOLDEN APPLE, puis on retombe sur APPLE.
 */
interface Gap {

    var lastGap: Long

    /** Sélection d’une gap via REGEX sur hotbar (slots 0..8). */
    private fun selectGapByRegex(): Int? {
        val inv = kira.mc.thePlayer?.inventory ?: return null

        // --- Pass 1 : chercher une golden apple
        for (i in 0..8) {
            val stack = inv.getStackInSlot(i) ?: continue
            if (matchesGoldenApple(stack)) {
                inv.currentItem = i
                return i
            }
        }

        // --- Pass 2 : si rien, chercher une apple simple
        for (i in 0..8) {
            val stack = inv.getStackInSlot(i) ?: continue
            if (matchesPlainApple(stack)) {
                inv.currentItem = i
                return i
            }
        }

        return null
    }

    private fun ensureGapStillSelected(initialSlot: Int): Boolean {
        val player = kira.mc.thePlayer ?: return false
        val inv = player.inventory ?: return false

        if (matchesAnyGap(player.heldItem)) return true

        val stillGapInSlot = matchesAnyGap(inv.getStackInSlot(initialSlot))
        if (stillGapInSlot) {
            Inventory.setInvSlot(initialSlot)
            return matchesAnyGap(player.heldItem)
        }

        val reselection = selectGapByRegex()
        return reselection != null && matchesAnyGap(player.heldItem)
    }

    /**
     * Déroulé d’utilisation de la pomme (identique côté timings/mouvements).
     * Seule la sélection d’item change (regex au lieu d’une liste).
     */
    fun useGap(distance: Float, run: Boolean, facingAway: Boolean) {
        if (Mouse.rClickDown) Mouse.rClickUp()
        lastGap = System.currentTimeMillis()

        fun gap() {
            Mouse.stopLeftAC()

            val gapSlot = selectGapByRegex()
            if (gapSlot != null) {
                ChatUtils.info("About to gap (regex)")
                val r = RandomUtils.randomIntInRange(2100, 2200)      // durée "manger"
                val equipDelay = RandomUtils.randomIntInRange(80, 130) // petit settle après switch

                var startedEating = false

                TimeUtils.setTimeout({
                    if (!ensureGapStillSelected(gapSlot)) {
                        ChatUtils.warn("Gap lost before eating; aborting right click")
                        return@setTimeout
                    }
                    startedEating = true
                    Mouse.rClick(r)
                }, equipDelay)

                val finishDelay = equipDelay + r + RandomUtils.randomIntInRange(200, 300)
                TimeUtils.setTimeout({
                    if (!startedEating) return@setTimeout
                    Inventory.setInvItem("sword")
                    TimeUtils.setTimeout({
                        Mouse.setRunningAway(false)
                    }, RandomUtils.randomIntInRange(200, 400))
                }, finishDelay)
            } else {
                // Log utile si jamais rien n’a matché (diagnostic serveur/pack)
                ChatUtils.warn("No golden apple (or apple) found by regex in hotbar")
            }
        }

        // Timings distance (inchangés)
        val time = when (distance) {
            in 0f..7f -> RandomUtils.randomIntInRange(2200, 2600)
            in 7f..15f -> RandomUtils.randomIntInRange(1700, 2200)
            else -> RandomUtils.randomIntInRange(1400, 1700)
        }

        if (run && !facingAway) {
            Mouse.setUsingProjectile(false)
            Mouse.setRunningAway(true)
            Movement.startJumping()
            TimeUtils.setTimeout({ gap() }, time)
        } else {
            gap()
        }
    }
}
