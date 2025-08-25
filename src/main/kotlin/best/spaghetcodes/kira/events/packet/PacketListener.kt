package best.spaghetcodes.kira.events.packet

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import net.minecraft.network.Packet
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent

class PacketListener : ChannelDuplexHandler() {

    @Throws(Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        var obj: Any? = msg
        var get = true

        if (obj is Packet<*>) {
            val inPacket = (obj as Packet<*>?)?.let { PacketEvent.Incoming(it) }
            MinecraftForge.EVENT_BUS.post(inPacket)

            if (inPacket?.isCanceled == true) {
                get = false
            }
            obj = inPacket?.getPacket()
        }
        if (get) super.channelRead(ctx, obj)
    }

    @Throws(Exception::class)
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        var obj: Any? = msg
        var send = true

        if (obj is Packet<*>) {
            val outPacket = (obj as Packet<*>?)?.let { PacketEvent.Outgoing(it) }
            MinecraftForge.EVENT_BUS.post(outPacket)

            if (outPacket?.isCanceled == true) {
                send = false
            }
            obj = outPacket?.getPacket()
        }
        if (send) super.write(ctx, obj, promise)
    }

    @SubscribeEvent
    fun joinEvent(event: FMLNetworkEvent.ClientConnectedToServerEvent) {
        if (event.manager.channel().pipeline().get("duelsbooster_packet_handler") == null) {
            event.manager.channel().pipeline().addBefore(
                "packet_handler",
                "kira_packet_handler",
                PacketListener()
            )
        }
    }
}
