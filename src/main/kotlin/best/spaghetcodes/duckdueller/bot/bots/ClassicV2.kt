package best.spaghetcodes.duckdueller.bot.bots

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

class ClassicV2 : BotBase("/play duels_classic_duel"), Bow, Rod, MovePriority {

    override fun getName(): String = "ClassicV2"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.classic_duel_wins",
                "losses" to "player.stats.Duels.classic_duel_losses",
                "ws" to "player.stats.Duels.current_classic_winstreak",
            )
        )
    }

    // ---------------------- ARC ----------------------
    private val fullDrawMsMin = 820
    private val fullDrawMsMax = 980
    private val bowCancelCloseDist = 4.8f

    // Ouverture contrôlée
    private var openVolleyMax = 1
    private var openVolleyFired = 0
    private var openWindowUntil = 0L
    private var openStartDelayUntil = 0L
    private var lastShotAt = 0L
    private val openSpacingMin = 650L
    private val openSpacingMax = 900L
    private val openShotMinDist = 9.0f

    // Détection “immobile/slow-bow”
    private val stillFrameThreshold = 0.0125
    private val stillFramesNeeded = 10
    private val bowSlowThreshold = 0.06
    private val bowSlowFramesNeeded = 3
    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private var stillFrames = 0
    private var bowSlowFrames = 0

    private var shotsFired = 0
    private val maxArrows = 5
    private var bowHardLockUntil = 0L
    private var projectileGraceUntil = 0L
    private var pendingProjectileUntil = 0L
    private var actionLockUntil = 0L
    private var projectileKind = 0
    private val KIND_NONE = 0
    private val KIND_BOW = 2

    private var lastReactiveShotAt = 0L
    private val reactiveCdMs = 650L

    // Réserve d’arrows
    private var gameStartAt = 0L
    private val reserveTightMs = 10_000L
    private val earlyReserve = 3
    private val midReserve = 2

    // ---------------------- ROD ----------------------
    private var lastRodUse = 0L
    private val rodCdCloseMs = 380L
    private val rodCdFarMs = 520L

    private val rodCloseMin = 2.2f
    private val rodCloseMax = 3.2f
    private val rodMainMin = 3.2f
    private val rodMainMax = 6.2f
    private val rodInterceptMin = 5.8f
    private val rodInterceptMax = 6.6f

    // -------------------- PARADE ÉPÉE -----------------
    private val parryMinDist = 6.5f
    private val parryCooldownMs = 900L
    private val parryHoldMinMs = 650
    private val parryHoldMaxMs = 980
    private val parryStickMinMs = 900
    private val parryStickMaxMs = 1500

    private var lastSwordBlock = 0L
    private var holdBlockUntil = 0L
    private var parryFromBow = false
    private var parryExtendedUntil = 0L

    // Strafe & saut pendant parade (imprévisible)
    private var parryStrafeDir = 1
    private var parryStrafeFlipAt = 0L
    private var lastParryJumpAt = 0L
    private val parryJumpCd = 580L

    // -------------------- MOUVEMENT -------------------
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f
    private var lastTacticalJumpAt = 0L
    private var lastGotHitAt = 0L
    private var tapping = false

    // ---------------------- LIFECYCLE -----------------
    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping() // jamais de saut “maintenu”

        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        Mouse.rClickUp()

        shotsFired = 0
        bowHardLockUntil = 0L
        projectileGraceUntil = 0L
        pendingProjectileUntil = 0L
        actionLockUntil = 0L
        projectileKind = KIND_NONE

        openVolleyMax = RandomUtils.randomIntInRange(1, 2)
        openVolleyFired = 0
        openWindowUntil = System.currentTimeMillis() + 4500L
        openStartDelayUntil = System.currentTimeMillis() + RandomUtils.randomIntInRange(700, 1100)
        lastShotAt = 0L

        oppLastX = 0.0
        oppLastZ = 0.0
        stillFrames = 0
        bowSlowFrames = 0

        lastReactiveShotAt = 0L
        gameStartAt = System.currentTimeMillis()

        lastRodUse = 0L
        lastTacticalJumpAt = 0L
        lastGotHitAt = 0L

        // Parade reset
        lastSwordBlock = 0L
        holdBlockUntil = 0L
        parryFromBow = false
        parryExtendedUntil = 0L
        parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        parryStrafeFlipAt = 0L
        lastParryJumpAt = 0L

        // Petit jump anti-coin au start
        TimeUtils.setTimeout({
            Movement.singleJump(RandomUtils.randomIntInRange(140, 220))
        }, RandomUtils.randomIntInRange(180, 320))
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

    override fun onAttack() {
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 100)
        if (combo >= 3) Movement.clearLeftRight()
    }

    // -------------------- HELPERS ---------------------
    private fun adjustedAimDistance(d: Float): Float {
        return when {
            d in 15.0f..22.0f -> d * 0.88f
            d in 22.0f..30.0f -> d * 0.86f
            d in 9.0f..15.0f  -> d * 0.92f
            else              -> d
        }
    }

    private fun chargeMsFor(distance: Float, opening: Boolean): Long {
        return if (opening) {
            RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
        } else {
            when {
                distance < 6.0f   -> RandomUtils.randomIntInRange(220, 320).toLong()
                distance < 10.0f  -> RandomUtils.randomIntInRange(320, 450).toLong()
                distance < 15.0f  -> RandomUtils.randomIntInRange(450, 650).toLong()
                distance < 25.0f  -> RandomUtils.randomIntInRange(550, 800).toLong()
                else              -> RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
            }
        }
    }

    private fun arrowsLeft(): Int = maxArrows - shotsFired
    private fun reserveNeeded(now: Long): Int =
        if (now - gameStartAt < reserveTightMs) earlyReserve else midReserve

    private fun castRodNow(distanceNow: Float) {
        val now = System.currentTimeMillis()

        // Annule un éventuel tir arc trop proche
        if (Mouse.rClickDown && projectileKind == KIND_BOW) {
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        Inventory.setInvItem("rod")
        Mouse.setUsingProjectile(true)
        Mouse.rClick(RandomUtils.randomIntInRange(80, 110))

        val settle = RandomUtils.randomIntInRange(240, 320)
        pendingProjectileUntil = now + 100L
        actionLockUntil = now + settle + 100
        projectileGraceUntil = actionLockUntil

        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            Mouse.setUsingProjectile(false)
        }, settle)

        lastRodUse = now
    }

    // ----------------------- TICK ---------------------
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()
        Movement.stopJumping() // par défaut

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        // MAJ immobile/slow
        if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
        oppLastX = opp.posX; oppLastZ = opp.posZ

        if (p.hurtTime > 0) lastGotHitAt = now

        // Anti-bloc devant
        if (distance > 2.2f) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
                lastTacticalJumpAt = now
            }
        }

        // Avance / arrêt
        if (distance < 1f || (distance < 2.7f && combo >= 1)) Movement.stopForward() else if (!tapping) Movement.startForward()

        // Tenir l'épée si très proche
        if (distance < 1.5f && p.heldItem != null && !p.heldItem.unlocalizedName.lowercase().contains("sword")) {
            Inventory.setInvItem("sword")
        }

        val projectileActive =
            Mouse.isUsingProjectile() || now < projectileGraceUntil || now < pendingProjectileUntil || now < actionLockUntil

        // Annuler l’arc si trop proche
        if (projectileActive && Mouse.rClickDown) {
            if (projectileKind == KIND_BOW && distance < bowCancelCloseDist) {
                Mouse.rClickUp()
                bowHardLockUntil = 0L
                projectileGraceUntil = 0L
                pendingProjectileUntil = 0L
                actionLockUntil = 0L
                projectileKind = KIND_NONE
            }
        }

        // --------------------- PARADE ÉPÉE ---------------------
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")
        val isStill = stillFrames >= stillFramesNeeded
        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowLikely = oppHasBow && (isStill || bowSlowFrames >= bowSlowFramesNeeded)

        if (holdingSword) {
            if (Mouse.rClickDown) {
                // si trop proche & face à nous -> on stoppe la parade (éviter ralentissement)
                if (distance < 4.0f && !EntityUtils.entityFacingAway(p, opp)) {
                    Mouse.rClickUp()
                    parryFromBow = false
                    parryExtendedUntil = 0L
                } else {
                    val mustKeep = parryFromBow && now < parryExtendedUntil
                    if (!mustKeep && now >= holdBlockUntil) {
                        Mouse.rClickUp()
                        parryFromBow = false
                        parryExtendedUntil = 0L
                    } else {
                        // Esquive aléatoire pendant parade : saut latéral alterné
                        if (p.onGround &&
                            now - lastParryJumpAt >= parryJumpCd &&
                            !projectileActive &&
                            now - lastGotHitAt > 260) {

                            // 50% de chance de flip immédiat pour casser le rythme
                            if (RandomUtils.randomIntInRange(0, 1) == 1 || now >= parryStrafeFlipAt) {
                                parryStrafeDir = -parryStrafeDir
                                parryStrafeFlipAt = now + RandomUtils.randomIntInRange(260, 420)
                            }
                            Movement.singleJump(RandomUtils.randomIntInRange(140, 210))
                            lastParryJumpAt = now
                        }
                    }
                }
            } else {
                val sinceStart = now - gameStartAt
                val canStartParry =
                    sinceStart > 1600 &&
                    distance >= parryMinDist &&
                    (isStill || bowLikely) &&
                    !projectileActive &&
                    WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                    (now - lastSwordBlock) > parryCooldownMs

                if (canStartParry) {
                    val dur = RandomUtils.randomIntInRange(parryHoldMinMs, parryHoldMaxMs)
                    holdBlockUntil = now + dur
                    lastSwordBlock = now
                    parryFromBow = true
                    val extraStick =
                        if (distance > 5f) RandomUtils.randomIntInRange(600, 900)
                        else if (distance > 4f) RandomUtils.randomIntInRange(320, 520)
                        else 0
                    parryExtendedUntil = now + RandomUtils.randomIntInRange(parryStickMinMs, parryStickMaxMs) + extraStick
                    parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                    parryStrafeFlipAt = now + RandomUtils.randomIntInRange(240, 380)
                    Mouse.rClick(dur)
                }
            }
        } else {
            if (Mouse.rClickDown && !projectileActive) Mouse.rClickUp()
            parryFromBow = false
            parryExtendedUntil = 0L
        }
        // -------------------------------------------------------

        // --------- JUMPS CONTEXTUELS hors parade/proj ----------
        if (!Mouse.rClickDown && !projectileActive && !Mouse.rClickDown && (now - lastGotHitAt) > 260) {
            val facingAway = EntityUtils.entityFacingAway(p, opp)
            val oppVeryStill = (stillFrames >= 6)
            if (distance > 8.0f) {
                if (p.onGround && now - lastTacticalJumpAt >= 520) {
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                    lastTacticalJumpAt = now
                }
            } else if (distance in 4.5f..8.0f && (facingAway || oppVeryStill)) {
                if (p.onGround && now - lastTacticalJumpAt >= 720) {
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                    lastTacticalJumpAt = now
                }
            }
        }
        // -------------------------------------------------------

        // ----------------------- ROD ----------------------------
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            val cdCloseOK = (now - lastRodUse) >= rodCdCloseMs
            val cdFarOK = (now - lastRodUse) >= rodCdFarMs
            val facingAway = EntityUtils.entityFacingAway(p, opp)

            if (distance in rodCloseMin..rodCloseMax &&
                (p.hurtTime > 0 || approaching) &&
                !facingAway &&
                cdCloseOK) {
                castRodNow(distance)
                prevDistance = distance
                return
            }
            if (distance in rodMainMin..rodMainMax &&
                !facingAway &&
                cdFarOK) {
                castRodNow(distance)
                prevDistance = distance
                return
            }
            if (distance in rodInterceptMin..rodInterceptMax &&
                approaching &&
                !facingAway &&
                cdFarOK) {
                castRodNow(distance)
                prevDistance = distance
                return
            }
        }
        // -------------------------------------------------------

        // ------------------------ ARC --------------------------
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            val reserve = reserveNeeded(now)
            val left = arrowsLeft()

            if (shotsFired < maxArrows &&
                openVolleyFired < openVolleyMax &&
                now < openWindowUntil &&
                now >= openStartDelayUntil &&
                distance >= openShotMinDist &&
                left > reserve &&
                (now - lastShotAt) >= RandomUtils.randomIntInRange(openSpacingMin.toInt(), openSpacingMax.toInt())) {

                val tunedD = adjustedAimDistance(distance)
                val lock = chargeMsFor(distance, opening = true)
                bowHardLockUntil = now + lock
                pendingProjectileUntil = now + 60L
                actionLockUntil = now + (lock + 120)
                projectileKind = KIND_BOW
                useBow(tunedD) {
                    shotsFired++
                    openVolleyFired++
                    lastShotAt = System.currentTimeMillis()
                }
                projectileGraceUntil = bowHardLockUntil + 120
                prevDistance = distance
                return
            }

            val bowLikelyNow = oppHasBow && (stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)
            if (shotsFired < maxArrows &&
                bowLikelyNow &&
                now - lastReactiveShotAt >= reactiveCdMs &&
                WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                left > reserve) {

                val tunedD = adjustedAimDistance(distance)
                val lock = chargeMsFor(distance, opening = false)
                bowHardLockUntil = now + lock
                pendingProjectileUntil = now + 50L
                actionLockUntil = now + (lock + 100)
                projectileKind = KIND_BOW
                useBow(tunedD) {
                    shotsFired++
                    lastReactiveShotAt = System.currentTimeMillis()
                }
                projectileGraceUntil = bowHardLockUntil + 100
                prevDistance = distance
                return
            }

            if (shotsFired < maxArrows && left > reserve) {
                val away = EntityUtils.entityFacingAway(p, opp)
                if ((away && distance in 3.5f..30f) ||
                    (!away && distance in 28.0f..33.0f)) {

                    val tunedD = adjustedAimDistance(distance)
                    val lock = chargeMsFor(distance, opening = false)
                    bowHardLockUntil = now + lock
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (lock + 120)
                    projectileKind = KIND_BOW
                    useBow(tunedD) { shotsFired++ }
                    projectileGraceUntil = bowHardLockUntil + 120
                    prevDistance = distance
                    return
                }
            }
        }
        // -------------------------------------------------------

        // ---------------------- STRAFE / HANDLE ----------------
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        val parryActive = Mouse.rClickDown && (now < holdBlockUntil || (parryFromBow && now < parryExtendedUntil))
        if (parryActive) {
            // renforce latéral pendant parade (cohérent avec les sauts)
            val w = if (distance > 6f) 7 else 9
            if (parryStrafeDir < 0) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        }

        if (EntityUtils.entityFacingAway(p, opp)) {
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
        } else if (!parryActive) {
            val rotations = EntityUtils.getRotations(opp, p, false)
            if (rotations != null && now - lastStrafeSwitch > 320) {
                val preferSide = if (rotations[0] < 0) +1 else -1
                if (preferSide != strafeDir) {
                    strafeDir = preferSide
                    lastStrafeSwitch = now
                }
            }
            if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(820, 1100)) {
                strafeDir = -strafeDir
                lastStrafeSwitch = now
            }
            val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
            if (distance in 1.8f..3.6f && deltaDist < 0.03f && now - lastStrafeSwitch > 260) {
                strafeDir = -strafeDir
                lastStrafeSwitch = now
            }
            val weight = if (distance < 4f) 7 else 5
            if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
            randomStrafe = (distance in 8.0f..15.0f)
        }

        handle(clear, randomStrafe, movePriority)
        prevDistance = distance
    }
}
