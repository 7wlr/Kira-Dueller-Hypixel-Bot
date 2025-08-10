package best.spaghetcodes.duckdueller.bot

import best.spaghetcodes.duckdueller.DuckDueller
import best.spaghetcodes.duckdueller.bot.player.*
import best.spaghetcodes.duckdueller.core.KeyBindings
import best.spaghetcodes.duckdueller.utils.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.multiplayer.GuiConnecting
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.network.Packet
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.network.play.server.S45PacketTitle
import net.minecraft.util.EnumChatFormatting
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.event.entity.EntityJoinWorldEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.fml.client.FMLClientHandler
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent
import java.util.Timer

/**
 * Base class for all bots
 * @param queueCommand Command to join a new game
 * @param quickRefresh MS for which to quickly refresh opponent entity
 */
open class BotBase(val queueCommand: String, val quickRefresh: Int = 10000) {

    protected val mc = Minecraft.getMinecraft()

    private var toggled = false
    fun toggled() = toggled
    fun toggle() {
        toggled = !toggled
    }

    private var attackedID = -1

    private var opponent: EntityPlayer? = null
    private var opponentTimer: Timer? = null
    private var calledFoundOpponent = false

    protected var combo = 0
    protected var opponentCombo = 0
    protected var ticksSinceHit = 0

    private var reconnectTimer: Timer? = null

    private var ticksSinceGameStart = 0

    private var lastOpponentName = ""

    private var calledGameEnd = false

    fun opponent() = opponent

    /********
     * Methods to override
     ********/

    open fun getName(): String {
        return "Base"
    }

    /**
     * Called when the bot attacks the opponent
     * Triggered by the damage sound, not the clientside attack event
     */
    protected open fun onAttack() {}

    /**
     * Called when the bot is attacked
     * Triggered by the damage sound, not the clientside attack event
     */
    protected open fun onAttacked() {}

    /**
     * Called when the game starts
     */
    protected open fun onGameStart() {}

    /**
     * Called when the game ends
     */
    protected open fun onGameEnd() {}

    /**
     * Called when the bot joins a game
     */
    protected open fun onJoinGame() {}

    /**
     * Called before the game starts (1s)
     */
    protected open fun beforeStart() {}

    /**
     * Called when the opponent entity is found
     */
    protected open fun onFoundOpponent() {}

    /**
     * Called every tick
     */
    protected open fun onTick() {}

    /********
     * Base Methods
     ********/

