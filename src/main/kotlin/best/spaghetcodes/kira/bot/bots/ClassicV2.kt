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

    // =====================  ARC  =====================
    private val fullDrawMsMin = 820
    private val fullDrawMsMax = 980
    private val bowCancelCloseDist = 4.8f
    private val bowMinUseDist = 9.0f            // ne pas initier un tir < 9 blocs

    // Ouverture contrôlée (1–2 flèches max, espacées)
    private var openVolleyMax = 1
    private var openVolleyFired = 0
    private var openWindowUntil = 0L
    private var openStartDelayUntil = 0L
    private var lastShotAt = 0L
    private val openSpacingMin = 650L
    private val openSpacingMax = 900L
    private val openShotMinDist = 9.0f

    // Détection immobile / slow-bow
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

    // Interdiction de rod juste après un tir d’arc
    private var postBowNoRodUntil = 0L

    // =====================  ROD  =====================
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
    private val rodMaxRangeHard = 7.2f // garde-fou dur

    // Heuristique hit/miss via hurtTime
    private var lastRodAttemptAt = 0L
    private var lastOppHurtTime = 0
    private var pendingRodCheck = false
    private var rodHits = 0
    private var rodMisses = 0

    // Fenêtres “melee focus” & stick avant
    private var meleeFocusUntil = 0L
    private var forwardStickUntil = 0L

    // Opponent rod usage
    private var lastOppRodSeenAt = 0L

    // Maintien minimal de la rod APRÈS cast (évite le switch épée prématuré)
    private var rodHoldUntil = 0L

    // Priming unique du premier cast rod (micro-retry, pas de délai arbitraire)
    private var firstRodPrimePending = true

    // ==================  PARADE ÉPÉE  =================
    // Interdite si distance < 15 blocs
    private val parryMinDist = 15.0f
    private val parryCloseCancelDist = 15.0f
    private val parryCooldownMs = 900L
    private val parryHoldMinMs = 650
    private val parryHoldMaxMs = 980
    private val parryStickMinMs = 900
    private val parryStickMaxMs = 1500

    private var lastSwordBlock = 0L
    private var holdBlockUntil = 0L
    private var parryFromBow = false
    private var parryExtendedUntil = 0L
    private var parryCloseLockUntil = 0L

    // Strafe & saut pendant parade
    private var parryStrafeDir = 1
    private var parryStrafeFlipAt = 0L
    private var lastParryJumpAt = 0L
    private val parryJumpCd = 580L

    // Anti-parade précoce
    private var allowParryAfter = 0L

    // ==================  MOUVEMENT  ===================
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f
    private var lastTacticalJumpAt = 0L
    private var lastGotHitAt = 0L
    private var tapping = false

    // Biais strafe court (post parade proche -> rod)
    private var strafeBiasDir = 0
    private var strafeBiasStickUntil = 0L

    // Sauts de démarrage & swings humains (série)
    private var startupJumping = true
    private var humanSwingSeriesDone = false
    private var humanSwingSeriesActiveUntil = 0L
    private var wasInHumanZone = false
    private val humanSwingZoneMin = 14.0f
    private val humanSwingZoneMax = 20.0f

    // ====================  LIFECYCLE  ==================
    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Mouse.rClickUp()

        startupJumping = true

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
        postBowNoRodUntil = 0L

        lastRodUse = 0L
        rodCdBias = 1.0f
        rodHits = 0
        rodMisses = 0
        pendingRodCheck = false
        lastRodAttemptAt = 0L
        lastOppRodSeenAt = 0L
        rodHoldUntil = 0L
        firstRodPrimePending = true

        lastTacticalJumpAt = 0L
        lastGotHitAt = 0L

        lastSwordBlock = 0L
        holdBlockUntil = 0L
        parryFromBow = false
        parryExtendedUntil = 0L
        parryCloseLockUntil = 0L
        parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        parryStrafeFlipAt = 0L
        lastParryJumpAt = 0L

        forwardStickUntil = 0L
        meleeFocusUntil = 0L
        strafeBiasDir = 0
        strafeBiasStickUntil = 0L

        humanSwingSeriesDone = false
        humanSwingSeriesActiveUntil = 0L
        wasInHumanZone = false

        // Anti-parade prématurée (~2.8 s)
        allowParryAfter = gameStartAt + 2800L
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
        startupJumping = false
    }

    override fun onAttack() {
        // W-tap court + maintien avant pour ne pas “strafe-only”
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

    // =====================  HELPERS  ==================
    // Mid-range un peu plus bas (15–25)
    private fun adjustedAimDistance(d: Float): Float = when {
        d in 15.0f..22.0f -> d * 0.84f
        d in 22.0f..30.0f -> d * 0.83f
        d in 9.0f..15.0f  -> d * 0.90f
        else              -> d
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

    // Série de swings “humains” (2–4 coups) — une seule fois
    private fun triggerHumanSwingSeries() {
        if (humanSwingSeriesDone) return
        humanSwingSeriesDone = true
        val swings = RandomUtils.randomIntInRange(2, 4)
        var delay = 0
        repeat(swings) {
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                mc.thePlayer?.swingItem()
            }, delay)
            delay += RandomUtils.randomIntInRange(110, 170)
        }
        humanSwingSeriesActiveUntil = System.currentTimeMillis() + delay + 60
    }

    // Rod cast fiable — micro-retry *uniquement* au tout premier cast
    private fun castRodNow(distanceNow: Float) {
        fun doClick() {
            val nowClick = System.currentTimeMillis()

            Mouse.setUsingProjectile(true)
            Mouse.rClick(RandomUtils.randomIntInRange(70, 95))

            val holdMs = when {
                distanceNow < 3.0f -> 120
                distanceNow < 5.2f -> RandomUtils.randomIntInRange(180, 220)
                else               -> RandomUtils.randomIntInRange(200, 240)
            }
            rodHoldUntil = nowClick + holdMs

            val settle = RandomUtils.randomIntInRange(220, 300)
            pendingProjectileUntil = nowClick + 90L
            actionLockUntil = nowClick + settle + 90
            projectileGraceUntil = actionLockUntil

            lastRodAttemptAt = nowClick
            pendingRodCheck = true
            lastOppHurtTime = opponent()?.hurtTime ?: 0

            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                Mouse.setUsingProjectile(false)
            }, max(holdMs + 30, settle))

            lastRodUse = nowClick
        }

        // Si un tir d’arc est en cours, on annule proprement
        if (Mouse.rClickDown && projectileKind == KIND_BOW) {
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        // Sélectionner la rod
        Inventory.setInvItem("rod")

        if (firstRodPrimePending) {
            firstRodPrimePending = false
            var tries = 0
            fun ready(): Boolean {
                val held = mc.thePlayer?.heldItem?.unlocalizedName?.lowercase()
                return held != null && held.contains("rod") && mc.rightClickDelayTimer == 0
            }
            fun tryClick() {
                if (ready() || tries >= 3) {
                    doClick()
                } else {
                    tries++
                    // recheck au tick suivant, sans bloquer
                    TimeUtils.setTimeout({ tryClick() }, 1)
                }
            }
            tryClick()
        } else {
            doClick()
        }
    }

    private fun updateRodAccuracyHeuristic(now: Long) {
        if (!pendingRodCheck) return
        val opp = opponent() ?: return

        val dt = now - lastRodAttemptAt
        if (dt in 80..420) {
            val ht = opp.hurtTime
            if (ht > 0 && ht != lastOppHurtTime) {
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
        return held != null && held.unlocalizedName.lowercase().contains("rod")
    }

    // ======================  TICK  =====================
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        // Sauts de début : actifs jusqu’au 1er draw d’arc
        if (startupJumping) {
            if (!Mouse.rClickDown) Movement.startJumping() else Movement.stopJumping()
        } else {
            Movement.stopJumping()
        }

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        // “swings humains” (2–4) quand entrée 14–20 blocs — une seule série
        val inHumanZone = distance in humanSwingZoneMin..humanSwingZoneMax
        if (!humanSwingSeriesDone && inHumanZone && !wasInHumanZone && now >= humanSwingSeriesActiveUntil) {
            triggerHumanSwingSeries()
        }
        wasInHumanZone = inHumanZone

        // suivi rod adverse
        if (opponentLikelyUsingRod(opp)) lastOppRodSeenAt = now

        // immobile/slow + vitesse totale
        if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
        val isStrafing = frameSpeed > 0.18
        oppLastX = opp.posX; oppLastZ = opp.posZ

        if (p.hurtTime > 0) lastGotHitAt = now

        // Anti-bloc devant
        if (distance > 2.2f) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
                lastTacticalJumpAt = now
            }
        }

        // KB/lag : colle l’avance
        if (prevDistance > 0f && distance - prevDistance > 0.6f) {
            forwardStickUntil = max(forwardStickUntil, now + 200)
        }

        val projectileActive =
            Mouse.isUsingProjectile() || now < projectileGraceUntil || now < pendingProjectileUntil || now < actionLockUntil

        // Avance / arrêt (avec stick avant)
        if (now < forwardStickUntil) {
            Movement.startForward()
        } else {
            if (distance < 0.75f || (distance < 2.4f && combo >= 2 && approaching)) {
                Movement.stopForward()
            } else {
                Movement.startForward()
            }
        }

        // Tenir l'épée si très proche — mais pas durant maintien rod/action lock
        if (distance < 1.5f &&
            p.heldItem != null &&
            !p.heldItem.unlocalizedName.lowercase().contains("sword") &&
            !projectileActive &&
            now >= rodHoldUntil) {
            Inventory.setInvItem("sword")
        }

        // Annuler l’arc si trop proche
        if (projectileActive && Mouse.rClickDown && projectileKind == KIND_BOW && distance < bowCancelCloseDist) {
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        // MAJ heuristique rod
        updateRodAccuracyHeuristic(now)

        // =================  PARADE ÉPÉE (long range)  ================
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")
        val isStill = stillFrames >= stillFramesNeeded
        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowLikely = oppHasBow && (isStill || bowSlowFrames >= bowSlowFramesNeeded)

        // Close cancel strict (< 15) + verrou court
        if (Mouse.rClickDown && distance < parryCloseCancelDist) {
            Mouse.rClickUp()
            parryFromBow = false
            parryExtendedUntil = 0L
            parryCloseLockUntil = now + 700L
        }

        if (holdingSword) {
            if (!Mouse.rClickDown) {
                val closeRange = distance < parryCloseCancelDist

                if (!closeRange &&
                    !startupJumping &&
                    now >= allowParryAfter &&
                    bowLikely &&
                    !projectileActive &&
                    WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                    now >= parryCloseLockUntil &&
                    (now - lastSwordBlock) > parryCooldownMs) {

                    val dur = RandomUtils.randomIntInRange(parryHoldMinMs, parryHoldMaxMs)
                    holdBlockUntil = now + dur
                    lastSwordBlock = now
                    parryFromBow = true

                    var extraStick =
                        if (distance > 15f) RandomUtils.randomIntInRange(900, 1200)
                        else RandomUtils.randomIntInRange(500, 800)

                    parryExtendedUntil = now + RandomUtils.randomIntInRange(parryStickMinMs, parryStickMaxMs) + extraStick
                    parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                    parryStrafeFlipAt = now + RandomUtils.randomIntInRange(260, 420)
                    Mouse.rClick(dur)
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

        // ========== JUMPS CONTEXTUELS (hors parade/proj) ==========
        if (!Mouse.rClickDown && !projectileActive && (now - lastGotHitAt) > 260 && !startupJumping) {
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

        // =======================  ROD  ===========================
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {

            // 1) Garde-fous anti-rod “dans le vide”
            if (distance <= rodMaxRangeHard && now >= postBowNoRodUntil) {
                val cdClose = (rodCdCloseMsBase * rodCdBias).toLong()
                val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
                val cdCloseOK = (now - lastRodUse) >= cdClose
                val cdFarOK = (now - lastRodUse) >= cdFar
                val facingAway = EntityUtils.entityFacingAway(p, opp)

                val oppRodRecently = (now - lastOppRodSeenAt) <= 2500L
                val meleeRange = distance < 3.1f
                val allowRodByMeleePolicy = !(meleeRange && !oppRodRecently && now < meleeFocusUntil)

                // Ultra-proche: casser un combo juste après un hit
                if (allowRodByMeleePolicy &&
                    distance < 2.2f && p.hurtTime > 0 && cdCloseOK && !facingAway) {
                    castRodNow(distance)
                    prevDistance = distance
                    return
                }

                // Fenêtre close
                if (allowRodByMeleePolicy &&
                    distance in rodCloseMin..rodCloseMax &&
                    (p.hurtTime > 0 || approaching) &&
                    !facingAway &&
                    cdCloseOK) {
                    castRodNow(distance)
                    prevDistance = distance
                    return
                }

                // Main / réponse à rod adverse
                if (allowRodByMeleePolicy && !facingAway && cdFarOK) {
                    if (oppRodRecently) {
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

                // Interception d’approche
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
        }

        // ========================  ARC  ==========================
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            val reserve = reserveNeeded(now)
            val left = arrowsLeft()

            // Ouverture (1–2 flèches max, espacées) — ET seulement si assez loin
            if (shotsFired < maxArrows &&
                openVolleyFired < openVolleyMax &&
                now < openWindowUntil &&
                now >= openStartDelayUntil &&
                distance >= max(openShotMinDist, bowMinUseDist) &&
                left > reserve &&
                (now - lastShotAt) >= RandomUtils.randomIntInRange(openSpacingMin.toInt(), openSpacingMax.toInt())) {

                val tunedD = adjustedAimDistance(distance)
                val lock = chargeMsFor(distance, opening = true)
                startupJumping = false // stop sauts de début au 1er draw
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
                postBowNoRodUntil = now + lock + 380L
                prevDistance = distance
                return
            }

            // Réactif si l’ennemi slow-bow/immobile — et seulement assez loin
            val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
            val bowLikelyNow = oppHasBow && (stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)
            if (shotsFired < maxArrows &&
                bowLikelyNow &&
                distance >= bowMinUseDist &&
                now - lastReactiveShotAt >= reactiveCdMs &&
                WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                left > reserve) {

                val tunedD = adjustedAimDistance(distance)
                val lock = chargeMsFor(distance, opening = false)
                startupJumping = false
                bowHardLockUntil = now + lock
                pendingProjectileUntil = now + 50L
                actionLockUntil = now + (lock + 100)
                projectileKind = KIND_BOW
                useBow(tunedD) {
                    shotsFired++
                    lastReactiveShotAt = System.currentTimeMillis()
                }
                projectileGraceUntil = bowHardLockUntil + 100
                postBowNoRodUntil = now + lock + 320L
                prevDistance = distance
                return
            }

            // Opportuniste (dos / très loin)
            if (shotsFired < maxArrows && left > reserve && distance >= bowMinUseDist) {
                val away = EntityUtils.entityFacingAway(p, opp)
                if ((away && distance in 3.5f..30f) ||
                    (!away && distance in 28.0f..33.0f)) {

                    val tunedD = adjustedAimDistance(distance)
                    val lock = chargeMsFor(distance, opening = false)
                    startupJumping = false
                    bowHardLockUntil = now + lock
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (lock + 120)
                    projectileKind = KIND_BOW
                    useBow(tunedD) { shotsFired++ }
                    projectileGraceUntil = bowHardLockUntil + 120
                    postBowNoRodUntil = now + lock + 320L
                    prevDistance = distance
                    return
                }
            }
        }

        // ==================  STRAFE / HANDLE  ===================
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

            // Micro-strafe agressif en très proche (<2.6)
            if (distance < 2.6f) {
                if (now - lastStrafeSwitch > RandomUtils.randomIntInRange(110, 170)) {
                    strafeDir = -strafeDir
                    lastStrafeSwitch = now
                }
                val weightClose = 4
                if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                Movement.startForward()
                Movement.startSprinting()
                randomStrafe = false
            } else {
                // Medium/long range
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
        }

        handle(clear, randomStrafe, movePriority)
        prevDistance = distance
    }
}
