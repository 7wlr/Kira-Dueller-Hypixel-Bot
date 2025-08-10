package best.spaghetcodes.duckdueller.bot.bots

import best.spaghetcodes.duckdueller.DuckDueller
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

class Classic : BotBase("/play duels_classic_duel"), Bow, Rod, MovePriority {

    override fun getName(): String {
        return "Classic"
    }

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.classic_duel_wins",
                "losses" to "player.stats.Duels.classic_duel_losses",
                "ws" to "player.stats.Duels.current_classic_winstreak",
            )
        )
    }

    // === PARAMÈTRES D'AGRESSIVITÉ AMÉLIORÉS ===
    private val jumpDistanceThreshold = 4.5f  // Réduit de 5.0f pour sauter moins et rester plus proche
    private val aggressiveDistance = 2.8f     // Distance pour mode ultra-agressif
    private val rodSpamDistance = 7.5f        // Distance optimale pour rod spam
    
    var shotsFired = 0
    var maxArrows = 5
    
    // Nouveaux compteurs pour comportement agressif
    private var rodUses = 0
    private var maxRodUses = 8
    private var lastRodUse = 0L
    private var lastBowSwitch = 0L
    private var aggressiveMode = false
    private var lastHitTime = 0L

    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        
        // Délai réduit pour être plus agressif dès le début
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(200, 600))
        
        // Réinitialisation des compteurs
        rodUses = 0
        shotsFired = 0
        aggressiveMode = false
        lastHitTime = 0L
    }

    override fun onGameEnd() {
        shotsFired = 0
        rodUses = 0
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout(fun () {
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    var tapping = false
    private var comboLocked = false  // Pour maintenir la pression en combo

    override fun onAttack() {
        lastHitTime = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        
        // Comportement ultra-agressif en close combat
        if (distance < aggressiveDistance) {
            if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
                val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
                
                if (n.contains("rod") && rodUses < maxRodUses) {
                    // Rod tap plus court pour enchaîner plus vite
                    Combat.wTap(200)
                    tapping = true
                    combo--
                    TimeUtils.setTimeout(fun () { tapping = false }, 200)
                    rodUses++
                } else if (n.contains("sword")) {
                    // W-tap court pour maintenir la pression
                    if (combo < 3) {
                        Combat.wTap(80)
                        tapping = true
                        TimeUtils.setTimeout(fun () { tapping = false }, 80)
                    }
                }
            }
        } else {
            // W-tap standard à distance moyenne
            Combat.wTap(100)
            tapping = true
            TimeUtils.setTimeout(fun () { tapping = false }, 100)
        }
        
        // Active le mode agressif après 2 hits consécutifs
        if (combo >= 2) {
            aggressiveMode = true
            Movement.clearLeftRight()
            comboLocked = true
            TimeUtils.setTimeout(fun () { comboLocked = false }, 500)
        }
    }

    override fun onAttacked() {
        // Réaction défensive mais reste agressif
        aggressiveMode = false
        comboLocked = false
        
        // Essaie de reprendre l'avantage avec une rod
        if (rodUses < maxRodUses && System.currentTimeMillis() - lastRodUse > 800) {
            TimeUtils.setTimeout(fun () {
                if (opponent() != null) {
                    val dist = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
                    if (dist in 4f..8f) {
                        useAggressiveRod()
                    }
                }
            }, RandomUtils.randomIntInRange(100, 200))
        }
    }

    override fun onTick() {
        var needJump = false
        if (mc.thePlayer != null) {
            // Saut plus réactif pour les obstacles
            if (WorldUtils.blockInFront(mc.thePlayer, 1.5f, 0.5f) != Blocks.air && mc.thePlayer.onGround) {
                needJump = true
                Movement.singleJump(RandomUtils.randomIntInRange(120, 200))
            }
        }
        
        if (opponent() != null && mc.theWorld != null && mc.thePlayer != null) {
            if (!mc.thePlayer.isSprinting) Movement.startSprinting()

            val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
            val opponentHealth = opponent()!!.health
            val myHealth = mc.thePlayer.health

            // === TRACKING PERMANENT ===
            Mouse.startTracking()
            Mouse.stopLeftAC()

            // === GESTION DU SAUT OPTIMISÉE ===
            if (distance > jumpDistanceThreshold) {
                // Logique anti-bow améliorée
                if (opponent()!!.heldItem?.unlocalizedName?.lowercase()?.contains("bow") == true) {
                    if (!EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) && !needJump) {
                        // Strafe au sol contre les archers
                        Movement.stopJumping()
                    } else {
                        Movement.startJumping()
                    }
                } else {
                    // Jump strafe agressif à moyenne distance
                    if (distance in 6f..10f && !aggressiveMode) {
                        Movement.startJumping()
                    } else {
                        Movement.stopJumping()
                    }
                }
            } else if (!needJump) {
                Movement.stopJumping()
            }

            // === GESTION DE LA DISTANCE ULTRA-AGRESSIVE ===
            when {
                distance < 0.8f -> Movement.stopForward()
                distance < 1.5f && combo >= 2 -> Movement.stopForward()
                distance < 2.5f && combo >= 1 && !comboLocked -> Movement.stopForward()
                aggressiveMode && distance < 3f -> {
                    // Mode rush agressif
                    if (!tapping) Movement.startForward()
                }
                !tapping -> Movement.startForward()
            }

            // === CHANGEMENT D'ARME INTELLIGENT ===
            if (distance < 1.3f && mc.thePlayer.heldItem != null && 
                !mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")) {
                Inventory.setInvItem("sword")
                Mouse.rClickUp()
            }

            // === ROD SPAM AGRESSIF ===
            val canUseRod = !Mouse.isUsingProjectile() && 
                           rodUses < maxRodUses && 
                           System.currentTimeMillis() - lastRodUse > 600

            if (canUseRod) {
                when {
                    // Rod à distance optimale
                    distance in 5.5f..7f && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) -> {
                        useAggressiveRod()
                    }
                    // Rod en close combat si pas en combo
                    distance in 3f..5f && combo == 0 && opponentCombo >= 1 -> {
                        useAggressiveRod()
                    }
                    // Rod préventive à longue distance
                    distance in 8.5f..10f && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) -> {
                        if (RandomUtils.randomBool()) useAggressiveRod()
                    }
                }
            }

            // === JUMP RESET AGRESSIF ===
            if (combo >= 3 && distance >= 3f && mc.thePlayer.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(80, 120))
            }

            // === BOW USAGE OPTIMISÉ ===
            if (Mouse.isUsingProjectile()) {
                val facingUs = !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)
                val tooClose = distance < bowCancelCloseDistance - 1f  // Plus agressif
                if (facingUs || tooClose) {
                    Mouse.rClickUp()
                    Inventory.setInvItem("sword")
                }
            }

            // === STRATÉGIE DE MOUVEMENT AVANCÉE ===
            val movePriority = arrayListOf(0, 0)
            var clear = false
            var randomStrafe = false

            // Décisions de mouvement basées sur la situation
            when {
                // Bow play agressif
                (EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) && distance in 4f..25f) -> {
                    if (distance > 6f && !Mouse.isUsingProjectile() && shotsFired < maxArrows &&
                        System.currentTimeMillis() - lastBowSwitch > 2000) {
                        clear = true
                        lastBowSwitch = System.currentTimeMillis()
                        useBow(distance) { shotsFired++ }
                    } else {
                        // Poursuite agressive
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) {
                            movePriority[0] += 6
                        } else {
                            movePriority[1] += 6
                        }
                    }
                }
                
                // Combat rapproché ultra-agressif
                distance < 4f && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) -> {
                    when {
                        aggressiveMode -> {
                            // Stick sur l'adversaire en mode combo
                            clear = true
                        }
                        combo > 0 -> {
                            // Léger strafe pour maintenir l'avantage
                            val rotations = EntityUtils.getRotations(opponent()!!, mc.thePlayer, false)
                            if (rotations != null) {
                                if (rotations[0] < 0) movePriority[1] += 7 else movePriority[0] += 7
                            }
                        }
                        else -> {
                            // Strafe agressif pour chercher l'ouverture
                            randomStrafe = true
                        }
                    }
                }
                
                // Distance moyenne - pression constante
                distance in 4f..8f -> {
                    if (opponent()!!.heldItem?.unlocalizedName?.lowercase()?.contains("bow") == true ||
                        opponent()!!.heldItem?.unlocalizedName?.lowercase()?.contains("rod") == true) {
                        // Strafe rapide contre projectiles
                        randomStrafe = true
                        if (distance < 6f && !needJump) Movement.stopJumping()
                    } else {
                        // Approche en zigzag
                        if (EntityUtils.entityMovingLeft(mc.thePlayer, opponent()!!)) {
                            movePriority[1] += 3
                        } else {
                            movePriority[0] += 3
                        }
                    }
                }
                
                // Longue distance - strafe défensif
                distance in 8f..15f -> {
                    randomStrafe = true
                }
                
                else -> {
                    // Poursuite par défaut
                    if (EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) {
                            movePriority[0] += 4
                        } else {
                            movePriority[1] += 4
                        }
                    }
                }
            }

            // === MODE BERSERK (santé faible) ===
            if (myHealth < 6 && opponentHealth > myHealth) {
                aggressiveMode = true
                clear = false
                randomStrafe = true
                // All-in avec les dernières ressources
                if (rodUses < maxRodUses && distance in 4f..7f) {
                    useAggressiveRod()
                }
            }

            handle(clear, randomStrafe, movePriority)
        }
    }

    // Nouvelle fonction pour rod plus agressive
    private fun useAggressiveRod() {
        if (System.currentTimeMillis() - lastRodUse > 600) {
            lastRodUse = System.currentTimeMillis()
            rodUses++
            useRod()
            // Suit immédiatement avec une attaque
            TimeUtils.setTimeout(fun () {
                Inventory.setInvItem("sword")
                Mouse.startLeftAC()
            }, RandomUtils.randomIntInRange(250, 350))
        }
    }
}