    fun onPacket(packet: Packet<*>) {
        if (toggled) {
            when (packet) {
                is S19PacketEntityStatus -> { // use the status packet for attack events
                    if (packet.opCode.toInt() == 2) { // damage
                        val entity = packet.getEntity(mc.theWorld)
                        if (entity != null) {
                            if (entity.entityId == attackedID) {
                                attackedID = -1
                                onAttack()
                                combo++
                                opponentCombo = 0
                                ticksSinceHit = 0
                            } else if (mc.thePlayer != null && entity.entityId == mc.thePlayer.entityId) {
                                onAttacked()
                                combo = 0
                                opponentCombo++
                            }
                        }
                    }
                }
                is S45PacketTitle -> { // use this to determine who won the duel
                    if (mc.theWorld != null) {
                        TimeUtils.setTimeout(fun () {
                            if (packet.message != null) {
                                val unformatted = packet.message.unformattedText.lowercase()
                                if (unformatted.contains("GAGNANT!") && mc.thePlayer != null) {
                                    var winner = ""
                                    var loser = ""
                                    var iWon = false

                                    val p = ChatUtils.removeFormatting(packet.message.unformattedText).split("won")[0].trim()
                                    if (unformatted.contains(mc.thePlayer.displayNameString.lowercase())) {
                                        Session.wins++
                                        winner = mc.thePlayer.displayNameString
                                        loser = lastOpponentName
                                        iWon = true
                                    } else {
                                        Session.losses++
                                        winner = p
                                        loser = mc.thePlayer.displayNameString
                                        iWon = false
                                    }

                                    ChatUtils.info(Session.getSession())

                                    if (!iWon) {
                                        TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(1000, 2000))
                                    }

                                    if ((DuckDueller.config?.disconnectAfterGames ?: 0) > 0) {
                                        if (Session.wins + Session.losses >= DuckDueller.config?.disconnectAfterGames!!) {
                                            ChatUtils.info("Played ${DuckDueller.config?.disconnectAfterGames} games, disconnecting...")
                                            TimeUtils.setTimeout(fun () {
                                                ChatUtils.sendAsPlayer("/l duels")
                                                TimeUtils.setTimeout(fun () {
                                                    toggle()
                                                    disconnect()
                                                }, RandomUtils.randomIntInRange(2300, 5000))
                                            }, RandomUtils.randomIntInRange(900, 1700))
                                        }
                                    }

                                    if ((DuckDueller.config?.disconnectAfterMinutes ?: 0) > 0) {
                                        if (System.currentTimeMillis() - Session.startTime >= DuckDueller.config?.disconnectAfterMinutes!! * 60 * 1000) {
                                            ChatUtils.info("Played for ${DuckDueller.config?.disconnectAfterMinutes} minutes, disconnecting...")
                                            TimeUtils.setTimeout(fun () {
                                                ChatUtils.sendAsPlayer("/l duels")
                                                TimeUtils.setTimeout(fun () {
                                                    toggle()
                                                    disconnect()
                                                }, RandomUtils.randomIntInRange(2300, 5000))
                                            }, RandomUtils.randomIntInRange(900, 1700))
                                        }
                                    }

                                    if (DuckDueller.config?.sendWebhookMessages == true) {
                                        if (DuckDueller.config?.webhookURL != "") {
                                            val duration = StateManager.lastGameDuration / 1000

                                            // Send the webhook embed
                                            val fields = WebHook.buildFields(arrayListOf(
                                                mapOf("name" to "Winner", "value" to winner, "inline" to "true"), 
                                                mapOf("name" to "Loser", "value" to loser, "inline" to "true"), 
                                                mapOf("name" to "Bot Started", "value" to "<t:${(Session.startTime / 1000).toInt()}:R>", "inline" to "false")
                                            ))
                                            val footer = WebHook.buildFooter(ChatUtils.removeFormatting(Session.getSession()), "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
                                            val author = WebHook.buildAuthor("Duck Dueller - ${getName()}", "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
                                            val thumbnail = WebHook.buildThumbnail("https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")

                                            WebHook.sendEmbed(
                                                DuckDueller.config?.webhookURL!!,
                                                WebHook.buildEmbed("${if (iWon) ":smirk:" else ":confused:"} Game ${if (iWon) "WON" else "LOST"}!", 
                                                    "Game Duration: `${duration}`s", fields, footer, author, thumbnail, 
                                                    if (iWon) 0x66ed8a else 0xed6d66))
                                        } else {
                                            ChatUtils.error("Webhook URL hasn't been set!")
                                        }
                                    }
                                }
                            }
                        }, 1000)
                    }
                }
            }
        }
    }

    @SubscribeEvent
    fun onAttackEntityEvent(ev: AttackEntityEvent) {
        if (toggled() && ev.entity == mc.thePlayer) {
            attackedID = ev.target.entityId
        }
    }

    @SubscribeEvent
    fun onClientTick(ev: ClientTickEvent) {
        registerPacketListener()
        if (toggled) {
            onTick()

            if (StateManager.state != StateManager.States.PLAYING) {
                ticksSinceGameStart++
                if (ticksSinceGameStart / 20 > (DuckDueller.config?.rqNoGame ?: 30)) {
                    ticksSinceGameStart = 0
                    joinGame()
                }
            } else {
                ticksSinceGameStart = 0
            }

            if (mc.thePlayer != null && opponent != null) {
                ticksSinceHit++

                val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent)

                if (distance > 5 && (combo != 0 || opponentCombo != 0)) {
                    combo = 0
                    opponentCombo = 0
                    ChatUtils.info("combo reset")
                }
            }
        }

        if (KeyBindings.toggleBotKeyBinding.isPressed) {
            toggle()
            ChatUtils.info("KIRA has been toggled ${if (toggled()) "${EnumChatFormatting.GREEN}on" else "${EnumChatFormatting.RED}off"}")
            if (toggled()) {
                ChatUtils.info("Current selected bot: ${EnumChatFormatting.GREEN}${getName()}")
                joinGame()
                Session.startTime = System.currentTimeMillis()
            }
        }
    }

