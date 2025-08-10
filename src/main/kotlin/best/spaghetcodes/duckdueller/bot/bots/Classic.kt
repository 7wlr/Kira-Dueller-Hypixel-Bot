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

    // --- Paramètres comportementaux ---
    private val jumpDistanceThreshold = 5.0      // Sauter dès que l’ennemi est > 5 blocs
    // bowCancelCloseDistance vient de Bow (6.0 par défaut)

    var shotsFired = 0
    var maxArrows = 5

    override fun onGameStart() {
        Mouse.stopTracking()          // auto-aim OFF
        Movement.startSprinting()
        Movement.startForward()
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(400, 1200))
    }

    override fun onGameEnd() {
        shotsFired = 0
        Mouse.stopLeftAC()            // auto-CPS OFF (sécurité)
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout(fun () {
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    var tapping = false

    override fun onAttack() {
        // Pas d’auto-CPS ici. On garde w-tap / blockhit pour le “feeling” humain.
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        if (distance < 3) {
            if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
                val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
                if (n.contains("rod")) {
                    Combat.wTap(300)
                    tapping = true
                    combo--
                    TimeUtils.setTimeout(fun () { tapping = false }, 300)
                } else if (n.contains("sword")) {
                    Mouse.rClick(RandomUtils.randomIntInRange(80, 100)) // petit blockhit
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

            // auto-aim OFF / auto-CPS OFF (idempotent)
            Mouse.stopTracking()
            Mouse.stopLeftAC()

            // Saut “humain” > 5 blocs
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

            // Si on charge l’arc et que l’adversaire re-engage: abandon immédiat (double sécurité)
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

            if (distance < 1 || (distance < 2.7 && combo >= 1)) {
                Movement.stopForward()
            } else if (!tapping) {
                Movement.startForward()
            }

            if (distance < 1.5 && mc.thePlayer.heldItem != null && !mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")) {
                Inventory.setInvItem("sword")
                Mouse.rClickUp()
            }

            // ---- Rod windows
            if ((distance in 5.7..6.5 || distance in 9.0..9.5) && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                if (!Mouse.isUsingProjectile()) useRod()
            }

            if (combo >= 3 && distance >= 3.2 && mc.thePlayer.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
            }

            // ---- Arc (tirs “safe” uniquement)
            if ((EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) && distance in 3.5..30.0) ||
                (distance in 28.0..33.0 && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!))) {
                if (distance > 5 && !Mouse.isUsingProjectile() && shotsFired < maxArrows) {
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
                    // Condition d’origine conservée (ordre inversé) pour patch minimal
                    if (distance in 15.0..8.0) {
                        randomStrafe = true
                    } else {
                        randomStrafe = false
                        if (opponent() != null && opponent()!!.heldItem != null &&
                            (opponent()!!.heldItem.unlocalizedName.lowercase().contains("bow") ||
                             opponent()!!.heldItem.unlocalizedName.lowercase().contains("rod"))) {
                            randomStrafe = true
                            if (distance < 15 && !needJump) Movement.stopJumping()
                        } else if (distance < 8) {
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

}
