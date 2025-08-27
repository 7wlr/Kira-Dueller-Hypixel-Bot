package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.Bow
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.features.Rod
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    // Distance minimum “safe” pour sortir l’arc (empêche le swap arc/épée à bout portant)
    private val bowMinSafeDist = 8.6f

    // Si on est en train de bander et que l’ennemi s’approche sous ce seuil => on annule
    private val bowCancelCloseDist = 8.6f

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
    private var rodCdCloseMsBase = 340L
    private var rodCdFarMsBase = 480L
    private var rodCdBias = 1.0f // >1 = plus long, <1 = plus court

    private val rodCloseMin = 2.0f
    private val rodCloseMax = 3.4f
    private val rodMainMin = 3.0f
    private val rodMainMax = 6.8f
    private val rodInterceptMin = 5.8f
    private val rodInterceptMax = 7.2f

    // Évaluation “hit/miss” via hurtTime
    private var lastRodAttemptAt = 0L
    private var lastOppHurtTime = 0
    private var pendingRodCheck = false
    private var rodHits = 0
    private var rodMisses = 0

    // Fenêtre “melee focus”
    private var meleeFocusUntil = 0L
    private var forwardStickUntil = 0L

    // Opponent rod usage
    private var lastOppRodSeenAt = 0L

    // -------------------- PARADE ÉPÉE -----------------
    private val parryMinDist = 6.5f
    private val parryCloseCancelDist = 6.0f
    private val parryCooldownMs = 900L
    private val parryHoldMinMs = 650
    private val parryHoldMaxMs = 980
    private val parryStickMinMs = 900
    private val parryStickMaxMs = 1500

    private var lastSwordBlock = 0L
    private var holdBlockUntil = 0L
    private var parryFromBow = false
    private var parryExtendedUntil = 0L

    // Strafe & saut pendant parade
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

    // Biais strafe court (post “parade proche -> rod”)
    private var strafeBiasDir = 0
    private var strafeBiasStickUntil = 0L

    // ---------------------- LIFECYCLE -----------------
    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping()
        Mouse.rClickUp()

        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1

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
        lastOppHurtTime = 0

        lastReactiveShotAt = 0L
        gameStartAt = System.currentTimeMillis()

        lastRodUse = 0L
        rodCdBias = 1.0f
        rodHits = 0
        rodMisses = 0
        pendingRodCheck = false
        lastRodAttemptAt = 0L
        lastOppRodSeenAt = 0L

        lastTacticalJumpAt = 0L
        lastGotHitAt = 0L

        lastSwordBlock = 0L
        holdBlockUntil = 0L
        parryFromBow = false
        parryExtendedUntil = 0L
        parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        parryStrafeFlipAt = 0L
        lastParryJumpAt = 0L

        forwardStickUntil = 0L
        meleeFocusUntil = 0L
        strafeBiasDir = 0
        strafeBiasStickUntil = 0L
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
        // W-tap puis reprise agressive + stick avant
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 100)

        val now = System.currentTimeMillis()
        forwardStickUntil = now + RandomUtils.randomIntInRange(220, 280)
        meleeFocusUntil = now + RandomUtils.randomIntInRange(300, 340)
        TimeUtils.setTimeout({
            Movement.startForward()
            Movement.startSprinting()
        }, 80)

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

        // Si arc en cours, on annule
        if (Mouse.rClickDown && projectileKind == KIND_BOW) {
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        if (distanceNow < 3.0f) {
            // Très proche : zéro latence obligatoire
            useRodImmediate()
        } else {
            useRod()
        }

        // Marqueurs communs
        lastRodAttemptAt = now
        pendingRodCheck = true
        lastOppHurtTime = opponent()?.hurtTime ?: 0
        lastRodUse = now
    }

    private fun updateRodAccuracyHeuristic(now: Long) {
        if (!pendingRodCheck) return
        val opp = opponent() ?: return

        val dt = now - lastRodAttemptAt
        if (dt in 80..420) {
            val ht = opp.hurtTime
            if (ht > 0 && (ht != lastOppHurtTime)) {
                rodHits++
                pendingRodCheck = false
                rodCdBias = max(0.85f, rodCdBias * 0.95f)
            }
        } else if (dt > 480) {
            rodMisses++
            pendingRodCheck = false
            if (rodMisses - rodHits >= 2) {
                rodCdBias = min(1.6f, rodCdBias * 1.12f)
            }
        }
    }

    private fun opponentLikelyUsingRod(opp: net.minecraft.entity.EntityLivingBase): Boolean {
        val held = opp.heldItem
        if (held != null && held.unlocalizedName.lowercase().contains("rod")) return true
        return false
    }

    // ----------------------- TICK ---------------------
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()
        Movement.stopJumping()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        // ---- Faux coups d’épée “humain” au tout début (15..20 blocs)
        if (now - gameStartAt <= 2000L &&
            distance in 15.0f..20.0f &&
            !Mouse.isUsingProjectile() &&
            !Mouse.rClickDown) {
            if (p.heldItem == null || !p.heldItem.unlocalizedName.lowercase().contains("sword")) {
                Inventory.setInvItem("sword")
            }
            // 4–6 swings espacés aléatoirement ~300–450 ms
            if ((now / 320L) % 2L == 0L) {
                Mouse.leftClick()
            }
        }

        // suivi rod adverse
        if (opponentLikelyUsingRod(opp)) {
            lastOppRodSeenAt = now
        }

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

        // Si la distance a soudainement augmenté (lag/KB), colle l’avancée
        if (prevDistance > 0f && distance - prevDistance > 0.6f) {
            forwardStickUntil = max(forwardStickUntil, now + 200)
        }

        // >>> Avance / arrêt, avec “forward stick”
        if (now < forwardStickUntil) {
            Movement.startForward()
        } else {
            if (distance < 0.75f || (distance < 2.4f && combo >= 2 && approaching)) {
                Movement.stopForward()
            } else {
                Movement.startForward()
            }
        }

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

        // Evaluer la réussite rod récente
        updateRodAccuracyHeuristic(now)

        // --------------------- PARADE ÉPÉE ---------------------
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")
        val isStill = stillFrames >= stillFramesNeeded
        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowLikely = oppHasBow && (isStill || bowSlowFrames >= bowSlowFramesNeeded)

        // Coupe-parade courte portée
        if (Mouse.rClickDown && distance < parryCloseCancelDist) {
            Mouse.rClickUp()
            parryFromBow = false
            parryExtendedUntil = 0L
            // à cette portée : rod d’esquive si possible
            val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
            if (!projectileActive && (now - lastRodUse) >= cdFar && !EntityUtils.entityFacingAway(p, opp) && now > meleeFocusUntil) {
                castRodNow(distance)
                strafeBiasDir = if (RandomUtils.randomIntInRange(0, 1) == 1) +1 else -1
                strafeBiasStickUntil = now + RandomUtils.randomIntInRange(380, 520)
            }
        }

        if (holdingSword) {
            if (!Mouse.rClickDown) {
                val sinceStart = now - gameStartAt
                val closeRange = distance < parryCloseCancelDist

                if (!closeRange) {
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

                        var extraStick =
                            if (distance > 5f) RandomUtils.randomIntInRange(600, 900)
                            else if (distance > 4f) RandomUtils.randomIntInRange(320, 520)
                            else 0
                        if (distance > 12f) extraStick += RandomUtils.randomIntInRange(500, 900)
                        if (distance > 18f) extraStick += RandomUtils.randomIntInRange(250, 350)

                        parryExtendedUntil = now + RandomUtils.randomIntInRange(parryStickMinMs, parryStickMaxMs) + extraStick
                        parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                        parryStrafeFlipAt = now + RandomUtils.randomIntInRange(240, 380)
                        Mouse.rClick(dur)
                    }
                }
            } else {
                // Parade en cours (distance suffisante) : petits sauts latéraux
                if (distance >= parryCloseCancelDist &&
                    p.onGround &&
                    now - lastParryJumpAt >= parryJumpCd &&
                    !projectileActive &&
                    now - lastGotHitAt > 260) {

                    if (RandomUtils.randomIntInRange(0, 1) == 1 || now >= parryStrafeFlipAt) {
                        parryStrafeDir = -parryStrafeDir
                        parryStrafeFlipAt = now + RandomUtils.randomIntInRange(260, 420)
                    }
                    Movement.singleJump(RandomUtils.randomIntInRange(140, 210))
                    lastParryJumpAt = now
                    Movement.startForward()
                    Movement.startSprinting()
                }

                val mustKeep = parryFromBow && now < parryExtendedUntil
                if (!mustKeep && now >= holdBlockUntil) {
                    Mouse.rClickUp()
                    parryFromBow = false
                    parryExtendedUntil = 0L
                }
            }
        } else {
            if (Mouse.rClickDown && !projectileActive) Mouse.rClickUp()
            parryFromBow = false
            parryExtendedUntil = 0L
        }

        // --------- JUMPS CONTEXTUELS hors parade/proj ----------
        if (!Mouse.rClickDown && !projectileActive && (now - lastGotHitAt) > 260) {
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

        // ----------------------- ROD ----------------------------
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            val cdClose = (rodCdCloseMsBase * rodCdBias).toLong()
            val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
            val cdCloseOK = (now - lastRodUse) >= cdClose
            val cdFarOK = (now - lastRodUse) >= cdFar
            val facingAway = EntityUtils.entityFacingAway(p, opp)

            val oppRodRecently = (now - lastOppRodSeenAt) <= 2500L
            val meleeRange = distance < 3.1f
            val allowRodByMeleePolicy = !(meleeRange && !oppRodRecently && now < meleeFocusUntil)

            val isStrafing = (dx + dz) > 0.18 && distance in 4.5f..7.0f

            if (allowRodByMeleePolicy &&
                distance < 2.2f && p.hurtTime > 0 && cdCloseOK && !facingAway) {
                castRodNow(distance)
                prevDistance = distance
                return
            }

            if (allowRodByMeleePolicy &&
                distance in rodCloseMin..rodCloseMax &&
                (p.hurtTime > 0 || approaching) &&
                !facingAway &&
                cdCloseOK) {
                castRodNow(distance)
                prevDistance = distance
                return
            }

            if (allowRodByMeleePolicy && !facingAway && cdFarOK) {
                if (isStrafing || oppRodRecently) {
                    castRodNow(distance)
                    prevDistance = distance
                    return
                }
                if (distance in rodMainMin..rodMainMax) {
                    castRodNow(distance)
                    prevDistance = distance
                    return
                }
            }

            if (allowRodByMeleePolicy &&
                distance in rodInterceptMin..rodInterceptMax &&
                approaching &&
                !facingAway &&
                cdFarOK) {
                castRodNow(distance)
                prevDistance = distance
                return
            }
        }

        // ------------------------ ARC --------------------------
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            val reserve = reserveNeeded(now)
            val left = arrowsLeft()

            // Ouverture (seulement si on est au-dessus du seuil “safe”)
            if (shotsFired < maxArrows &&
                openVolleyFired < openVolleyMax &&
                now < openWindowUntil &&
                now >= openStartDelayUntil &&
                distance >= max(openShotMinDist, bowMinSafeDist) &&
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

            // Réactif “ennemi immobile/lent + bow en main” (mais seulement si distance >= safe)
            val isStillNow = stillFrames >= stillFramesNeeded
            val oppHasBowNow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
            val bowLikelyNow = oppHasBowNow && (isStillNow || bowSlowFrames >= bowSlowFramesNeeded)

            if (shotsFired < maxArrows &&
                bowLikelyNow &&
                distance >= bowMinSafeDist &&
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

            // Opportuniste (jamais en dessous du seuil “safe” si l’ennemi n’est pas de dos)
            if (shotsFired < maxArrows && left > reserve) {
                val away = EntityUtils.entityFacingAway(p, opp)
                val okFarFront = (!away && distance in 28.0f..33.0f)
                val okAway = (away && distance >= max(9.0f, bowMinSafeDist) && distance <= 30.0f)

                if (okAway || okFarFront) {
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

        // ---------------------- STRAFE / HANDLE ----------------
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        val parryActive = Mouse.rClickDown && (now < holdBlockUntil || (parryFromBow && now < parryExtendedUntil))
        if (parryActive) {
            val w = if (distance > 6f) 7 else 9
            if (parryStrafeDir < 0) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        }

        // Biais court post “parade proche -> rod”
        if (!parryActive && now < strafeBiasStickUntil && strafeBiasDir != 0) {
            val w = if (distance > 6f) 6 else 7
            if (strafeBiasDir < 0) movePriority[0] += w else movePriority[1] += w
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
