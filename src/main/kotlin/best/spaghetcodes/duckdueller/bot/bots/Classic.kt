package best.spaghetcodes.duckdueller.bot.bots

import best.spaghetcodes.duckdueller.DuckDueller
import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.features.Bow
import best.spaghetcodes.duckdueller.bot.features.MovePriority
import best.spaghetcodes.duckdueller.bot.features.Rod
import best.spaghetcodes.duckdueller.bot.player.Combat
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.bot.player.Movement
import best.spaghetcodes.duckdueller.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3

class Classic : BotBase("/play duels_classic_duel"), Bow, Rod, MovePriority {

    override fun getName(): String {
        return "Classic"
    }

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.classic_duel_wins",
                "losses" to "player.stats.Duels.classic_duel_losses",
                "ws" to "player.stats.Duels.current_classic_winstreak",
            )
        )
    }

    private val jumpDistanceThreshold = 5.0f
    private var rodLockUntil = 0L

    var shotsFired = 0
    var maxArrows = 5

    override fun onGameStart() {
        Mouse.startTracking()         // tracking ON
        Movement.startSprinting()
        Movement.startForward()
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(400, 1200))
    }

    override fun onGameEnd() {
        shotsFired = 0
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout(fun () {
            i?.cancel()
            Mouse.stopTracking()      // clean
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    var tapping = false

    override fun onAttack() {
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        if (distance < 3f) {
            if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
                val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
                if (n.contains("rod")) {
                    Combat.wTap(300)
                    tapping = true
                    combo--
                    TimeUtils.setTimeout(fun () { tapping = false }, 300)
                } else if (n.contains("sword")) {
                    Mouse.rClick(RandomUtils.randomIntInRange(80, 100))
                }
            }
        } else {
            Combat.wTap(100)
            tapping = true
            TimeUtils.setTimeout(fun () { tapping = false }, 100)
        }
        if (combo >= 3) Movement.clearLeftRight()
    }

    override fun onTick() {
        var needJump = false
        if (mc.thePlayer != null) {
            if (WorldUtils.blockInFront(mc.thePlayer, 2f, 0.5f) != Blocks.air && mc.thePlayer.onGround) {
                needJump = true
                Movement.singleJump(RandomUtils.randomIntInRange(150, 250))
            }
        }
        if (opponent() != null && mc.theWorld != null && mc.thePlayer != null) {
            if (!mc.thePlayer.isSprinting) Movement.startSprinting()

            val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())

            // tracking ON en continu
            Mouse.startTracking()

            // auto-CPS OFF
            Mouse.stopLeftAC()

            if (distance > jumpDistanceThreshold) {
                if (opponent() != null && opponent()!!.heldItem != null && opponent()!!.heldItem.unlocalizedName.lowercase().contains("bow")) {
                    if (WorldUtils.blockInFront(mc.thePlayer, 2f, 0.5f) == Blocks.air) {
                        if (!EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) && !needJump) {
                            Movement.stopJumping()
                        } else {
                            Movement.startJumping()
                        }
                    } else {
                        Movement.startJumping()
                    }
                } else {
                    Movement.startJumping()
                }
            } else if (!needJump) {
                Movement.stopJumping()
            }

            // En charge d’arc : si pression, on lâche et on repasse épée (géré aussi dans Bow)
            if (Mouse.isUsingProjectile()) {
                val facingUs = !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)
                val tooClose = distance < bowCancelCloseDistance
                if (facingUs || tooClose) {
                    Mouse.rClickUp()
                    Inventory.setInvItem("sword")
                }
            }

            val movePriority = arrayListOf(0, 0)
            var clear = false
            var randomStrafe = false

            if (distance < 1f || (distance < 2.7f && combo >= 1)) {
                Movement.stopForward()
            } else if (!tapping) {
                Movement.startForward()
            }

            // NE PAS re-équiper épée pendant la fenêtre "rodLockUntil"
            if (System.currentTimeMillis() >= rodLockUntil) {
                if (distance < 1.5f && mc.thePlayer.heldItem != null && !mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")) {
                    Inventory.setInvItem("sword")
                    Mouse.rClickUp()
                }
            }

            // ---- Rod windows (Hypixel Classic) : lancer "réel" + retour épée après un court délai
            if ((distance in 5.7f..6.5f || distance in 9.0f..9.5f) && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                if (!Mouse.isUsingProjectile()) useRodClassic()
            }

            if (combo >= 3 && distance >= 3.2f && mc.thePlayer.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
            }

            // ---- Arc (tirs “safe” + full charge par défaut)
            if ((EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) && distance in 3.5f..30f) ||
                (distance in 28.0f..33.0f && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!))) {
                if (distance > 5f && !Mouse.isUsingProjectile() && shotsFired < maxArrows) {
                    clear = true
                    useBow(distance) { shotsFired++ }
                } else {
                    clear = false
                    if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                    else movePriority[1] += 4
                }
            } else {
                if (EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                    if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                    else movePriority[1] += 4
                } else {
                    if (distance in 15.0f..8.0f) {
                        randomStrafe = true
                    } else {
                        randomStrafe = false
                        if (opponent() != null && opponent()!!.heldItem != null &&
                            (opponent()!!.heldItem.unlocalizedName.lowercase().contains("bow") ||
                             opponent()!!.heldItem.unlocalizedName.lowercase().contains("rod"))) {
                            randomStrafe = true
                            if (distance < 15f && !needJump) Movement.stopJumping()
                        } else if (distance < 8f) {
                            val rotations = EntityUtils.getRotations(opponent()!!, mc.thePlayer, false)
                            if (rotations != null) {
                                if (rotations[0] < 0) movePriority[1] += 5 else movePriority[0] += 5
                            }
                        }
                    }
                }
            }

            handle(clear, randomStrafe, movePriority)
        }
    }

    /**
     * Lancer de canne optimisé Hypixel Classic :
     * - sort la canne, lance réellement (clic droit court),
     * - empêche de rééquiper l’épée pendant ~220–300ms,
     * - rééquipe l’épée juste après pour enchaîner le hit.
     */
    private fun useRodClassic() {
        rodLockUntil = System.currentTimeMillis() + RandomUtils.randomIntInRange(220, 300)
        if (Inventory.setInvItem("rod")) {
            Mouse.rClick(RandomUtils.randomIntInRange(90, 120)) // lancer du flotteur
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
            }, RandomUtils.randomIntInRange(180, 240))
        }
    }

}
