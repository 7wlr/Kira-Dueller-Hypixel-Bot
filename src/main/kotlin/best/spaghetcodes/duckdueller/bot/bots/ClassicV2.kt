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
import kotlin.math.max

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

    // ---------- Tuning général ----------
    private val fullDrawMsMin = 820
    private val fullDrawMsMax = 980
    private val bowCancelCloseDist = 4.8f

    // Ouverture : volley contrôlée (réserves strictes)
    private var openVolleyMax = 1            // 1–2 au start
    private var openVolleyFired = 0
    private var openWindowUntil = 0L
    private var openStartDelayUntil = 0L
    private var lastShotAt = 0L
    private val openSpacingMin = 650L
    private val openSpacingMax = 900L
    private val openShotMinDist = 9.0f

    // Slow-bow / immobilité (tirs réactifs)
    private val stillFrameThreshold = 0.0125
    private val stillFramesNeeded = 10
    private val bowSlowThreshold = 0.06
    private val bowSlowFramesNeeded = 3
    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private var stillFrames = 0
    private var bowSlowFrames = 0

    // Strafe & déplacement
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f

    // Projectiles (arc)
    private var shotsFired = 0
    private val maxArrows = 5
    private var bowHardLockUntil = 0L
    private var projectileGraceUntil = 0L
    private var pendingProjectileUntil = 0L
    private var actionLockUntil = 0L
    private var projectileKind = 0
    private val KIND_NONE = 0
    private val KIND_BOW = 2

    // Tirs réactifs (anti-spam)
    private var lastReactiveShotAt = 0L
    private val reactiveCdMs = 650L

    // Réserves d’arrows
    private var gameStartAt = 0L
    private val reserveTightMs = 10_000L
    private val earlyReserve = 3
    private val midReserve = 2

    // ---------- Rod (améliorée vs joueurs agressifs) ----------
    private var lastRodUse = 0L
    private var rodLockUntil = 0L
    private var forceKeepRodUntil = 0L

    private val rodCloseMin = 2.2f
    private val rodCloseMax = 3.8f
    private val rodWindowMin = 3.0f
    private val rodWindowMax = 5.6f
    private val rodInterceptMin = 5.7f
    private val rodInterceptMax = 6.5f

    private val rodCdCloseMs = 520L
    private val rodCdFarMs = 850L
    // ----------------------------------------------------------

    private var tapping = false

    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.startJumping() // saute dès le départ

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

        // Volley d’ouverture contrôlée
        openVolleyMax = RandomUtils.randomIntInRange(1, 2)
        openVolleyFired = 0
        openWindowUntil = System.currentTimeMillis() + 4500L
        openStartDelayUntil = System.currentTimeMillis() + RandomUtils.randomIntInRange(700, 1100)
        lastShotAt = 0L

        // Slow-bow reset
        oppLastX = 0.0
        oppLastZ = 0.0
        stillFrames = 0
        bowSlowFrames = 0

        lastReactiveShotAt = 0L
        gameStartAt = System.currentTimeMillis()

        // Rod reset
        lastRodUse = 0L
        rodLockUntil = 0L
        forceKeepRodUntil = 0L
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
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 100)
        if (combo >= 3) Movement.clearLeftRight()
    }

    // ————— Visée : baisse à mi-distance (15–25) —————
    private fun adjustedAimDistance(d: Float): Float {
        return when {
            d in 15.0f..20.0f -> d * 0.90f
            d in 20.0f..25.0f -> d * 0.86f
            d in 9.0f..15.0f  -> d * 0.92f
            else              -> d
        }
    }

    // ————— Charge adaptative —————
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

    // ————— Rod confirmée, sans latence après switch + backup-click au contact —————
    private fun castRodConfirmed(distanceNow: Float) {
        val now = System.currentTimeMillis()

        // on stoppe toute charge d'arc trop proche
        if (Mouse.rClickDown && projectileKind == KIND_BOW) {
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        val clickMs = RandomUtils.randomIntInRange(80, 110)
        val settleAfter = RandomUtils.randomIntInRange(260, 360)
        val beingComboedClose = (mc.thePlayer != null && mc.thePlayer.hurtTime > 0 && distanceNow < 3.0f)
        val rodAlreadyHeld = mc.thePlayer?.heldItem?.unlocalizedName?.lowercase()?.contains("rod") == true

        Inventory.setInvItem("rod")

        // clic principal (immédiat si déjà tenue, sinon 1 tick max)
        val delay = if (rodAlreadyHeld || beingComboedClose) 0 else 50
        if (delay == 0) {
            Mouse.rClick(clickMs)
        } else {
            actionLockUntil += 50
            projectileGraceUntil += 50
            TimeUtils.setTimeout({ Mouse.rClick(clickMs) }, 50)
        }

        // backup-click si très proche / KB vertical
        if (beingComboedClose || distanceNow < 2.9f) {
            val backupDelay = delay + clickMs + 20
            TimeUtils.setTimeout({
                val held = mc.thePlayer?.heldItem
                if (held != null &&
                    held.unlocalizedName.lowercase().contains("rod") &&
                    !Mouse.rClickDown) {
                    Mouse.rClick(RandomUtils.randomIntInRange(55, 75))
                }
            }, backupDelay)
        }

        // protège la rod contre un reswitch instant
        forceKeepRodUntil = now + delay + clickMs + 120L

        // verrou d’action court, puis retour épée
        pendingProjectileUntil = now + 100L
        actionLockUntil = now + delay + clickMs + settleAfter + 100
        projectileGraceUntil = actionLockUntil

        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            Mouse.setUsingProjectile(false)
        }, delay + clickMs + settleAfter)

        lastRodUse = now
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        val distance = EntityUtils.getDistanceNoY(p, opp)
        val now = System.currentTimeMillis()
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        // Anti-bloc devant
        if (distance > 2.2f) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
            }
        }

        // Avance / arrêt
        if (distance < 1f || (distance < 2.7f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // Switch épée si trop proche (sauf si on protège la rod)
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")
        if (distance < 1.5f &&
            (p.heldItem == null || !holdingSword) &&
            now >= forceKeepRodUntil) {
            Inventory.setInvItem("sword")
        }

        // État projectile actif
        val projectileActive =
            Mouse.isUsingProjectile() || now < projectileGraceUntil || now < pendingProjectileUntil || now < actionLockUntil

        // NE JAMAIS SAUTER pendant un tir
        if (projectileActive || Mouse.rClickDown) Movement.stopJumping() else Movement.startJumping()

        // Annuler l’ARC si trop proche pendant la charge
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

        // ---------------- ROD — priorités “anti-nerd” ----------------
        if (!Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            // cooldowns
            val cdOKClose = (now - lastRodUse) >= rodCdCloseMs
            val cdOKFar = (now - lastRodUse) >= rodCdFarMs

            // (0) URGENCE : break-combo au contact — PREEMPT l’arc si nécessaire
            if (!projectileActive &&
                distance in rodCloseMin..rodCloseMax &&
                (p.hurtTime > 0 || approaching) &&
                !EntityUtils.entityFacingAway(p, opp) &&
                cdOKClose &&
                now >= rodLockUntil) {

                rodLockUntil = now + RandomUtils.randomIntInRange(240, 320)
                castRodConfirmed(distance)
                return
            }

            // (A) Interception quand l’ennemi pousse (3.0–5.6)
            if (!projectileActive &&
                distance in rodWindowMin..rodWindowMax &&
                approaching &&
                !EntityUtils.entityFacingAway(p, opp) &&
                cdOKFar &&
                now >= rodLockUntil) {

                rodLockUntil = now + RandomUtils.randomIntInRange(300, 380)
                castRodConfirmed(distance)
                return
            }

            // (B) Fenêtre 5.7–6.5 si l’ennemi avance
            if (!projectileActive &&
                distance in rodInterceptMin..rodInterceptMax &&
                approaching &&
                !EntityUtils.entityFacingAway(p, opp) &&
                cdOKFar &&
                now >= rodLockUntil) {

                rodLockUntil = now + RandomUtils.randomIntInRange(340, 420)
                castRodConfirmed(distance)
                return
            }

            // (C) V2 “classique” : 3.2–6.0 propre (si rien d’autre ne s’applique)
            if (!projectileActive &&
                distance in 3.2f..6.0f &&
                !EntityUtils.entityFacingAway(p, opp) &&
                cdOKFar &&
                now >= rodLockUntil) {

                rodLockUntil = now + RandomUtils.randomIntInRange(300, 380)
                castRodConfirmed(distance)
                return
            }
        }
        // -----------------------------------------------------------------

        // ---------- ARC ----------
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {

            val reserve = reserveNeeded(now)
            val left = arrowsLeft()

            // 1) Ouverture : 1–2 max, réserves strictes
            if (shotsFired < maxArrows &&
                openVolleyFired < openVolleyMax &&
                now < openWindowUntil &&
                now >= openStartDelayUntil &&
                distance >= openShotMinDist &&
                left > reserve) {

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
                return
            }

            // 2) Réactif sur slow-bow/immobile — respecte la réserve
            if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
            val dx = abs(opp.posX - oppLastX)
            val dz = abs(opp.posZ - oppLastZ)
            if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
            val frameSpeed = dx + dz
            if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
            oppLastX = opp.posX; oppLastZ = opp.posZ

            val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
            val bowLikely = oppHasBow && (stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)

            if (shotsFired < maxArrows &&
                bowLikely &&
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
                return
            }

            // 3) Safe later
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
                    return
                }
            }
        }
        // ------------------------

        // Mouvement / strafe
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        if (EntityUtils.entityFacingAway(p, opp)) {
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
        } else {
            val rotations = EntityUtils.getRotations(opp, p, false)
            if (rotations != null && now - lastStrafeSwitch > 320) {
                val preferSide = if (rotations[0] < 0) +1 else -1
                if (preferSide != strafeDir) {
                    strafeDir = preferSide
                    lastStrafeSwitch = now
                }
            }

            // flips plus fréquents quand on “colle”, pour casser les lignes des nerds
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
