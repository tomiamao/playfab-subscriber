package chat.reachout.playfab

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import chat.reachout.playfab.ZeroDataMultiPathConnector.*
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

class ZeroDataMultiPathService : Service() {

    private val binder: IBinder = LocalBinder()
    private val listeners: MutableList<ZeroDataMultiPathListener> = ArrayList()
    private var zeroDataMultiPathConnector: ZeroDataMultiPathConnector? = null

    inner class LocalBinder : Binder() {
        val service: ZeroDataMultiPathService
            get() = this@ZeroDataMultiPathService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun initializeConnector(localEndpointName: String, serviceId: String, strategy: Strategy) {
        zeroDataMultiPathConnector = getInstance(
            applicationContext,
            localEndpointName,
            serviceId,
            strategy,
            Endpoint.EndpointType.GATEWAY_NODE,
            object : ZeroDataMultiPathListener {
                override fun onAdvertisingStarted() {
                    Log.d(ZeroDataMultiPathService::class.java.simpleName, "On Advertising Started")
                    listeners.forEach { it.onAdvertisingStarted() }

                    zeroDataMultiPathConnector?.startDiscovery()
                }

                override fun onAdvertisingFailed() {
                    Log.d(ZeroDataMultiPathService::class.java.simpleName, "On Advertising Failed")
                    listeners.forEach { it.onAdvertisingFailed() }

                }

                override fun onDiscoveryStarted() {
                    Log.d(ZeroDataMultiPathService::class.java.simpleName, "On Discovery Started")
                    listeners.forEach { it.onDiscoveryStarted() }
                }

                override fun onDiscoveryFailed(throwable: Throwable) {
                    Log.d(ZeroDataMultiPathService::class.java.simpleName, "On Discovery Failed: ${throwable?.message}")
                    listeners.forEach { it.onDiscoveryFailed(throwable) }
                }

                override fun onEndpointConnected(endpoint: Endpoint) {
                    Log.d(ZeroDataMultiPathService::class.java.simpleName, "On Endpoint Connected: ${endpoint.id}")

                    zeroDataMultiPathConnector?.send(Payload.fromBytes("Hello from ${endpoint.name}".toByteArray()))
                    listeners.forEach { it.onEndpointConnected(endpoint) }
                }

                override fun onEndpointConnectionInitiated(endpoint: Endpoint, connectionInfo: ConnectionInfo) {
                    Log.d(
                        MainActivity::class.java.simpleName,
                        "On Connection Initiated ${endpoint.id} ${connectionInfo.endpointName}"
                    )
                    listeners.forEach { it.onEndpointConnectionInitiated(endpoint, connectionInfo) }
                    zeroDataMultiPathConnector?.acceptConnection(endpoint)
                }

                override fun onEndpointDisconnected(endpoint: Endpoint) {
                    Log.d(ZeroDataMultiPathService::class.java.simpleName, "On Endpoint Disconnected: ${endpoint.id}")
                    listeners.forEach { it.onEndpointDisconnected(endpoint) }
                }

                override fun onEndpointDiscovered(endpoint: Endpoint) {
                    if (zeroDataMultiPathConnector!!.isConnectedToEndpoint(endpoint.id)) {
                        Log.d(MainActivity::class.java.simpleName, "Won't connect because ${endpoint.id} is already connected")
                        return
                    }

                    if (zeroDataMultiPathConnector!!.isSelf(endpoint.name)) {
                        Log.d(MainActivity::class.java.simpleName, "Won't connect because ${endpoint.name} is the local node")
                        return
                    }

                    Log.d(ZeroDataMultiPathService::class.java.simpleName, "On Endpoint Discovered, connecting to: ${endpoint.name}")

                    zeroDataMultiPathConnector?.initiateConnectionToEndpoint(endpoint)
                    listeners.forEach { it.onEndpointDiscovered(endpoint) }
                }

                override fun onPayloadReceived(endpointId: String, payload: Payload) {
                    Log.d(
                        MainActivity::class.java.simpleName,
                        "On Payload Received: ${String(payload.asBytes()!!)}"
                    )
                    listeners.forEach { it.onPayloadReceived(endpointId, payload) }
                }

                override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                    Log.d(MainActivity::class.java.simpleName, "On Payload Transfer Updated $endpointId")
                    listeners.forEach { it.onPayloadTransferUpdate(endpointId, update) }
                }

                override fun onPayloadSent(id: String?) {
                    Log.d(MainActivity::class.java.simpleName, "On Payload Sent $id")
                    listeners.forEach { it.onPayloadSent(id) }
                }

                override fun onPayloadFailed(throwable: Throwable?, id: String?) {
                    Log.d(MainActivity::class.java.simpleName, "On Payload Failed $id ${throwable?.message}")
                    listeners.forEach { it.onPayloadFailed(throwable, id) }
                }

                override fun onEndpointConnectionFailed(throwable: Throwable?) {
                    Log.d(MainActivity::class.java.simpleName, "On Endpoint connection Failed ${throwable?.message}")
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
