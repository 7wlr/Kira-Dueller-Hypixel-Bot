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

class BowDuel : BotBase("/play duels_bow_duel"), Bow, MovePriority {

    override fun getName(): String = "Bow"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.bow_duel_wins",
                "losses" to "player.stats.Duels.bow_duel_losses",
                "ws" to "player.stats.Duels.current_bow_winstreak",
            )
        )
    }

    private var shotsFired = 0
    private val maxArrows = 15
    private var lastShot = 0L
    private val shotCooldown = 800L

    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.startJumping()
        Mouse.stopLeftAC()
        
        shotsFired = 0
        lastShot = 0L
        
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

        // Gestion des sauts - plus agressif pour éviter les flèches
        if (distance > 8f) {
            Movement.startJumping()
        } else if (distance > 3f) {
            // Sauts occasionnels à moyenne distance
            if (RandomUtils.randomIntInRange(0, 100) < 15) {
                Movement.singleJump(RandomUtils.randomIntInRange(200, 300))
            } else {
                Movement.stopJumping()
            }
        } else {
            Movement.stopJumping()
        }

        // Éviter les obstacles
        if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(150, 250))
        }

        // Mouvement avant/arrière
        if (distance < 8f) {
            Movement.stopForward()
            // Reculer si trop proche
            if (distance < 5f) {
                Movement.startBackward()
            } else {
                Movement.stopBackward()
            }
        } else {
            Movement.stopBackward()
            Movement.startForward()
        }

        // Tir à l'arc
        if (!Mouse.isUsingProjectile() && shotsFired < maxArrows && (now - lastShot) > shotCooldown) {
            val shouldShoot = when {
                distance > 15f -> true // Toujours tirer à longue distance
                distance > 10f -> RandomUtils.randomIntInRange(0, 100) < 80 // 80% de chance
                distance > 6f -> RandomUtils.randomIntInRange(0, 100) < 60 // 60% de chance
                else -> RandomUtils.randomIntInRange(0, 100) < 30 // 30% de chance à courte distance
            }

            if (shouldShoot) {
                useBowImmediateFull {
                    shotsFired++
                    lastShot = System.currentTimeMillis()
                }
                return
            }
        }

        // Mouvement strafe - très important pour éviter les flèches
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        if (EntityUtils.entityFacingAway(p, opp)) {
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) {
                movePriority[0] += 4
            } else {
                movePriority[1] += 4
            }
        } else {
            // Strafe agressif pour éviter les tirs
            randomStrafe = true
            
            // Ajouter une préférence basée sur la position de l'ennemi
            val rotations = EntityUtils.getRotations(opp, p, false)
            if (rotations != null) {
                if (rotations[0] < 0) movePriority[1] += 3 else movePriority[0] += 3
            }
        }

        handle(clear, randomStrafe, movePriority)
    }
}