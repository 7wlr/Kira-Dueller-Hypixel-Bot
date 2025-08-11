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
import kotlin.math.abs
import kotlin.math.max

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

    // ————— Constantes de tuning (toutes utilisées) —————
    private val jumpDistanceThreshold = 5.0f
    private val noJumpCloseDist = 2.2f
    private val fullDrawMsMin = 820
    private val fullDrawMsMax = 980
    private val openShotMinDist = 9.0f
    private val parryMinDist = 11.0f
    private val stillFrameThreshold = 0.0125   // delta (x/z) par tick considéré “immobile”
    private val stillFramesNeeded = 10         // ~0.5s à 20 TPS
    private val parryCooldownMs = 900L
    private val parryHoldMinMs = 650L
    private val parryHoldMaxMs = 1050L
    private val bowCancelApproachDist = 6.0f
    private val singleJumpMinDist = 2.8f

    // ————— États —————
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var stagnantSince = 0L
    private var cornerBreakUntil = 0L

    private var rodLockUntil = 0L
    private var lastRodUse = 0L
    private var prevDistance = -1f

    private var gameStartAt = 0L
    private var lastSwordBlock = 0L
    private var holdBlockUntil = 0L

    private var noJumpUntil = 0L
    private var lastHurtTime = 0

    private var shotsFired = 0
    private val maxArrows = 5

    // suivi immobilité robuste (compte de frames quasi immobiles)
    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private var stillFrames = 0

    // verrou d’arc pour forcer la charge complète
    private var bowHardLockUntil = 0L

    // ————— Lifecycle —————
    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(400, 1200))

        prevDistance = -1f
        lastRodUse = 0L
        rodLockUntil = 0L
        lastStrafeSwitch = 0L
        stagnantSince = 0L
        cornerBreakUntil = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1

        Mouse.rClickUp()
        gameStartAt = System.currentTimeMillis()
        lastSwordBlock = 0L
        holdBlockUntil = 0L

        noJumpUntil = 0L
        lastHurtTime = 0

        shotsFired = 0
        bowHardLockUntil = 0L
        stillFrames = 0
        oppLastX = 0.0
        oppLastZ = 0.0

        // Tir d’ouverture (charge complète) si l’adversaire est à bonne distance
        TimeUtils.setTimeout({
            val opp = opponent()
            if (opp != null && !Mouse.isUsingProjectile()) {
                val d = EntityUtils.getDistanceNoY(mc.thePlayer, opp)
                if (d >= openShotMinDist && shotsFired < maxArrows) {
                    val now = System.currentTimeMillis()
                    bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                    useBow(d) { shotsFired++ }
                }
            }
        }, RandomUtils.randomIntInRange(350, 650))
    }

    override fun onGameEnd() {
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    // ————— Combat hooks —————
    private var tapping = false

    override fun onAttack() {
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        if (distance < 3f) {
            if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
                val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
                if (n.contains("rod")) {
                    ChatUtils.info("W-Tap 300")
                    Combat.wTap(300)
                    tapping = true
                    combo--
                    TimeUtils.setTimeout({ tapping = false }, 300)
                }
            }
        } else {
            ChatUtils.info("W-Tap 100")
            Combat.wTap(100)
            tapping = true
            TimeUtils.setTimeout({ tapping = false }, 100)
        }
        if (combo >= 3) Movement.clearLeftRight()
    }

    // ————— Tick principal —————
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val w = mc.theWorld ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        // Bloque le jump juste après avoir été touché
        val ht = p.hurtTime
        if (ht > 0 && lastHurtTime == 0) {
            noJumpUntil = now + RandomUtils.randomIntInRange(340, 520)
        }
        lastHurtTime = ht

        // Détecte obstacles très proches → mini jump dirigé (mais pas si cible trop proche)
        var needJump = false
        if (distance > noJumpCloseDist) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                needJump = true
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
            }
        }

        // ——— Immobilité de l’adversaire : frames quasi immobiles ———
        if (oppLastX == 0.0 && oppLastZ == 0.0) {
            oppLastX = opp.posX; oppLastZ = opp.posZ
        }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        oppLastX = opp.posX; oppLastZ = opp.posZ
        val isStill = stillFrames >= stillFramesNeeded

        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")

        // ——— Charge d'arc “hard lock” : ne pas casser la charge avant l’échéance, sauf danger proche ———
        if (Mouse.isUsingProjectile()) {
            if (distance < bowCancelApproachDist || approaching) {
                // danger : relâche tôt (Bow.kt libère sur rClickUp)
                Mouse.rClickUp()
                bowHardLockUntil = 0L
            } else if (now >= bowHardLockUntil && bowHardLockUntil != 0L) {
                // charge complète atteinte : on laisse Bow.kt relâcher à pleine puissance
                // (pas d’action ici, on évite juste toute annulation)
            }
        }

        // ——— Logique de parade ÉPÉE ———
        if (holdingSword) {
            if (Mouse.rClickDown) {
                // on tient la parade : on coupe dès qu’il bouge OU dès que la fenêtre prévue expire
                val moving = !isStill || approaching
                if (moving || now >= holdBlockUntil) {
                    // 80% arrêt immédiat, 20% on garde un peu (bait)
                    val stopNow = RandomUtils.randomIntInRange(0, 99) < 80
                    if (stopNow) {
                        Mouse.rClickUp()
                    } else {
                        // on prolonge légèrement mais max 300ms pour éviter le “block inutile”
                        holdBlockUntil = max(holdBlockUntil, now + RandomUtils.randomIntInRange(120, 300))
                    }
                }
            } else {
                // conditions d’entrée : pas trop tôt, assez loin, ennemi immobile, LoS propre, pas d’arc en cours
                val sinceStart = now - gameStartAt
                val canStartParry =
                    sinceStart > 1500 &&
                    distance >= parryMinDist &&
                    isStill &&
                    !Mouse.isUsingProjectile() &&
                    WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                    (now - lastSwordBlock) > parryCooldownMs

                if (canStartParry) {
                    // Ne pas être 100% lisible : 65% de chance de parer
                    if (RandomUtils.randomIntInRange(0, 99) < 65) {
                        val dur = RandomUtils.randomIntInRange(parryHoldMinMs.toInt(), parryHoldMaxMs.toInt())
                        holdBlockUntil = now + dur
                        lastSwordBlock = now
                        Mouse.rClick(dur)
                    }
                } else if (Mouse.rClickDown) {
                    // sécurité : jamais coincé en parade si conditions cassées
                    Mouse.rClickUp()
                }
            }
        } else {
            // si pas épée, on ne veut pas de parade active
            if (Mouse.rClickDown) Mouse.rClickUp()
        }

        // ——— Saut : jamais très proche, ni pendant un “noJumpUntil”, ni pendant une parade ———
        val canJump = (now >= noJumpUntil) && !Mouse.rClickDown
        if (distance <= noJumpCloseDist) {
            Movement.stopJumping()
        } else {
            if (distance > jumpDistanceThreshold) {
                if (canJump && !needJump) Movement.startJumping() else Movement.stopJumping()
            } else if (!needJump) {
                Movement.stopJumping()
            }
        }

        // ——— Gestion des déplacements de base ———
        if (distance < 1f || (distance < 2.7f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        if (!Mouse.isUsingProjectile() && now >= rodLockUntil && !Mouse.rClickDown) {
            if (distance < 1.5f && p.heldItem != null && !p.heldItem.unlocalizedName.lowercase().contains("sword")) {
                Inventory.setInvItem("sword")
            }
        }

        // ——— Fenêtres rod/bow ———
        if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {

            val cdOK = (now - lastRodUse) >= if (distance < 5.3f) 650 else 900

            // (A) Anti-bow mid-range
            if (cdOK && oppHasBow && distance in 4.8f..7.2f && !EntityUtils.entityFacingAway(p, opp) && now >= rodLockUntil) {
                val lockMs = if (distance < 6f) RandomUtils.randomIntInRange(300, 360) else RandomUtils.randomIntInRange(340, 420)
                rodLockUntil = now + lockMs
                lastRodUse = now
                useRod()

            // (B) Anti-rod mid classique
            } else if (cdOK &&
                distance in 3.4f..5.2f &&
                !EntityUtils.entityFacingAway(p, opp) &&
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

            // (C) Fenêtre 5.7..6.5 ajustée
            } else if (cdOK &&
                distance in 5.7f..6.5f &&
                !EntityUtils.entityFacingAway(p, opp) &&
                approaching &&
                combo <= 1 &&
                now >= rodLockUntil) {

                val lockMs = if (distance < 6.1f) RandomUtils.randomIntInRange(320, 380) else RandomUtils.randomIntInRange(360, 440)
                rodLockUntil = now + lockMs
                lastRodUse = now
                useRod()

            // (D) Bow : tirs “safe” (et full charge via Bow.kt + hard lock)
            } else if ((EntityUtils.entityFacingAway(p, opp) && distance in 3.5f..30f) ||
                       (distance in 28.0f..33.0f && !EntityUtils.entityFacingAway(p, opp))) {
                if (distance > 10f && shotsFired < maxArrows) {
                    bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                    useBow(distance) { shotsFired++ }
                }
            }
        }

        // Pas de singleJump anti-corner si trop proche
        if (combo >= 3 && distance >= singleJumpMinDist && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
        }

        // ——— Anti-corner & Strafe ———
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        val blockAheadClose = WorldUtils.blockInFront(p, 1.2f, 1.0f) != Blocks.air
        val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
        val cornerLikely = (distance < 3.8f && (blockAheadClose || deltaDist < 0.02f))
        if (cornerLikely && now >= cornerBreakUntil) {
            strafeDir = -strafeDir
            cornerBreakUntil = now + RandomUtils.randomIntInRange(280, 420)
            if (p.onGround && distance >= singleJumpMinDist) {
                Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            }
        }

        if (!clear) {
            if (EntityUtils.entityFacingAway(p, opp)) {
                if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
            } else {
                val rotations = EntityUtils.getRotations(opp, p, false)
                if (rotations != null && now - lastStrafeSwitch > 350) {
                    val preferSide = if (rotations[0] < 0) +1 else -1
                    if (preferSide != strafeDir) {
                        strafeDir = preferSide
                        lastStrafeSwitch = now
                    }
                }
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

                if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(950, 1200)) {
                    strafeDir = -strafeDir
                    lastStrafeSwitch = now
                }

                val weight = if (now < cornerBreakUntil) 8 else if (distance < 4f) 7 else 5
                if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight

                randomStrafe = (distance in 8.0f..15.0f) || (oppHasBow && distance > 8.0f)
            }
        }

        handle(clear, randomStrafe, movePriority)
        prevDistance = distance
    }
}
