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

    // --- Strafe state ---
    private var strafeDir = 1                 // -1 = gauche, +1 = droite
    private var lastStrafeSwitch = 0L         // dernier switch de côté
    private var stagnantSince = 0L            // début d’une distance quasi stable (pour break-orbit)
    private var cornerBreakUntil = 0L         // fenêtre forcée de “décrochage” (anti-corner)

    // --- Rod control ---
    private var rodLockUntil = 0L
    private var lastRodUse = 0L        // cooldown global rod
    private var prevDistance = -1f     // pour détecter si l’adversaire APPROCHE

    var shotsFired = 0
    var maxArrows = 5

    override fun onGameStart() {
        Mouse.startTracking()                 // tracking permanent
        Movement.startSprinting()
        Movement.startForward()
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(400, 1200))

        prevDistance = -1f
        lastRodUse = 0L
        rodLockUntil = 0L
        lastStrafeSwitch = 0L
        stagnantSince = 0L
        cornerBreakUntil = 0L
        // aléa initial de côté
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1

        // anti “block d’épée” fantôme dès l’init
        Mouse.rClickUp()

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
                    // W-Tap long après hit à la rod (affiché)
                    ChatUtils.info("W-Tap 300")
                    Combat.wTap(300)
                    tapping = true
                    combo--
                    TimeUtils.setTimeout(fun () { tapping = false }, 300)
                }
                // pas de block-hit à l’épée — l’attaque est gérée par ton autre mod
            }
        } else {
            // Petit W-Tap pour maintenir la pression (affiché)
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
            val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)
            val now = System.currentTimeMillis()

            // Tracking permanent + auto-CPS OFF (laisse ton autre mod cliquer)
            Mouse.startTracking()
            Mouse.stopLeftAC()

            // --- Anti “block d’épée” non désiré ---
            val holdingSword = mc.thePlayer.heldItem != null &&
                mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")
            val oppSpeed = kotlin.math.abs(opp.motionX) + kotlin.math.abs(opp.motionZ)
            val farAndStill = (distance > 11.5f && oppSpeed < 0.04)

            // On n’autorise le block à l’épée QUE si loin & immobile ; sinon on lève.
            if (holdingSword) {
                if (!farAndStill && Mouse.rClickDown) {
                    Mouse.rClickUp()
                } else if (farAndStill && !Mouse.isUsingProjectile() && !Mouse.rClickDown) {
                    // petit block court (défensif vs bow/placement) et on relâche aussitôt
                    Mouse.rClick(RandomUtils.randomIntInRange(120, 180))
                }
            }

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

            // Ne JAMAIS ré-équiper l’épée si action projectile en cours,
            // si le verrou rod n’est pas expiré, ou si le clic droit est encore appuyé
            if (!Mouse.isUsingProjectile() && now >= rodLockUntil && !Mouse.rClickDown) {
                if (distance < 1.5f && mc.thePlayer.heldItem != null &&
                    !mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")) {
                    Inventory.setInvItem("sword")
                }
            }

            // Fenêtrage (façon OP) : ni rod ni bow si une autre action est en cours
            if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {

                // --- Anti-rod mid-range (3.4..5.2), seulement si APPROCHE & face & combo faible ---
                val minCd = if (distance < 5.3f) 650 else 900
                val cdOK = (now - lastRodUse) >= minCd
                if (cdOK &&
                    distance in 3.4f..5.2f &&
                    !EntityUtils.entityFacingAway(mc.thePlayer, opp) &&
                    approaching &&
                    combo <= 1 &&
                    now >= rodLockUntil) {

                    val lockMs = when {
                        distance < 4.0f -> RandomUtils.randomIntInRange(260, 320)
                        distance < 4.6f -> RandomUtils.randomIntInRange(300, 360)
                        else            -> RandomUtils.randomIntInRange(340, 420)
                    }
                    rodLockUntil = now + lockMs
                    lastRodUse = now
                    useRod()

                // --- Fenêtre classique resserrée (5.7..6.5), seulement si APPROCHE & face & combo faible ---
                } else if (cdOK &&
                           distance in 5.7f..6.5f &&
                           !EntityUtils.entityFacingAway(mc.thePlayer, opp) &&
                           approaching &&
                           combo <= 1 &&
                           now >= rodLockUntil) {

                    val lockMs = if (distance < 6.1f)
                        RandomUtils.randomIntInRange(320, 380)
                    else
                        RandomUtils.randomIntInRange(360, 440)
                    rodLockUntil = now + lockMs
                    lastRodUse = now
                    useRod()

                // --- Bow windows (en plus du tir d’ouverture) ---
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

            // --- Anti-corner & strafe ---
            // Détection coin/bloc devant en mêlée ⇒ forcer un “décrochage” latéral court
            val blockAheadClose = WorldUtils.blockInFront(mc.thePlayer, 1.2f, 1.0f) != Blocks.air
            val deltaDist = if (prevDistance > 0f) kotlin.math.abs(distance - prevDistance) else 999f
            val cornerLikely = (distance < 3.8f && (blockAheadClose || deltaDist < 0.02f))

            if (cornerLikely && now >= cornerBreakUntil) {
                strafeDir = -strafeDir
                cornerBreakUntil = now + RandomUtils.randomIntInRange(280, 420)
                if (mc.thePlayer.onGround) {
                    Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
                }
            }

            if (!clear) {
                if (EntityUtils.entityFacingAway(mc.thePlayer, opp)) {
                    // Fuite : chase tout droit avec un léger biais
                    if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
                } else {
                    // Contrôle de côté “intelligent”
                    val rotations = EntityUtils.getRotations(opp, mc.thePlayer, false)
                    if (rotations != null && now - lastStrafeSwitch > 350) {
                        val preferSide = if (rotations[0] < 0) +1 else -1  // yaw<0 → strafe droite, sinon gauche
                        if (preferSide != strafeDir) {
                            strafeDir = preferSide
                            lastStrafeSwitch = now
                        }
                    }

                    // Break-orbit : distance quasi stable ⇒ inverser
                    if (distance in 1.8f..3.6f) {
                        if (deltaDist < 0.03f) {
                            if (stagnantSince == 0L) stagnantSince = now
                            else if (now - stagnantSince > 550 && now - lastStrafeSwitch > 300) {
                                strafeDir = -strafeDir
                                lastStrafeSwitch = now
                                stagnantSince = 0L
                            }
                        } else stagnantSince = 0L
                    } else stagnantSince = 0L

                    // Alternance contrôlée
                    if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(950, 1200)) {
                        strafeDir = -strafeDir
                        lastStrafeSwitch = now
                    }

                    // Si on est en fenêtre “cornerBreak”, forcer le strafe côté choisi plus fort
                    val weight = if (now < cornerBreakUntil) 8 else if (distance < 4f) 7 else 5
                    if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight

                    // Random strafe utile surtout mid-long range
                    randomStrafe = distance in 8.0f..15.0f
                }
            }

            handle(clear, randomStrafe, movePriority)

            // Mémorise pour le tick suivant
            prevDistance = distance
        }
    }
}
