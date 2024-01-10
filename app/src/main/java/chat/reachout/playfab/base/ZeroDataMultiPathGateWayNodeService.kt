package chat.reachout.playfab.base

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import chat.reachout.playfab.Constants.TAG
import chat.reachout.playfab.MainActivity
import chat.reachout.playfab.base.ZeroDataMultiPathConnector.Endpoint
import chat.reachout.playfab.base.ZeroDataMultiPathConnector.Endpoint.EndpointType
import chat.reachout.playfab.base.ZeroDataMultiPathConnector.ZeroDataMultiPathListener
import chat.reachout.playfab.base.ZeroDataMultiPathConnector.getInstance
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.Arrays

class ZeroDataMultiPathGateWayNodeService : Service() {

    private val binder: IBinder = LocalBinder()
    private val listeners: MutableList<ZeroDataMultiPathListener> = ArrayList()
    private var tunnel: DatagramChannel? = null

    private var zeroDataMultiPathConnector: ZeroDataMultiPathConnector? = null

    inner class LocalBinder : Binder() {
        val service: ZeroDataMultiPathGateWayNodeService
            get() = this@ZeroDataMultiPathGateWayNodeService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()


        val thread = Thread(runnable)
        thread.start()
    }


    private val runnable = Runnable {
        try {
            tunnel = DatagramChannel.open()
            // Connect to the server.
            val serverAddress: SocketAddress = InetSocketAddress("102.221.184.33", 5000)
            tunnel?.connect(serverAddress)
            Log.i(ContentValues.TAG, "******* CONNECTED TO VPN HEADEND")
            val packet = ByteBuffer.allocate(32767)
            while (true) {
                val length = tunnel?.read(packet) ?: -1
                if (length > 0) {
                    Log.d(ContentValues.TAG, "************ Packet from VPN headed ENd received- routing: $length")
                    // Ignore control messages, which start with zero.
//                    if (packet[0].toInt() != 0) {
//
//                    }
                    // Ignore control messages, which start with zero.
//                    if (packet.get(0) != 0) {
//
//                    }

                    // Write the incoming packet to the output stream.
                    packet.flip()
                    val packetBytes = Arrays.copyOf(packet.array(), packet.remaining())
                    Log.d(
                        ContentValues.TAG,
                        "************ Packet size of bytes: ${packetBytes.size} $length"
                    )
                    // Write the incoming packet to the output stream which is the Nearby device.
                    zeroDataMultiPathConnector?.sendToSubscriberNode(
                        Payload.fromBytes(packetBytes)
                    )

                    packet.clear()
                }
            }
        } catch (e: SocketException) {
            Log.i(ContentValues.TAG, "Cannot use socket", e)
        } catch (e: Exception) {
            Log.i(ContentValues.TAG, "Cannot use socket", e)
        } finally {
            Log.i(ContentValues.TAG, "SHUTTING DOWN INTERFACE")
        }
    }

