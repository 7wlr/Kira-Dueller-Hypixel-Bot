package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.Gap
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.features.Potion
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.WorldUtils
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3

class Combo : BotBase("/play duels_combo_duel"), MovePriority, Gap, Potion {

    override fun getName(): String = "Combo"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.combo_duel_wins",
                "losses" to "player.stats.Duels.combo_duel_losses",
                "ws" to "player.stats.Duels.current_combo_winstreak",
            )
        )
    }

    // --------- Ouverture (Potion -> Gapple enchaînés) ----------
    private var openingPhase = true
    private var openingScheduled = false
    private var dontStartLeftAC = false

    // --------- Items & cooldowns ----------
    private var strengthPots = 2
    override var lastPotion = 0L

    private var gaps = 32
    override var lastGap = 0L

    private var pearls = 5
    private var lastPearl = 0L

    private var tapping = false

    // --------- Armure ----------
    enum class ArmorEnum { BOOTS, LEGGINGS, CHESTPLATE, HELMET }
    private var armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)

    // --------- Strafe “Classic-like” ----------
    private var prevDistance = -1f
    private var lastStrafeSwitch = 0L
    private var strafeDir = 1
    private var stagnantSince = 0L

    // Close-range state machine
    private var closeStrafeMode = 0
    private val MODE_BURST = 0
    private val MODE_HOLD_LEFT = 1
    private val MODE_HOLD_RIGHT = 2
    private var closeStrafeNextAt = 0L
    private var closeStrafeToggleAt = 0L

    // Centre “virtuel” pour recentrer quand on est près d’un bord/obstacle
    private var centerX = 0.0
    private var centerZ = 0.0

    override fun onGameStart() {
        Movement.startSprinting()
        Movement.startForward()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        // point "centre" ≈ spawn du joueur (sert juste à donner une direction vers l’intérieur)
        mc.thePlayer?.let {
            centerX = it.posX
            centerZ = it.posZ
        }

        // Séquence d’ouverture verrouillée
        openingPhase = true
        openingScheduled = false
        dontStartLeftAC = true

        // Strafes init
        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        stagnantSince = 0L
        closeStrafeMode = MODE_BURST
        closeStrafeNextAt = 0L
        closeStrafeToggleAt = 0L
        tapping = false
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            tapping = false

            strengthPots = 2
            lastPotion = 0L

            gaps = 32
            lastGap = 0L

            pearls = 5
            lastPearl = 0L

            armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)
            openingPhase = false
            openingScheduled = false
            dontStartLeftAC = false
            Mouse.stopTracking()
        }, RandomUtils.randomIntInRange(100, 300))
    }

    // Force un vrai "drink" de potion de force en retenant le clic droit
    private fun forceDrinkStrengthPotion(): Boolean {
        val p = mc.thePlayer ?: return false
        if (p.isPotionActive(net.minecraft.potion.Potion.damageBoost) || strengthPots <= 0) return false
        // Sélectionne un item de type potion sur la hotbar / inventaire
        val selected = Inventory.setInvItem("potion")
        // Maintien suffisant pour la boisson (~1.5–1.7s)
        val hold = RandomUtils.randomIntInRange(1450, 1680)
        Mouse.rClick(hold)
        lastPotion = System.currentTimeMillis()
        strengthPots--
        return selected
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        // Anti-saut en mêlée
        if (distance < 8f) Movement.stopJumping()

        // Petit hop si on a pris de l'avance et qu'on est au sol (anti-flat)
        if (combo >= 3 && distance >= 3.2f && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
        }

        // Avance/arrêt simples (éviter de pousser trop quand collé)
        if (distance < 1.5f || (distance < 2.4f && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // S’il y a un bloc devant à ~3 blocs/1.5 haut, ne pas déclencher “runaway”
        if (WorldUtils.blockInFront(p, 3f, 1.5f) != Blocks.air) {
            Mouse.setRunningAway(false)
        }

        // ====================== OUVERTURE FORCÉE ======================
        if (openingPhase && !openingScheduled) {
            openingScheduled = true
            // Potion : on BOIT réellement, puis on enchaîne la gapple dès que fini
            TimeUtils.setTimeout({
                val drank = forceDrinkStrengthPotion()
                val waitForEnd = if (drank) RandomUtils.randomIntInRange(1480, 1650) else RandomUtils.randomIntInRange(40, 80)
                TimeUtils.setTimeout({
                    // Gapple juste après la potion (sans "fenêtre safe")
                    if (gaps > 0) {
                        lastGap = System.currentTimeMillis()
                        useGap(distance, distance < 2f, EntityUtils.entityFacingAway(p, opp))
                        gaps--
                    }
                    // Fin d'ouverture après la fin (ou quasi fin) de l'animation de mange
                    val eatHold = RandomUtils.randomIntInRange(1380, 1650)
                    TimeUtils.setTimeout({
                        dontStartLeftAC = false
                        openingPhase = false
                    }, eatHold / 2)
                }, waitForEnd)
            }, RandomUtils.randomIntInRange(20, 60))
        }

        // ====================== POTIONS (hors ouverture) ======================
        if (!openingPhase &&
            !p.isPotionActive(net.minecraft.potion.Potion.damageBoost) &&
            now - lastPotion > 5000
        ) {
            if (strengthPots > 0) {
                lastPotion = now
                strengthPots--
                // Force aussi la boisson si jamais la routine interne tergiverse
                val drank = forceDrinkStrengthPotion()
                if (!drank) {
                    // Fallback sur le helper existant (slot 8) si la sélection "potion" n'a pas matché
                    usePotion(8, distance < 3f, EntityUtils.entityFacingAway(opp, p))
                }
            }
        }

        // ====================== ARMURE (hors ouverture) ======================
        if (!openingPhase) {
            for (i in 0..3) {
                if (p.inventory.armorItemInSlot(i) == null) {
                    Mouse.stopLeftAC()
                    dontStartLeftAC = true
                    if ((armor[i] ?: 0) > 0) {
                        TimeUtils.setTimeout({
                            val ok = Inventory.setInvItem(ArmorEnum.values()[i].name.lowercase())
                            if (ok) {
                                armor[i] = (armor[i] ?: 0) - 1
                                TimeUtils.setTimeout({
                                    val r = RandomUtils.randomIntInRange(100, 150)
                                    Mouse.rClick(r)
                                    TimeUtils.setTimeout({
                                        Inventory.setInvItem("sword")
                                        TimeUtils.setTimeout({
                                            dontStartLeftAC = false
                                        }, RandomUtils.randomIntInRange(200, 300))
                                    }, r + RandomUtils.randomIntInRange(100, 150))
                                }, RandomUtils.randomIntInRange(200, 400))
                            } else {
                                dontStartLeftAC = false
                            }
                        }, RandomUtils.randomIntInRange(250, 500))
                    }
                }
            }
        }

        // ====================== GAPPLE (hors ouverture) ======================
        // Mange même en plein combo si besoin (aucune fenêtre safe)
        if (!openingPhase && gaps > 0) {
            val needGap = (p.health < 12f) || !p.isPotionActive(net.minecraft.potion.Potion.absorption)
            val cdOk = (now - lastGap) > 1600 // ~temps d'une bouchée; évite le spam dur
            if (needGap && cdOk) {
                useGap(distance, distance < 2f, EntityUtils.entityFacingAway(p, opp))
                gaps--
                lastGap = now
            }
        }

        // ====================== PERLE (hors ouverture) ======================
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        if (!openingPhase &&
            distance > 18f &&
            EntityUtils.entityFacingAway(opp, p) &&
            !Mouse.isRunningAway() &&
            now - lastPearl > 5000 &&
            pearls > 0
        ) {
            lastPearl = now
            Mouse.stopLeftAC()
            dontStartLeftAC = true
            TimeUtils.setTimeout({
                if (Inventory.setInvItem("pearl")) {
                    pearls--
                    Mouse.setUsingProjectile(true)
                    TimeUtils.setTimeout({
                        Mouse.rClick(RandomUtils.randomIntInRange(100, 150))
                        TimeUtils.setTimeout({
                            Mouse.setUsingProjectile(false)
                            Inventory.setInvItem("sword")
                            TimeUtils.setTimeout({
                                dontStartLeftAC = false
                            }, RandomUtils.randomIntInRange(200, 300))
                        }, RandomUtils.randomIntInRange(250, 300))
                    }, RandomUtils.randomIntInRange(300, 600))
                } else {
                    dontStartLeftAC = false
                }
            }, RandomUtils.randomIntInRange(250, 500))
        } else {
            // ===================== STRAFE “CLASSIC” =====================
            fun edgeAhead(distProbe: Float) =
                WorldUtils.blockInFront(p, distProbe, 0.0f) == Blocks.air

            val voidNear = edgeAhead(1.4f) || edgeAhead(2.0f)
            if (voidNear) {
                val toLeft = WorldUtils.leftOrRightToPoint(p, Vec3(centerX, 0.0, centerZ))
                val w = 10
                if (toLeft) movePriority[0] += w else movePriority[1] += w
                randomStrafe = false
            } else {
                // Close-range machine (< 2.6 blocs)
                if (distance < 2.6f) {
                    if (now >= closeStrafeNextAt) {
                        val roll = RandomUtils.randomIntInRange(0, 99)
                        closeStrafeMode = when {
                            roll < 50 -> MODE_BURST
                            roll < 75 -> MODE_HOLD_LEFT
                            else     -> MODE_HOLD_RIGHT
                        }
                        closeStrafeNextAt = now + when (closeStrafeMode) {
                            MODE_BURST -> RandomUtils.randomIntInRange(280, 420).toLong()
                            else       -> RandomUtils.randomIntInRange(220, 340).toLong()
                        }
                        if (closeStrafeMode == MODE_BURST) {
                            closeStrafeToggleAt = now + RandomUtils.randomIntInRange(60, 110)
                        } else {
                            strafeDir = if (closeStrafeMode == MODE_HOLD_LEFT) -1 else 1
                        }
                    } else if (closeStrafeMode == MODE_BURST && now >= closeStrafeToggleAt) {
                        strafeDir = -strafeDir
                        closeStrafeToggleAt = now + RandomUtils.randomIntInRange(60, 110)
                    }

                    val weightClose = 6
                    if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                    Movement.startForward()
                    Movement.startSprinting()
                    randomStrafe = false
                } else {
                    // Medium/long range
                    if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(820, 1100)) {
                        strafeDir = -strafeDir
                        lastStrafeSwitch = now
                    }
                    val deltaDist = if (prevDistance > 0f) kotlin.math.abs(distance - prevDistance) else 999f
                    if (distance in 1.8f..3.6f && deltaDist < 0.03f && now - lastStrafeSwitch > 260) {
                        strafeDir = -strafeDir
                        lastStrafeSwitch = now
                    }
                    val weight = if (distance < 4f) 7 else 5
                    if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
                    randomStrafe = (distance in 8.0f..15.0f)
                }
            }

            handle(clear, randomStrafe, movePriority)
        }

        prevDistance = distance
    }

    override fun onAttack() {
        // Petit W-tap pour conserver la pression, sans spam
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 120)
    }
}
