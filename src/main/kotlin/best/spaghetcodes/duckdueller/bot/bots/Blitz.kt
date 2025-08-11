package best.spaghetcodes.duckdueller.bot.bots

import best.spaghetcodes.duckdueller.bot.BotBase
import best.spaghetcodes.duckdueller.bot.features.MovePriority
import best.spaghetcodes.duckdueller.bot.player.Combat
import best.spaghetcodes.duckdueller.bot.player.Inventory
import best.spaghetcodes.duckdueller.bot.player.Mouse
import best.spaghetcodes.duckdueller.bot.player.Movement
import best.spaghetcodes.duckdueller.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3

class Blitz : BotBase("/play duels_blitz_duel"), MovePriority {

    override fun getName(): String = "Blitz"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.blitz_duel_wins",
                "losses" to "player.stats.Duels.blitz_duel_losses",
                "ws" to "player.stats.Duels.current_blitz_winstreak",
            )
        )
    }

    private var tapping = false
    private var lastKitSwitch = 0L
    private val kitSwitchCooldown = 3000L

    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.startJumping()
        Mouse.stopLeftAC()
        
        lastKitSwitch = 0L
        
        // Équiper l'épée au début
        Inventory.setInvItem("sword")
    }

    override fun onGameEnd() {
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
            tapping = false
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

        // Gestion des sauts - plus agressif pour le mode Blitz
        if (distance > 3f) {
            Movement.startJumping()
        } else {
            Movement.stopJumping()
        }

        // Éviter les obstacles
        if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(150, 250))
        }

        // Mouvement avant/arrière
        if (distance < 1.2f || (distance < 2.5f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // Gestion des objets/kits - spécifique au mode Blitz
        if ((now - lastKitSwitch) > kitSwitchCooldown) {
            // Logique pour changer d'objets selon la situation
            when {
                distance > 8f -> {
                    // Essayer d'utiliser un arc ou des projectiles à longue distance
                    if (Inventory.setInvItem("bow")) {
                        lastKitSwitch = now
                    }
                }
                distance < 3f -> {
                    // S'assurer d'avoir l'épée en combat rapproché
                    if (!p.heldItem?.unlocalizedName?.lowercase()?.contains("sword") == true) {
                        Inventory.setInvItem("sword")
                        lastKitSwitch = now
                    }
                }
                else -> {
                    // Distance moyenne - garder l'épée ou utiliser des objets spéciaux
                    if (Inventory.hasItem("potion")) {
                        // Utiliser des potions si disponibles
                        if (p.health < 15f && RandomUtils.randomIntInRange(0, 100) < 30) {
                            Inventory.setInvItem("potion")
                            TimeUtils.setTimeout({
                                Mouse.rClick(RandomUtils.randomIntInRange(80, 120))
                                TimeUtils.setTimeout({
                                    Inventory.setInvItem("sword")
                                }, RandomUtils.randomIntInRange(200, 300))
                            }, RandomUtils.randomIntInRange(100, 200))
                            lastKitSwitch = now
                        }
                    }
                }
            }
        }

        // Mouvement strafe - adapté au mode Blitz (plus agressif)
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
            when {
                distance < 3f -> {
                    // Combat rapproché - mouvement précis
                    val rotations = EntityUtils.getRotations(opp, p, false)
                    if (rotations != null) {
                        if (rotations[0] < 0) movePriority[1] += 6 else movePriority[0] += 6
                    }
                }
                distance < 8f -> {
                    // Distance moyenne - strafe modéré
                    val rotations = EntityUtils.getRotations(opp, p, false)
                    if (rotations != null) {
                        if (rotations[0] < 0) movePriority[1] += 4 else movePriority[0] += 4
                    }
                }
                else -> {
                    // Longue distance - strafe aléatoire
                    randomStrafe = true
                }
            }
        }

        handle(clear, randomStrafe, movePriority)
    }
}