    fun initializeConnector(localEndpointName: String, serviceId: String, strategy: Strategy) {
        zeroDataMultiPathConnector = getInstance(
            applicationContext,
            localEndpointName,
            serviceId,
            strategy,
            EndpointType.SUBSCRIBER_NODE,
            object : ZeroDataMultiPathListener {
                override fun onAdvertisingStarted() {
                    Log.d(
                        ZeroDataMultiPathGateWayNodeService::class.java.simpleName,
                        "On Advertising Started"
                    )
                    listeners.forEach { it.onAdvertisingStarted() }

                    startDiscovery()
                }

                override fun onAdvertisingFailed() {
                    Log.d(
                        ZeroDataMultiPathGateWayNodeService::class.java.simpleName,
                        "On Advertising Failed"
                    )
                    listeners.forEach { it.onAdvertisingFailed() }

                }

                override fun onDiscoveryStarted() {
                    Log.d(
                        ZeroDataMultiPathGateWayNodeService::class.java.simpleName,
                        "On Discovery Started"
                    )
                    listeners.forEach { it.onDiscoveryStarted() }
                }

                override fun onDiscoveryFailed(throwable: Throwable) {
                    Log.d(
                        ZeroDataMultiPathGateWayNodeService::class.java.simpleName,
                        "On Discovery Failed: ${throwable.message}"
                    )
                    listeners.forEach { it.onDiscoveryFailed(throwable) }
                }

                override fun onEndpointConnected(endpoint: Endpoint) {
                    Log.d(
                        ZeroDataMultiPathGateWayNodeService::class.java.simpleName,
                        "On Endpoint Connected: ${endpoint.id}"
                    )

                    //zeroDataMultiPathConnector?.send(Payload.fromBytes("Hello from ${endpoint.name}".toByteArray()))
                    listeners.forEach { it.onEndpointConnected(endpoint) }
                }

                override fun onEndpointConnectionInitiated(
                    endpoint: Endpoint,
                    connectionInfo: ConnectionInfo
                ) {
                    Log.d(
                        MainActivity::class.java.simpleName,
                        "On Connection Initiated ${endpoint.id} ${connectionInfo.endpointName}"
                    )
                    listeners.forEach { it.onEndpointConnectionInitiated(endpoint, connectionInfo) }
                    zeroDataMultiPathConnector?.acceptConnection(endpoint)
                }

                override fun onEndpointDisconnected(endpoint: Endpoint) {
                    Log.d(
                        ZeroDataMultiPathGateWayNodeService::class.java.simpleName,
                        "On Endpoint Disconnected: ${endpoint.id}"
                    )
                    listeners.forEach { it.onEndpointDisconnected(endpoint) }
                }

                override fun onEndpointDiscovered(endpoint: Endpoint) {
                    if (zeroDataMultiPathConnector!!.isConnectedToEndpoint(endpoint.id)) {
                        Log.d(
                            MainActivity::class.java.simpleName,
                            "Won't connect because ${endpoint.id} is already connected"
                        )
                        return
                    }

                    if (zeroDataMultiPathConnector!!.isSelf(endpoint.name)) {
                        Log.d(
                            MainActivity::class.java.simpleName,
                            "Won't connect because ${endpoint.name} is the local node"
                        )
                        return
                    }

                    Log.d(
                        ZeroDataMultiPathGateWayNodeService::class.java.simpleName,
                        "On Endpoint Discovered, connecting to: ${endpoint.name}"
                    )

                    zeroDataMultiPathConnector?.initiateConnectionToEndpoint(endpoint)
                    listeners.forEach { it.onEndpointDiscovered(endpoint) }
                }

                override fun onPayloadReceived(endpointId: String, payload: Payload) {
                    Log.d(
                        MainActivity::class.java.simpleName,
                        "On Payload Received: ${String(payload.asBytes()!!)}"
                    )

                    Thread {
                        // background code
                        try {
                            val buffer = ByteBuffer.wrap(payload.asBytes())
                            val count: Int = tunnel?.write(buffer) ?: -1
                            Log.d(TAG, "************ Finished writing packet to UDP: $count")
                        } catch (e: SocketException) {
                            Log.e(ContentValues.TAG, "Cannot use socket", e)
                        } catch (e: java.lang.Exception) {
                            Log.e(ContentValues.TAG, "Cannot use socket", e)
                        } finally {
                            Log.i(ContentValues.TAG, "SHUTTING DOWN INTERFACE")
                        }
                    }.start()

                    listeners.forEach { it.onPayloadReceived(endpointId, payload) }
                }

                override fun onPayloadTransferUpdate(
                    endpointId: String,
                    update: PayloadTransferUpdate
                ) {
                    Log.d(
                        MainActivity::class.java.simpleName,
                        "On Payload Transfer Updated $endpointId"
                    )
                    listeners.forEach { it.onPayloadTransferUpdate(endpointId, update) }
                }

                override fun onPayloadSent(id: String?) {
                    Log.d(MainActivity::class.java.simpleName, "On Payload Sent $id")
                    listeners.forEach { it.onPayloadSent(id) }
                }

                override fun onPayloadFailed(throwable: Throwable?, id: String?) {
                    Log.d(
                        MainActivity::class.java.simpleName,
                        "On Payload Failed $id ${throwable?.message}"
                    )
                    listeners.forEach { it.onPayloadFailed(throwable, id) }
                }

                override fun onEndpointConnectionFailed(throwable: Throwable?) {
                    Log.d(
                        MainActivity::class.java.simpleName,
                        "On Endpoint connection Failed ${throwable?.message}"
                    )
                    listeners.forEach { it.onEndpointConnectionFailed(throwable) }
                }
            }
        )
    }

    fun registerListener(listener: ZeroDataMultiPathListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: ZeroDataMultiPathListener) {
        listeners.remove(listener)
    }

    fun sendMessage(text: String) {
        zeroDataMultiPathConnector?.send(Payload.fromBytes(text.toByteArray()))
    }

    fun startAdvertising() {
        zeroDataMultiPathConnector?.startAdvertising()
    }

    fun startDiscovery() {
        zeroDataMultiPathConnector?.startDiscovery()
    }
}
