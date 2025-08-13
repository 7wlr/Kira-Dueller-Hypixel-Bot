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
import best.spaghetcodes.duckdueller.utils.ChatUtils
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

    // ---------- Tuning / États ----------
    private val fullDrawMsMin = 820
    private val fullDrawMsMax = 980
    private val openShotMinDist = 9.0f
    private val bowCancelCloseDist = 4.8f

    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f
    private var tapping = false

    // Arcs
    private var shotsFired = 0
    private val maxArrows = 5

    // Ouverture : 2/3 flèches max
    private var openVolleyMax = 2
    private var openVolleyFired = 0
    private var openWindowUntil = 0L

    // verrous “projectiles”
    private var bowHardLockUntil = 0L
    private var projectileGraceUntil = 0L
    private var pendingProjectileUntil = 0L
    private var actionLockUntil = 0L
    private var projectileKind = 0
    private val KIND_NONE = 0
    private val KIND_BOW = 2

    // --- NEW: détection slow-bow pour parade simple ---
    private val stillFrameThreshold = 0.0125
    private val stillFramesNeeded = 10
    private val bowSlowThreshold = 0.06
    private val bowSlowFramesNeeded = 3
    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private var stillFrames = 0
    private var bowSlowFrames = 0

    private val parryMinDist = 6.0f
    private val parryHoldMinMs = 520
    private val parryHoldMaxMs = 820
    private val parryCooldownMs = 800L
    private var holdBlockUntil = 0L
    private var lastSwordBlock = 0L
    // ---------------------------------------------------

    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.startJumping()

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

        openVolleyMax = RandomUtils.randomIntInRange(2, 3)
        openVolleyFired = 0
        openWindowUntil = System.currentTimeMillis() + 6000L

        // reset parade
        oppLastX = 0.0
        oppLastZ = 0.0
        stillFrames = 0
        bowSlowFrames = 0
        holdBlockUntil = 0L
        lastSwordBlock = 0L
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
        ChatUtils.info("W-Tap 100")
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 100)
        if (combo >= 3) Movement.clearLeftRight()
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        val distance = EntityUtils.getDistanceNoY(p, opp)
        val now = System.currentTimeMillis()

        // Petits sauts de rattrapage
        if (distance > 2.2f) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
            }
        }
        if (distance > 5.0f) Movement.startJumping() else Movement.stopJumping()

        // Avance / arrêt
        if (distance < 1f || (distance < 2.7f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // Switch épée si proche
        if (distance < 1.5f && p.heldItem != null && !p.heldItem.unlocalizedName.lowercase().contains("sword")) {
            Inventory.setInvItem("sword")
        }

        // Annuler l’ARC si trop proche pendant la charge
        val projectileActive =
            Mouse.isUsingProjectile() || now < projectileGraceUntil || now < pendingProjectileUntil || now < actionLockUntil
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

        // ---------- LOGIQUE BOW V2 ----------
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            // Tir d’ouverture : 2/3 flèches max, uniquement à longue distance
            if (shotsFired < maxArrows &&
                openVolleyFired < openVolleyMax &&
                now < openWindowUntil &&
                distance >= openShotMinDist) {

                bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                pendingProjectileUntil = now + 60L
                actionLockUntil = now + (fullDrawMsMax + 120)
                projectileKind = KIND_BOW

                useBow(distance) {
                    shotsFired++
                    openVolleyFired++
                }
                projectileGraceUntil = bowHardLockUntil + 120
                return
            }

            // Usage tardif : fenêtres safe
            if (shotsFired < maxArrows) {
                val away = EntityUtils.entityFacingAway(p, opp)
                if ((away && distance in 3.5f..30f) ||
                    (!away && distance in 28.0f..33.0f)) {
                    bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (fullDrawMsMax + 120)
                    projectileKind = KIND_BOW

                    useBow(distance) { shotsFired++ }
                    projectileGraceUntil = bowHardLockUntil + 120
                    return
                }
            }
        }
        // -----------------------------------

        // --- NEW : détection slow-bow + parade simple (V2 n’en avait pas) ---
        // MAJ de l'immobilité/slow
        if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
        oppLastX = opp.posX; oppLastZ = opp.posZ

        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowDrawLikely = oppHasBow && (stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")

        if (holdingSword) {
            if (Mouse.rClickDown) {
                // relâcher si la fenêtre est finie ou si l’adversaire repart franchement
                val movingHard = !(stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)
                if (movingHard || now >= holdBlockUntil) {
                    Mouse.rClickUp()
                }
            } else {
                val canStartParry =
                    distance >= parryMinDist &&
                    distance <= 14.5f &&
                    bowDrawLikely &&
                    !projectileActive &&
                    WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                    (now - lastSwordBlock) > parryCooldownMs

                if (canStartParry) {
                    val dur = RandomUtils.randomIntInRange(parryHoldMinMs, parryHoldMaxMs)
                    holdBlockUntil = now + dur
                    lastSwordBlock = now
                    Mouse.rClick(dur)
                }
            }
        } else if (Mouse.rClickDown && !projectileActive) {
            Mouse.rClickUp()
        }
        // --------------------------------------------------------------------

        // Rod simple à moyenne distance
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            if (distance in 3.2f..6.0f && !EntityUtils.entityFacingAway(p, opp)) {
                useRod()
                return
            }
        }

        // Mouvement strafe (V2 “light”)
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

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

            if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(950, 1200)) {
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
