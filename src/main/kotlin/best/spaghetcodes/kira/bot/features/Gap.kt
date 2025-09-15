package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.ChatUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils

interface Gap {

    var lastGap: Long

    fun useGap(distance: Float, run: Boolean, facingAway: Boolean) {
        if (Mouse.rClickDown) Mouse.rClickUp()
        lastGap = System.currentTimeMillis()
        fun gap() {
            Mouse.stopLeftAC()
            val gapNames = listOf(
                "minecraft:golden_apple",
                "item.minecraft.golden_apple",
                "applegold",
                "golden_apple",
                "apple",
                "pomme",
                "pomme_doree",
                "gap",
                "gapple"
            )
            val equipped = gapNames.any { Inventory.setInvItem(it) }
            if (equipped) {
                ChatUtils.info("About to gap")
                val r = RandomUtils.randomIntInRange(2100, 2200)
                val equipDelay = RandomUtils.randomIntInRange(80, 130)

                TimeUtils.setTimeout(fun () {
                    Mouse.rClick(r)
                }, equipDelay)

                TimeUtils.setTimeout(fun () {
                    Inventory.setInvItem("sword")

                    TimeUtils.setTimeout(fun () {
                        Mouse.setRunningAway(false)
                    }, RandomUtils.randomIntInRange(200, 400))
                }, equipDelay + r + RandomUtils.randomIntInRange(200, 300))
            }
        }

        val time = when (distance) {
            in 0f..7f -> RandomUtils.randomIntInRange(2200, 2600)
            in 7f..15f -> RandomUtils.randomIntInRange(1700, 2200)
            else -> RandomUtils.randomIntInRange(1400, 1700)
        }
        if (run && !facingAway) {
            Mouse.setUsingProjectile(false)
            Mouse.setRunningAway(true)
            Movement.startJumping()

            TimeUtils.setTimeout(fun () { gap() }, time)
        } else {
            gap()
        }
    }

}