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

    override fun getName(): String = "Classic"

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
    private var lastCounterRod = 0L         // NEW: anti-rod mid-range cooldown

    var shotsFired = 0
    var maxArrows = 5

    override fun onGameStart() {
        Mouse.startTracking()                 // tracking permanent
        Movement.startSprinting()
        Movement.startForward()
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(400, 1200))

        // Tir d’ouverture (full charge via Bow.kt) si aucune action en cours
        TimeUtils.setTimeout({
            val opp = opponent()
            if (opp != null && shotsFired < maxArrows && !Mouse.isUsingProjectile()) {
                val d = EntityUtils.getDistanceNoY(mc.thePlayer, opp)
                useBow(d) { shotsFired++ }
            }
        }, RandomUtils.randomIntInRange(300, 500))
    }

    override fun onGameEnd() {
        shotsFired = 0
        Mouse.stopLeftAC()
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
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        if (distance < 3f) {
            if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
                val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
                if (n.contains("rod")) {
                    // W-Tap long après hit à la rod (affiché dans le chat)
                    ChatUtils.info("W-Tap 300")
                    Combat.wTap(300)
                    tapping = true
                    combo--
                    TimeUtils.setTimeout(fun () { tapping = false }, 300)
                }
                // pas de block-hit à l’épée — l’attaque est gérée par ton autre mod
            }
        } else {
            // Petit W-Tap pour maintenir la pression à distance (affiché)
            ChatUtils.info("W-Tap 100")
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

            val opp = opponent()!!
            val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opp)

            // Tracking permanent + auto-CPS OFF (laisse ton autre mod cliquer)
            Mouse.startTracking()
            Mouse.stopLeftAC()

            // Sauts “humains”
            if (distance > jumpDistanceThreshold) {
                if (opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")) {
                    if (WorldUtils.blockInFront(mc.thePlayer, 2f, 0.5f) == Blocks.air) {
                        if (!EntityUtils.entityFacingAway(mc.thePlayer, opp) && !needJump) {
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

            val movePriority = arrayListOf(0, 0)
            var clear = false
            var randomStrafe = false

            if (distance < 1f || (distance < 2.7f && combo >= 1)) {
                Movement.stopForward()
            } else if (!tapping) {
                Movement.startForward()
            }

            // Ne JAMAIS ré-équiper l’épée si une action projectile est en cours,
            // si le verrou rod n’est pas expiré, ou si le clic droit est encore appuyé
            if (!Mouse.isUsingProjectile() && System.currentTimeMillis() >= rodLockUntil && !Mouse.rClickDown) {
                if (distance < 1.5f && mc.thePlayer.heldItem != null &&
                    !mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")) {
                    Inventory.setInvItem("sword")
                }
            }

            // Fenêtrage (façon OP) : ni rod ni bow si une autre action est en cours
            if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {

                // ---- (1) Anti-rod mid-range (NOUVEAU) ----
                // Si l’adversaire nous fait face à mi-distance et entretient un loop rod->épée,
                // on contre en envoyant NOTRE rod à 3.4..5.2 : ça casse son sprint et ouvre le melee.
                val now = System.currentTimeMillis()
                val canCounterRod = (now - lastCounterRod) > 700   // anti-spam ~0.7s
                if (canCounterRod &&
                    distance in 3.4f..5.2f &&
                    !EntityUtils.entityFacingAway(mc.thePlayer, opp)) {

                    // Verrou court, adapté à la distance, pour laisser connecter le flotteur
                    val lockMs = when {
                        distance < 4.0f -> RandomUtils.randomIntInRange(260, 320)
                        distance < 4.6f -> RandomUtils.randomIntInRange(300, 360)
                        else            -> RandomUtils.randomIntInRange(340, 420)
                    }
                    rodLockUntil = now + lockMs
                    lastCounterRod = now
                    useRod()
                }

                // ---- (2) Rod windows classiques ----
                else if ((distance in 5.7f..6.5f || distance in 9.0f..9.5f) &&
                         !EntityUtils.entityFacingAway(mc.thePlayer, opp) &&
                         System.currentTimeMillis() >= rodLockUntil) {

                    val lockMs = when {
                        distance < 4f    -> RandomUtils.randomIntInRange(260, 320)
                        distance < 6.7f  -> RandomUtils.randomIntInRange(320, 380)
                        distance < 9.7f  -> RandomUtils.randomIntInRange(380, 460)
                        else             -> RandomUtils.randomIntInRange(460, 560)
                    }
                    rodLockUntil = System.currentTimeMillis() + lockMs
                    useRod()

                // ---- (3) Bow windows (en plus du tir d’ouverture) ----
                } else if ((EntityUtils.entityFacingAway(mc.thePlayer, opp) && distance in 3.5f..30f) ||
                           (distance in 28.0f..33.0f && !EntityUtils.entityFacingAway(mc.thePlayer, opp))) {
                    if (distance > 10f && shotsFired < maxArrows) {
                        clear = true
                        useBow(distance) { shotsFired++ }
                    } else {
                        clear = false
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                        else movePriority[1] += 4
                    }
                }
            }

            if (combo >= 3 && distance >= 3.2f && mc.thePlayer.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
            }

            // Strafe / priorités (inchangé)
            if (!clear) {
                if (EntityUtils.entityFacingAway(mc.thePlayer, opp)) {
                    if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
                } else {
                    if (distance in 15.0f..8.0f) {
                        randomStrafe = true
                    } else {
                        randomStrafe = false
                        if (opp.heldItem != null &&
                            (opp.heldItem.unlocalizedName.lowercase().contains("bow") ||
                             opp.heldItem.unlocalizedName.lowercase().contains("rod"))) {
                            randomStrafe = true
                            if (distance < 15f && !needJump) Movement.stopJumping()
                        } else if (distance < 8f) {
                            val rotations = EntityUtils.getRotations(opp, mc.thePlayer, false)
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
