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
    private val openShotMinDist = 9.0f     // tirer à l’ouverture seulement si assez loin
    private val bowCancelCloseDist = 4.8f

    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f
    private var tapping = false

    // Arcs
    private var shotsFired = 0
    private val maxArrows = 5

    // Ouverture : on tire 2 ou 3 flèches max
    private var openVolleyMax = 2
    private var openVolleyFired = 0
    private var openWindowUntil = 0L

    // verrous “projectiles” (mêmes principes que Classic)
    private var bowHardLockUntil = 0L
    private var projectileGraceUntil = 0L
    private var pendingProjectileUntil = 0L
    private var actionLockUntil = 0L
    private var projectileKind = 0
    private val KIND_NONE = 0
    private val KIND_BOW = 2

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

        openVolleyMax = RandomUtils.randomIntInRange(2, 3)   // 2 ou 3 flèches au start
        openVolleyFired = 0
        openWindowUntil = System.currentTimeMillis() + 6000L // fenêtre de 6s pour l’ouverture
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

        // Petits sauts de rattrapage (comme avant)
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

                // Full draw + locks pour éviter le reswitch prématuré
                bowHardLockUntil = now + RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
                pendingProjectileUntil = now + 60L
                actionLockUntil = now + (fullDrawMsMax + 120)
                projectileKind = KIND_BOW

                useBow(distance) {
                    shotsFired++
                    openVolleyFired++
                }
                projectileGraceUntil = bowHardLockUntil + 120
                // on laisse la main, pas d'autre action ce tick
                return
            }

            // Usage “tardif” : garder le reste pour des fenêtres utiles
            // Conditions safe : ennemi de dos ET distance 3.5..30, OU très loin 28..33
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

        // Rod simple à moyenne distance (garde le style V2 “light”)
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            if (distance in 3.2f..6.0f && !EntityUtils.entityFacingAway(p, opp)) {
                useRod()
                return
            }
        }

        // Mouvement strafe (identique à la V2 d’origine)
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
