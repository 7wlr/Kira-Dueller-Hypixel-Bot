package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.ChatUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.kira

/**
 * Gestion "pomme dorée" par regex (sans liste fermée).
 * On détecte d'abord les variantes GOLDEN APPLE, puis on retombe sur APPLE.
 */
interface Gap {

    var lastGap: Long

    /** Sélection d’une gap via REGEX sur hotbar (slots 0..8). */
    private fun selectGapByRegex(): Boolean {
        val inv = kira.mc.thePlayer?.inventory ?: return false

        // 1) GOLDEN APPLE d’abord (priorité)
        //   - "minecraft:golden_apple", "golden apple", "gold apple",
        //   - "item.appleGold" (ancien unlocalized),
        //   - "applegold" / "apple gold",
        //   - "pomme dorée" / "pomme_doree" (FR, avec/ sans accents).
        val goldenAppleRegex = Regex(
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

        // 2) Fallback : APPLE simple (si un pack renomme l’item -> “apple”/“pomme”)
        //    On le prend seulement si aucune "golden apple" n’a été trouvée.
        val plainAppleRegex = Regex(
            pattern = """\b(apple|pomme)\b""",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        // --- Pass 1 : chercher une golden apple
        for (i in 0..8) {
            val stack = inv.getStackInSlot(i) ?: continue
            val combined = (stack.unlocalizedName + " " + stack.displayName).lowercase()
            if (goldenAppleRegex.containsMatchIn(combined)) {
                inv.currentItem = i
                return true
            }
        }

        // --- Pass 2 : si rien, chercher une apple simple
        for (i in 0..8) {
            val stack = inv.getStackInSlot(i) ?: continue
            val combined = (stack.unlocalizedName + " " + stack.displayName).lowercase()
            if (plainAppleRegex.containsMatchIn(combined)) {
                inv.currentItem = i
                return true
            }
        }

        return false
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

            val equipped = selectGapByRegex()
            if (equipped) {
                ChatUtils.info("About to gap (regex)")
                val r = RandomUtils.randomIntInRange(2100, 2200)      // durée "manger"
                val equipDelay = RandomUtils.randomIntInRange(80, 130) // petit settle après switch

                TimeUtils.setTimeout({ Mouse.rClick(r) }, equipDelay)

                TimeUtils.setTimeout({
                    Inventory.setInvItem("sword")
                    TimeUtils.setTimeout({
                        Mouse.setRunningAway(false)
                    }, RandomUtils.randomIntInRange(200, 400))
                }, equipDelay + r + RandomUtils.randomIntInRange(200, 300))
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
