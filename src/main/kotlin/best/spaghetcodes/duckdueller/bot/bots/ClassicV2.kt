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

    // ---------- Tuning ----------
    private val fullDrawMsMin = 820
    private val fullDrawMsMax = 980
    private val openShotMinDist = 9.0f
    private val bowCancelCloseDist = 4.8f

    // Ouverture : volley contrôlée (pas instant)
    private var openVolleyMax = 2          // 2–3 au start
    private var openVolleyFired = 0
    private var openWindowUntil = 0L
    private var openStartDelayUntil = 0L   // délai avant 1re flèche
    private var lastShotAt = 0L            // cadence ouverture

    private val openSpacingMin = 650L      // espacement entre tirs d’ouverture
    private val openSpacingMax = 900L

    // Parry simple (différent de V1)
    private val stillFrameThreshold = 0.0125
    private val stillFramesNeeded = 10
    private val bowSlowThreshold = 0.06
    private val bowSlowFramesNeeded = 3
    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private var stillFrames = 0
    private var bowSlowFrames = 0

    private val parryMinDist = 6.0f
    private val parryHoldMinMs = 560
    private val parryHoldMaxMs = 880
    private val parryCooldownMs = 800L
    private var holdBlockUntil = 0L
    private var lastSwordBlock = 0L

    // Strafe
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f

    // Projectiles
    private var shotsFired = 0
    private val maxArrows = 5
    private var bowHardLockUntil = 0L
    private var projectileGraceUntil = 0L
    private var pendingProjectileUntil = 0L
    private var actionLockUntil = 0L
    private var projectileKind = 0
    private val KIND_NONE = 0
    private val KIND_BOW = 2

    private var tapping = false
    // ---------------------------

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

        // Volley d’ouverture contrôlée
        openVolleyMax = RandomUtils.randomIntInRange(2, 3)
        openVolleyFired = 0
        openWindowUntil = System.currentTimeMillis() + 6000L
        openStartDelayUntil = System.currentTimeMillis() + RandomUtils.randomIntInRange(700, 1100)
        lastShotAt = 0L

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

        // Anti-bloc devant
        if (distance > 2.2f) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
            }
        }

        // Sauts utiles uniquement (pas pendant charge, pas trop proche)
        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowDrawLikely = run {
            // MAJ immob/slow ci-dessous — on réutilise les compteurs hors-parry
            bowSlowFrames >= bowSlowFramesNeeded || stillFrames >= stillFramesNeeded
        }
        val canJumpByDist =
            (distance > 8.0f) || (distance <= 8.0f && (EntityUtils.entityFacingAway(p, opp) || bowDrawLikely))
        if (!Mouse.rClickDown && canJumpByDist) Movement.startJumping() else Movement.stopJumping()

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

        // ---------- LOGIQUE BOW (contrôlée) ----------
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {

            // 1) Tir d’ouverture : 2/3 max, pas direct au spawn, espacement entre tirs
            if (shotsFired < maxArrows &&
                openVolleyFired < openVolleyMax &&
                now < openWindowUntil &&
                now >= openStartDelayUntil &&
                distance >= openShotMinDist) {

                // hop léger puis tir (jamais pendant la charge)
                val hopDur = RandomUtils.randomIntInRange(140, 200)
                Movement.singleJump(hopDur)

                val spacing = if (lastShotAt == 0L)
                    RandomUtils.randomIntInRange(openSpacingMin.toInt(), openSpacingMax.toInt()).toLong()
                else
                    RandomUtils.randomIntInRange(openSpacingMin.toInt(), openSpacingMax.toInt()).toLong()

                if (now - lastShotAt >= spacing) {
                    val tunedD = if (distance in 9.0f..18.5f) distance * 0.92f else distance
                    val lock = RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                    bowHardLockUntil = now + lock
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (fullDrawMsMax + 120)
                    projectileKind = KIND_BOW

                    // Tir juste après le hop (stabilisation)
                    TimeUtils.setTimeout({
                        useBow(tunedD) {
                            shotsFired++
                            openVolleyFired++
                            lastShotAt = System.currentTimeMillis()
                        }
                    }, hopDur + RandomUtils.randomIntInRange(60, 90))

                    projectileGraceUntil = bowHardLockUntil + 120
                    return
                }
            }

            // 2) Tirs “safe” plus tard dans le duel (garde des flèches)
            if (shotsFired < maxArrows) {
                val away = EntityUtils.entityFacingAway(p, opp)
                if ((away && distance in 3.5f..30f) ||
                    (!away && distance in 28.0f..33.0f)) {
                    val tunedD = if (distance in 9.0f..18.5f) distance * 0.92f else distance
                    bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (fullDrawMsMax + 120)
                    projectileKind = KIND_BOW

                    useBow(tunedD) { shotsFired++ }
                    projectileGraceUntil = bowHardLockUntil + 120
                    return
                }
            }
        }
        // ---------------------------------------------

        // --- Détection slow-bow + parade simple ---
        // MAJ immob/slow
        if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
        oppLastX = opp.posX; oppLastZ = opp.posZ

        val bowLikely = oppHasBow && (stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")

        if (holdingSword) {
            if (Mouse.rClickDown) {
                val movingHard = !(stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)
                if (movingHard || now >= holdBlockUntil) {
                    Mouse.rClickUp()
                }
            } else {
                val canStartParry =
                    distance >= parryMinDist &&
                    distance <= 14.5f &&
                    bowLikely &&
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
        // ------------------------------------------

        // Rod simple (fenêtre claire), on garde l’avantage V2
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            if (distance in 3.2f..6.0f && !EntityUtils.entityFacingAway(p, opp)) {
                useRod()
                return
            }
        }

        // Mouvement strafe (léger)
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
