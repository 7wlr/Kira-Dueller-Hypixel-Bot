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

    // --- Paramètres comportementaux (changements demandés) ---
    private val jumpDistanceThreshold = 5.0      // Sauter dès que l’ennemi est > 5 blocs
    private val bowCancelCloseDistance = 6.0     // Annuler l’arc si l’ennemi revient < 6 blocs

    var shotsFired = 0
    var maxArrows = 5

    override fun onGameStart() {
        // Auto-aim OFF : on s’assure que le bot ne force pas le tracking (géré par ton autre mod)
        Mouse.stopTracking()

        Movement.startSprinting()
        Movement.startForward()
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(400, 1200))
    }

    override fun onGameEnd() {
        shotsFired = 0
        // Par sécurité on coupe tout clic automatique (même si on ne l’active plus dans ce profil)
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

    override fun onAttack() {
        // L'événement d’attaque reste utile pour la logique de w-tap/blockhit,
        // mais l’initiation des clics gauche n’est PLUS faite par ce bot (CPS géré ailleurs).
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        if (distance < 3) {
            if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
                val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
                if (n.contains("rod")) { // wait after hitting with the rod
                    Combat.wTap(300)
                    tapping = true
                    combo--
                    TimeUtils.setTimeout(fun () {
                        tapping = false
                    }, 300)
                } else if (n.contains("sword")) {
                    // Blockhit léger pour un feeling humain
                    Mouse.rClick(RandomUtils.randomIntInRange(80, 100))
                }
            }
        } else {
            Combat.wTap(100)
            tapping = true
            TimeUtils.setTimeout(fun () {
                tapping = false
            }, 100)
        }
        if (combo >= 3) {
            Movement.clearLeftRight()
        }
    }

    override fun onTick() {
        var needJump = false
        if (mc.thePlayer != null) {
            if (WorldUtils.blockInFront(mc.thePlayer, 2f, 0.5f) != Blocks.air && mc.thePlayer.onGround) {
                needJump = true
                Movement.singleJump(RandomUtils.randomIntInRange(150, 250))
            }
        }
        if (opponent() != null && mc.theWorld != null && mc.thePlayer != null) {
            if (!mc.thePlayer.isSprinting) {
                Movement.startSprinting()
            }

            val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())

            // ---- Auto-aim OFF : ne jamais lancer le tracking ici (géré par ton autre mod)
            Mouse.stopTracking()

            // ---- Auto-CPS OFF : le bot ne déclenche plus de clic gauche auto
            // (on ne fait plus startLeftAC nulle part dans ce profil)
            // On peut s'assurer qu'il n'y a jamais d'état résiduel :
            // (Appel idempotent par tick, tolérable côté perfs)
            Mouse.stopLeftAC()

            // ---- Gestion du saut : rendre le bot plus "vivant" dès > 5 blocs
            if (distance > jumpDistanceThreshold) {
                if (opponent() != null && opponent()!!.heldItem != null && opponent()!!.heldItem.unlocalizedName.lowercase().contains("bow")) {
                    if (WorldUtils.blockInFront(mc.thePlayer, 2f, 0.5f) == Blocks.air) {
                        if (!EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) && !needJump) {
                            Movement.stopJumping()
                        } else {
                            Movement.startJumping()
                        }
                    } else {
                        Movement.startJumping()
                    }
                } else {
                    Movement.startJumping()
                }
            } else {
                if (!needJump) {
                    Movement.stopJumping()
                }
            }

            // ---- Si on était en train d’utiliser l’arc, mais que l’adversaire se retourne
            //      OU revient trop près, on ABANDONNE l’arc et on repasse épée.
            if (Mouse.isUsingProjectile()) {
                val facingUs = !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)
                val tooClose = distance < bowCancelCloseDistance
                if (facingUs || tooClose) {
                    Mouse.rClickUp()                 // on relâche l’arc
                    Inventory.setInvItem("sword")    // on revient à l’épée
                }
            }

            val movePriority = arrayListOf(0, 0)
            var clear = false
            var randomStrafe = false

            if (distance < 1 || (distance < 2.7 && combo >= 1)) {
                Movement.stopForward()
            } else {
                if (!tapping) {
                    Movement.startForward()
                }
            }

            if (distance < 1.5 && mc.thePlayer.heldItem != null && !mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword")) {
                Inventory.setInvItem("sword")
                Mouse.rClickUp()
                // (Auto-CPS OFF) -> on ne démarre plus Mouse.startLeftAC() ici
            }

            if ((distance in 5.7..6.5 || distance in 9.0..9.5) && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                if (!Mouse.isUsingProjectile()) {
                    useRod()
                }
            }

            if (combo >= 3 && distance >= 3.2 && mc.thePlayer.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
            }

            // Logique d’utilisation de l’arc : seulement si l’ennemi est de dos OU très loin,
            // et uniquement si on n’a pas dépassé le quota de flèches.
            if ((EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) && distance in 3.5f..30f) ||
                (distance in 28.0..33.0 && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!))) {
                if (distance > 5 && !Mouse.isUsingProjectile() && shotsFired < maxArrows) {
                    clear = true
                    useBow(distance, fun () {
                        shotsFired++
                    })
                } else {
                    clear = false
                    if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) {
                        movePriority[0] += 4
                    } else {
                        movePriority[1] += 4
                    }
                }
            } else {
                if (EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                    if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) {
                        movePriority[0] += 4
                    } else {
                        movePriority[1] += 4
                    }
                } else {
                    // NOTE: condition "distance in 15f..8f" d’origine paraît inversée,
                    // je ne la modifie pas ici pour rester strictement dans le scope demandé.
                    if (distance in 15f..8f) {
                        randomStrafe = true
                    } else {
                        randomStrafe = false
                        if (opponent() != null && opponent()!!.heldItem != null &&
                            (opponent()!!.heldItem.unlocalizedName.lowercase().contains("bow") ||
                             opponent()!!.heldItem.unlocalizedName.lowercase().contains("rod"))) {
                            randomStrafe = true
                            if (distance < 15 && !needJump) {
                                Movement.stopJumping()
                            }
                        } else {
                            if (distance < 8) {
                                val rotations = EntityUtils.getRotations(opponent()!!, mc.thePlayer, false)
                                if (rotations != null) {
                                    if (rotations[0] < 0) {
                                        movePriority[1] += 5
                                    } else {
                                        movePriority[0] += 5
                                    }
                                }
                            }
                        }
                    }
                }
            }

            handle(clear, randomStrafe, movePriority)
        }
    }

}
