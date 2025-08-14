package best.spaghetcodes.duckdueller.bot.bots

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.features.MovePriority
import best.spaghetcodes.duckdueller.bot.player.Combat
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.bot.player.Movement
import best.spaghetcodes.duckdueller.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.max

class Sumo : BotBase("/play duels_sumo_duel"), MovePriority {

    override fun getName(): String = "Sumo"

    // ---------- Tuning ----------
    private val engageJumpMin = 5.5f          // saut d'engagement seulement dans cette fenêtre
    private val engageJumpMax = 7.0f
    private val microBackstepCd = 700L        // cooldown des micro reculs anti-combo
    private val microBackstepDur = 140..220   // durée du micro recul
    private val wTapClose = 120..170          // WTap au corps à corps
    private val wTapFar = 200..260            // WTap quand on s'engage
    private val strafeFlipBase = 420..680     // délai de flip du strafe par défaut
    private val edgeProbeNear = 1.6f          // détection du vide proche
    private val edgeProbeFar = 2.6f           // détection du vide un peu plus loin
    private val stopForwardDist = 1.3f        // arrêt d'avance si on est collé
    private val reForwardDist = 2.0f          // reprise d'avance au-delà de cette distance

    // ---------- États ----------
    private var prevDistance = -1f
    private var lastStrafeSwitch = 0L
    private var strafeDir = 1
    private var stagnantSince = 0L
    private var lastBackstep = 0L

    private var centerX = 0.0
    private var centerZ = 0.0

    private var tapping = false

    override fun onGameStart() {
        Mouse.startTracking()
        Mouse.stopLeftAC()                 // on (re)démarre proprement
        Movement.clearAll()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping()

        // point "centre" approximatif = spawn du joueur (suffisant pour orienter le strafe vers l'intérieur)
        val p = mc.thePlayer
        if (p != null) {
            centerX = p.posX
            centerZ = p.posZ
        }

        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        stagnantSince = 0L
        lastBackstep = 0L
        tapping = false
    }

    override fun onGameEnd() {
        // Nettoyage safe
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
        // Reset sprint à l’impact pour maximiser le KB appliqué
        val d = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        val ms = if (d <= 3.0f) RandomUtils.randomIntInRange(wTapClose.first, wTapClose.last)
                 else RandomUtils.randomIntInRange(wTapFar.first, wTapFar.last)
        Combat.wTap(ms)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, ms)
    }

    private fun edgeAhead(dist: Float): Boolean {
        val p = mc.thePlayer ?: return false
        // s'il n'y a PAS de bloc devant nous à la hauteur du pied -> vide
        return WorldUtils.blockInFront(p, dist, 0.0f) == Blocks.air
    }

    private fun preferLeftTowardCenter(): Boolean {
        val p = mc.thePlayer ?: return false
        // vrai = le point (centre) est à gauche du yaw actuel -> strafe gauche rapproche du centre
        return WorldUtils.leftOrRightToPoint(p, Vec3(centerX, 0.0, centerZ))
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        // Suivi + sprint permanent
        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.12f)

        // ---- Activer l'attaque auto quand on est en portée ----
        if (distance <= 3.2f && !Mouse.isUsingPotion() && !Mouse.isUsingProjectile()) {
            Mouse.startLeftAC()
        } else {
            Mouse.stopLeftAC()
        }

        // ---- Éviter le vide : ne JAMAIS avancer quand c'est "air" devant ----
        val voidNear = edgeAhead(edgeProbeNear)
        val voidFar = edgeAhead(edgeProbeFar)

        if (voidNear || voidFar) {
            Movement.stopForward()
        } else if (!tapping && distance >= reForwardDist) {
            Movement.startForward()
        }

        // ---- Distance-jump contrôlé (engage) ----
        // seulement si pas de vide devant, au sol, et dans la fenêtre utile
        if (!voidNear && !voidFar && p.onGround && distance in engageJumpMin..engageJumpMax && !tapping) {
            Movement.singleJump(RandomUtils.randomIntInRange(140, 200))
        }

        // ---- Micro backstep anti-combo (rare et court) ----
        if (p.hurtTime > 0 && (now - lastBackstep) > microBackstepCd && distance < 3.6f) {
            // petit recul (on laisse handle() gérer les latéraux)
            Movement.stopForward()
            TimeUtils.setTimeout(Movement::startForward, RandomUtils.randomIntInRange(microBackstepDur.first, microBackstepDur.last))
            lastBackstep = now
        }

        // ---- Strafe : edge-aware + anti-stagnation ----
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        // Si on est proche d'un bord (air devant), forcer le strafe vers le centre
        if (voidNear || voidFar) {
            val toLeft = preferLeftTowardCenter()
            val w = 10
            if (toLeft) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        } else {
            // Strafe normal : on suit l'angle sur l'ennemi et on alterne de temps en temps
            val rotations = EntityUtils.getRotations(opp, p, false)
            if (rotations != null && now - lastStrafeSwitch > 320) {
                val preferSide = if (rotations[0] < 0) +1 else -1
                if (preferSide != strafeDir) {
                    strafeDir = preferSide
                    lastStrafeSwitch = now
                }
            }

            // alterner régulièrement pour rester imprévisible
            if (now - lastStrafeSwitch > RandomUtils.randomIntInRange(strafeFlipBase.first, strafeFlipBase.last)) {
                strafeDir = -strafeDir
                lastStrafeSwitch = now
            }

            // anti-stagnation à mi-distance
            val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
            if (distance in 1.8f..3.6f) {
                if (deltaDist < 0.03f) {
                    if (stagnantSince == 0L) stagnantSince = now
                    else if (now - stagnantSince > 520 && now - lastStrafeSwitch > 280) {
                        strafeDir = -strafeDir
                        lastStrafeSwitch = now
                        stagnantSince = 0L
                    }
                } else stagnantSince = 0L
            } else stagnantSince = 0L

            val weight = if (distance < 3.2f) 8 else 6
            if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
            randomStrafe = distance >= 3.2f && distance <= 7.5f
        }

        // ---- Gestion avant/arrière simple pour coller sans s'emmêler ----
        if (distance < stopForwardDist) {
            Movement.stopForward()
        } else if (!tapping && distance > reForwardDist && !voidNear && !voidFar) {
            Movement.startForward()
        }

        // ---- Pas de saut en mêlée (Sumo) ----
        Movement.stopJumping()

        handle(clear, randomStrafe, movePriority)
        prevDistance = distance
    }
}
