package best.spaghetcodes.duckdueller.bot.bots

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.features.Bow
import best.spaghetcodes.duckdueller.bot.features.MovePriority
import best.spaghetcodes.duckdueller.bot.player.Combat
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.bot.player.Movement
import best.spaghetcodes.duckdueller.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs

class BowDuel : BotBase("/play duels_bow_duel"), Bow, MovePriority {

    override fun getName(): String = "Bow"

    init {
        setStatKeys(
            mapOf(
                "wins"   to "player.stats.Duels.bow_duel_wins",
                "losses" to "player.stats.Duels.bow_duel_losses",
                "ws"     to "player.stats.Duels.current_bow_winstreak",
            )
        )
    }

    // ---------- Tir / cadence ----------
    private var shotsFired = 0
    private var lastShot = 0L
    private val shotCooldown = 800L

    // Rafale -> phase d’esquive
    private var burstCount = 0
    private var evasionUntil = 0L
    private var strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
    private var strafeFlipAt = 0L

    // Hop-shot (saut latéral avant tir)
    private var forceStrafeDir = 1
    private var forceStrafeUntil = 0L
    private var shotPlannedUntil = 0L

    // ---------- Pearl (1 usage, défensif) ----------
    private var pearls = 1
    private var lastPearl = 0L
    private val pearlCooldown = 6000L
    private val pearlEscapeDist = 5.5f
    // ------------------------------------------------

    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Mouse.stopLeftAC()

        shotsFired = 0
        lastShot = 0L
        burstCount = 0
        evasionUntil = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        strafeFlipAt = 0L

        pearls = 1
        lastPearl = 0L

        forceStrafeDir = 1
        forceStrafeUntil = 0L
        shotPlannedUntil = 0L

        // Équiper l'arc immédiatement
        Inventory.setInvItem("bow")
    }

    override fun onGameEnd() {
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
            shotsFired = 0
            burstCount = 0
            evasionUntil = 0L
            pearls = 1
            lastPearl = 0L
            forceStrafeUntil = 0L
            shotPlannedUntil = 0L
        }, RandomUtils.randomIntInRange(200, 400))
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        val distance = EntityUtils.getDistanceNoY(p, opp)
        val now = System.currentTimeMillis()

        // Toujours garder l'arc en main
        if (p.heldItem == null || !p.heldItem.unlocalizedName.lowercase().contains("bow")) {
            Inventory.setInvItem("bow")
        }

        // Anti-bloc : micro-saut si un bloc devant
        if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround && !Mouse.rClickDown) {
            Movement.singleJump(RandomUtils.randomIntInRange(140, 220))
        }

        // Ne JAMAIS sauter pendant qu'on bande l'arc (précision)
        if (Mouse.rClickDown) {
            Movement.stopJumping()
        }

        // --------- PEARL (défensif : s'éloigner quand trop proche) ----------
        if (pearls > 0 &&
            (now - lastPearl) > pearlCooldown &&
            distance < pearlEscapeDist &&
            !Mouse.isUsingProjectile() &&
            !Mouse.rClickDown) {

            lastPearl = now
            Mouse.stopLeftAC()
            Inventory.setInvItem("pearl")

            // Se retourner automatiquement le temps du lancer
            Mouse.setRunningAway(true)
            Mouse.setUsingProjectile(true)

            val clickDur = RandomUtils.randomIntInRange(95, 125)
            // Lancer la perle après avoir regardé à l'opposé  (un tick ~50ms)
            TimeUtils.setTimeout({
                Mouse.rClick(clickDur)
                TimeUtils.setTimeout({
                    Mouse.setUsingProjectile(false)
                    Mouse.setRunningAway(false)
                    Inventory.setInvItem("bow")
                    pearls--
                }, RandomUtils.randomIntInRange(220, 320))
            }, RandomUtils.randomIntInRange(70, 110))
            return
        }
        // -------------------------------------------------------------------

        // --------- ÉVASION ACTIVE après rafale ----------
        val evading = now < evasionUntil
        if (evading && !Mouse.rClickDown) {
            Movement.startJumping()
            if (now >= strafeFlipAt) {
                strafeDir = -strafeDir
                strafeFlipAt = now + RandomUtils.randomIntInRange(220, 360)
            }
        } else if (!evading && !Mouse.rClickDown) {
            // Pas de saut inutile hors évitement/hop-shot
            Movement.stopJumping()
        }
        // -----------------------------------------------

        // --------- Tir à l’arc orchestré : HOP puis SHOOT ----------
        // 1) Si un tir est planifié, on le déclenche au bon moment (après le saut latéral)
        if (shotPlannedUntil != 0L && now >= shotPlannedUntil && !Mouse.isUsingProjectile()) {
            shotPlannedUntil = 0L
            // Tir FULL (Mouse.kt gère la compensation de pitch pendant le bandage)
            useBowImmediateFull {
                shotsFired++
                lastShot = System.currentTimeMillis()
                burstCount++

                // Après 3–4 flèches consécutives -> petite phase d'évasion latérale
                if (burstCount >= RandomUtils.randomIntInRange(3, 4)) {
                    evasionUntil = lastShot + RandomUtils.randomIntInRange(900, 1200)
                    strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                    strafeFlipAt = lastShot + RandomUtils.randomIntInRange(220, 360)
                    burstCount = 0
                }
            }
            // on laisse la main
            return
        }

        // 2) Sinon, décider de lancer un hop-shot (alternance gauche/droite), puis shooter
        if (!Mouse.isUsingProjectile() && (now - lastShot) > shotCooldown && shotPlannedUntil == 0L) {
            val shootProb = when {
                distance > 24f -> 100
                distance > 16f -> 85
                distance > 10f -> 70
                distance > 6f  -> 50
                else           -> 25
            }

            if (RandomUtils.randomIntInRange(0, 99) < shootProb) {
                // On force un strafe latéral pendant la durée du hop
                val hopDur = RandomUtils.randomIntInRange(150, 220)
                forceStrafeDir = -forceStrafeDir
                forceStrafeUntil = now + hopDur

                // Petit saut latéral (pendant lequel on ne bande pas l'arc)
                Movement.singleJump(hopDur)

                // Planifier le tir juste après le hop (temps pour retomber / stabiliser)
                shotPlannedUntil = now + hopDur + RandomUtils.randomIntInRange(60, 90)
                // Empêcher une re-entrée prématurée de la logique de tir
                lastShot = shotPlannedUntil
                return
            }
        } else if ((now - lastShot) > (shotCooldown + 700)) {
            // Si on ne tire pas depuis un moment, on casse la rafale
            burstCount = 0
        }
        // ------------------------------------------------------------

        // --------- Strafe / déplacement ----------
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        // Strafe forcé pendant hop-shot
        if (now < forceStrafeUntil) {
            val w = 10
            if (forceStrafeDir < 0) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        } else if (now < evasionUntil) {
            val w = if (distance > 14f) 9 else 7
            if (strafeDir < 0) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        } else {
            if (EntityUtils.entityFacingAway(p, opp)) {
                if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
            } else {
                randomStrafe = true
                val rotations = EntityUtils.getRotations(opp, p, false)
                if (rotations != null && abs(rotations[0]) > 0.0f) {
                    if (rotations[0] < 0) movePriority[1] += 3 else movePriority[0] += 3
                }
            }
        }

        // Gestion avant/arrière simple : rester à distance d’arc
        if (distance < 6f && pearls == 0) {
            // si on n'a plus de perle, on évite de coller
            Movement.stopForward()
        } else {
            Movement.startForward()
        }

        handle(clear, randomStrafe, movePriority)
    }
}