    @SubscribeEvent
    fun onChat(ev: ClientChatReceivedEvent) {
        val unformatted = ev.message.unformattedText
        if (toggled() && mc.thePlayer != null) {

            if (unformatted.contains("The game starts in 1 second!")) {
                beforeStart()
            }

            if (unformatted.contains("Opponent:")) {
                gameStart()
            }

            if (unformatted.contains("Melee") && !calledGameEnd) {
                calledGameEnd = true
                gameEnd()
            }

            // Failsafe
            if (unformatted.lowercase().contains("something went wrong trying") || unformatted.lowercase().contains("please don't spam the command")) {
                TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(6000, 8000))
            } else if (unformatted.contains("A disconnect occurred in your connection, so you were put")) {
                Movement.clearAll()
                Mouse.stopLeftAC()
                TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(6000, 8000))
            }
        }

        if (unformatted.contains("Your new API key is ")) {
            val key = ev.message.unformattedText.split("Your new API key is ")[1]
            DuckDueller.config?.apiKey = key
            DuckDueller.config?.writeData()
            ChatUtils.info("Saved API Key!")
        }
    }

    @SubscribeEvent
    fun onJoinWorld(ev: EntityJoinWorldEvent) {
        if (DuckDueller.mc.thePlayer != null && ev.entity == DuckDueller.mc.thePlayer) {
            if (toggled()) {
                resetVars()
                LobbyMovement.stop()
                Movement.clearAll()
                Combat.stopRandomStrafe()
                Mouse.stopLeftAC()
                calledGameEnd = false
            }
        }
    }

    @SubscribeEvent
    fun onConnect(ev: ClientConnectedToServerEvent) {
        if (toggled()) {
            println("Reconnect successful!")

            val author = WebHook.buildAuthor("Duck Dueller - ${getName()}", "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
            val thumbnail = WebHook.buildThumbnail("https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")

            WebHook.sendEmbed(
                DuckDueller.config?.webhookURL!!,
                WebHook.buildEmbed("Reconnected!", "The bot successfully reconnected!", com.google.gson.JsonArray(), com.google.gson.JsonObject(), author, thumbnail, 0x66ed8a))

            reconnectTimer?.cancel()
            TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(6000, 8000))
        }
    }

    @SubscribeEvent
    fun onDisconnect(ev: ClientDisconnectionFromServerEvent) {
        if (toggled()) { // well that wasn't supposed to happen, try and reconnect
            println("Disconnected from server, reconnecting...")

            val author = WebHook.buildAuthor("Duck Dueller - ${getName()}", "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
            val thumbnail = WebHook.buildThumbnail("https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")

            WebHook.sendEmbed(
                DuckDueller.config?.webhookURL!!,
                WebHook.buildEmbed("Disconnected!", "The bot was disconnected! Attempting to reconnect...", com.google.gson.JsonArray(), com.google.gson.JsonObject(), author, thumbnail, 0xed6d66))

            TimeUtils.setTimeout(fun () {
                reconnectTimer = TimeUtils.setInterval(this::reconnect, 0, 30000)
            }, RandomUtils.randomIntInRange(5000, 7000))
        }
    }

    /********
     * Private Methods
     ********/

    private fun resetVars() {
        calledFoundOpponent = false
        opponentTimer?.cancel()
        opponent = null
        combo = 0
        opponentCombo = 0
        ticksSinceHit = 0
        ticksSinceGameStart = 0
    }

    private fun gameStart() {
        if (toggled()) {
            onJoinGame()
            
            if (DuckDueller.config?.sendStartMessage == true) {
                TimeUtils.setTimeout(fun () {
                    ChatUtils.sendAsPlayer("/ac " + (DuckDueller.config?.startMessage ?: "glhf!"))
                }, DuckDueller.config?.startMessageDelay ?: 100)
            }

            val quickRefreshTimer = TimeUtils.setInterval(this::bakery, 200, 50)
            TimeUtils.setTimeout(fun () {
                quickRefreshTimer?.cancel()
                opponentTimer = TimeUtils.setInterval(this::bakery, 0, 500)
            }, quickRefresh)

            onGameStart()
        }
    }

    private fun gameEnd() {
        if (toggled()) {
            onGameEnd()
            resetVars()

            if (DuckDueller.config?.sendAutoGG == true) {
                TimeUtils.setTimeout(fun () {
                    ChatUtils.sendAsPlayer("/ac " + (DuckDueller.config?.ggMessage ?: "gg"))
                }, DuckDueller.config?.ggDelay ?: 100)
            }

            val delay = DuckDueller.config?.autoRqDelay ?: 2000
            if (DuckDueller.config?.fastRequeue == true) {
                TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(300, 500))
            } else {
                TimeUtils.setTimeout(this::joinGame, delay)
            }
        }
    }

    private fun bakery() {
        if (StateManager.state == StateManager.States.PLAYING) {
            val entity = EntityUtils.getOpponentEntity()
            if (entity != null) {
                opponent = entity
                lastOpponentName = opponent!!.displayNameString
                if (!calledFoundOpponent) {
                    calledFoundOpponent = true
                    onFoundOpponent()
                }
            }
        }
    }

    private fun joinGame(second: Boolean = false) {
        if (toggled() && StateManager.state != StateManager.States.PLAYING && !StateManager.gameFull) {
            if (StateManager.state == StateManager.States.GAME) {
                val paper = DuckDueller.config?.paperRequeue == true && Inventory.setInvItem("paper")
                if (paper) {
                    TimeUtils.setTimeout(fun () {
                        Mouse.rClick(RandomUtils.randomIntInRange(30, 70))
                        TimeUtils.setTimeout(fun () {
                            Mouse.rClick(RandomUtils.randomIntInRange(30, 70))
                        }, RandomUtils.randomIntInRange(100, 300))
                    }, RandomUtils.randomIntInRange(100, 300))
                } else {
                    if (second) {
                        TimeUtils.setTimeout(fun () {
                            ChatUtils.sendAsPlayer(queueCommand)
                        }, RandomUtils.randomIntInRange(100, 300))
                    } else {
                        TimeUtils.setTimeout(fun () {
                            joinGame(true)
                        }, RandomUtils.randomIntInRange(1000, 1400))
                    }
                }
            } else {
                TimeUtils.setTimeout(fun () {
                    ChatUtils.sendAsPlayer(queueCommand)
                }, RandomUtils.randomIntInRange(100, 300))
            }
        }
    }

    private fun disconnect() {
        if (mc.theWorld != null) {
            mc.addScheduledTask(fun () {
                mc.theWorld.sendQuittingDisconnectingPacket()
                mc.loadWorld(null)
                mc.displayGuiScreen(GuiMultiplayer(GuiMainMenu()))
            })
        }
    }

    private fun reconnect() {
        if (mc.theWorld == null) {
            if (mc.currentScreen is GuiMultiplayer) {
                mc.addScheduledTask(fun () {
                    println("Reconnecting...")
                    FMLClientHandler.instance().setupServerList()
                    FMLClientHandler.instance().connectToServer(mc.currentScreen, ServerData("hypixel", "mc.hypixel.net", false))
                })
            } else {
                if (mc.theWorld == null && mc.currentScreen !is GuiConnecting) {
                    mc.addScheduledTask(fun () {
                        println("Attempting to show new multiplayer screen...")
                        mc.displayGuiScreen(GuiMultiplayer(GuiMainMenu()))
                        reconnect()
                    })
                }
            }
        }
    }

    class PacketReader(private val container: BotBase) : SimpleChannelInboundHandler<Packet<*>>(false) {

        override fun channelRead0(ctx: ChannelHandlerContext?, msg: Packet<*>?) {
            if (msg != null) {
                container.onPacket(msg)
            }
            ctx?.fireChannelRead(msg)
        }

    }

    private fun registerPacketListener() {
        val pipeline = mc.thePlayer?.sendQueue?.networkManager?.channel()?.pipeline()
        if (pipeline != null && pipeline.get("${getName()}_packet_handler") == null && pipeline.get("packet_handler") != null) {
            pipeline.addBefore(
                "packet_handler",
                "${getName()}_packet_handler",
                PacketReader(this)
            )
            println("Registered ${getName()}_packet_handler")
        }
    }

}
