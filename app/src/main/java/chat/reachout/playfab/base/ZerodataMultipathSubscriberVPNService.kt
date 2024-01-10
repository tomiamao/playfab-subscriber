package chat.reachout.playfab.base

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Pair
import android.widget.Toast
import androidx.core.app.NotificationCompat
import chat.reachout.playfab.MultiPathActivity
import chat.reachout.playfab.R
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.util.concurrent.atomic.AtomicReference

class ZerodataMultipathSubscriberVPNService : VpnService(), Handler.Callback{

    inner class LocalBinder : Binder() {
        fun getService(): ZerodataMultipathSubscriberVPNService = this@ZerodataMultipathSubscriberVPNService
    }


    private var mHandler: Handler? = null
    private val mConnectingThread = AtomicReference<Thread>()
    private val mConnection = AtomicReference<Connection>()
    private var mConfigureIntent: PendingIntent? = null

    private val binder = LocalBinder()
    private class Connection(thread: Thread, pfd: ParcelFileDescriptor) : Pair<Thread, ParcelFileDescriptor>(thread, pfd)

    private val listeners: MutableList<ZeroDataMultiPathConnector.ZeroDataMultiPathListener> = ArrayList()
    private var zeroDataMultiPathConnector: ZeroDataMultiPathConnector? = null
    var pipe: Pipe = Pipe.open()

    var sink : Pipe.SinkChannel = pipe.sink()
    var source : Pipe.SourceChannel = pipe.source()


    override fun onCreate() {
        Log.d("MainActivity", "********* Starting VPN Connection - ONCREATE")
        if (mHandler == null) {
            mHandler = Handler(this)
        }
        mConfigureIntent = PendingIntent.getActivity(this, 0, Intent(this, MultiPathActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (intent != null && ACTION_DISCONNECT == intent.action) {
            Log.d("MainActivity", "********* Starting VPN Connection - ACTION: DISCONNECT")
            disconnect()
            Service.START_NOT_STICKY
        } else {
            connect()
            Log.d("MainActivity", "********* Starting VPN Connection - ACTION: CONNECT")
            Service.START_STICKY
        }
    }

    override fun onDestroy() {
        disconnect()
    }

    override fun handleMessage(message: Message): Boolean {
        Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show()
        if (message.what != R.string.disconnected) {
            updateForegroundNotification(message.what)
        }
        return true
    }

    private fun connect() {
        updateForegroundNotification(R.string.connecting)
        mHandler?.sendEmptyMessage(R.string.connecting)
        source.configureBlocking(false)
        startConnection(ZerodataVPNConnection(this, source))
    }

    private fun startConnection(connection: ZerodataVPNConnection) {
        val thread = Thread(connection, "ZerodataVPNThread")
        setConnectingThread(thread)
        connection.setConfigureIntent(mConfigureIntent)
        connection.setOnEstablishListener { tunInterface ->
            mHandler?.sendEmptyMessage(R.string.connected)
            mConnectingThread.compareAndSet(thread, null)
            setConnection(Connection(thread, tunInterface))
        }
        thread.start()
    }

    private fun setConnectingThread(thread: Thread?) {
        val oldThread = mConnectingThread.getAndSet(thread)
        oldThread?.interrupt()
    }

    private fun setConnection(connection: Connection?) {
        val oldConnection = mConnection.getAndSet(connection)
        oldConnection?.let {
            try {
                it.first.interrupt()
                it.second.close()
            } catch (e: IOException) {
                Log.e(TAG, "Closing VPN interface", e)
            }
        }
    }

    fun sendPacketPayload(payload: ByteArray) {
        zeroDataMultiPathConnector?.sendToGateWayNode(Payload.fromBytes(payload))
    }

    private fun disconnect() {
        mHandler?.sendEmptyMessage(R.string.disconnected)
        setConnectingThread(null)
        setConnection(null)
        stopForeground(true)
    }

    private fun updateForegroundNotification(message: Int) {
        val NOTIFICATION_CHANNEL_ID = "Multipath"
        val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationManager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(1, NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentText(getString(message))
                .setContentIntent(mConfigureIntent)
                .build())
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun initializeConnector(localEndpointName: String, serviceId: String, strategy: Strategy) {
        zeroDataMultiPathConnector = ZeroDataMultiPathConnector.getInstance(
            applicationContext,
            localEndpointName,
            serviceId,
            strategy,
            ZeroDataMultiPathConnector.Endpoint.EndpointType.SUBSCRIBER_NODE,
            object : ZeroDataMultiPathConnector.ZeroDataMultiPathListener {
                override fun onAdvertisingStarted() {
//                    Log.d(
//                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
//                        "On Advertising Started"
//                    )
                    listeners.forEach { it.onAdvertisingStarted() }

                    startDiscovery()
                }

                override fun onAdvertisingFailed() {
//                    Log.d(
//                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
//                        "On Advertising Failed"
//                    )
                    listeners.forEach { it.onAdvertisingFailed() }

                }

                override fun onDiscoveryStarted() {
//                    Log.d(
//                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
//                        "On Discovery Started"
//                    )
                    listeners.forEach { it.onDiscoveryStarted() }
                }

                override fun onDiscoveryFailed(throwable: Throwable) {
//                    Log.d(
//                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
//                        "On Discovery Failed: ${throwable?.message}"
//                    )
                    listeners.forEach { it.onDiscoveryFailed(throwable) }
                }

                override fun onEndpointConnected(endpoint: ZeroDataMultiPathConnector.Endpoint) {
//                    Log.d(
//                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
//                        "On Endpoint Connected: ${endpoint.id}"
//                    )

                    //zeroDataMultiPathConnector?.send(Payload.fromBytes("Hello from Gateway ${endpoint.name}".toByteArray()))
                    listeners.forEach { it.onEndpointConnected(endpoint) }
                }

                override fun onEndpointConnectionInitiated(
                    endpoint: ZeroDataMultiPathConnector.Endpoint,
                    connectionInfo: ConnectionInfo
                ) {
//                    Log.d(
//                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
//                        "On Connection Initiated ${endpoint.id} ${connectionInfo.endpointName}"
//                    )
                    listeners.forEach { it.onEndpointConnectionInitiated(endpoint, connectionInfo) }
                    zeroDataMultiPathConnector?.acceptConnection(endpoint)
                }

                override fun onEndpointDisconnected(endpoint: ZeroDataMultiPathConnector.Endpoint) {
                    Log.d(
                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
                        "On Endpoint Disconnected: ${endpoint.id}"
                    )
                    listeners.forEach { it.onEndpointDisconnected(endpoint) }
                }

                override fun onEndpointDiscovered(endpoint: ZeroDataMultiPathConnector.Endpoint) {
                    if (zeroDataMultiPathConnector!!.isConnectedToEndpoint(endpoint.id)) {
                        Log.d(
                            ZerodataMultipathSubscriberVPNService::class.java.simpleName,
                            "Won't connect because ${endpoint.id} is already connected"
                        )
                        return
                    }

                    if (zeroDataMultiPathConnector!!.isSelf(endpoint.name)) {
                        Log.d(
                            ZerodataMultipathSubscriberVPNService::class.java.simpleName,
                            "Won't connect because ${endpoint.name} is the local node"
                        )
                        return
                    }

                    Log.d(
                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
                        "On Endpoint Discovered, connecting to: ${endpoint.name}"
                    )

                    zeroDataMultiPathConnector?.initiateConnectionToEndpoint(endpoint)
                    listeners.forEach { it.onEndpointDiscovered(endpoint) }
                }

                override fun onPayloadReceived(endpointId: String, payload: Payload) {
//                    Log.d(
//                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
//                        "On Payload Received: ${payload.asBytes()?.size} ${Thread.currentThread().name}"
//                    )
                    val buffer = ByteBuffer.wrap(payload.asBytes())
                    buffer.rewind()
                    Log.d(
                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
                        "On Payload Written: ${buffer.remaining()} ${Thread.currentThread().name}"
                    )
                    sink.write(buffer)
                    listeners.forEach { it.onPayloadReceived(endpointId, payload) }
                }

                override fun onPayloadTransferUpdate(
                    endpointId: String,
                    update: PayloadTransferUpdate
                ) {
//                    Log.d(
//                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
//                        "On Payload Transfer Updated $endpointId"
//                    )
                    listeners.forEach { it.onPayloadTransferUpdate(endpointId, update) }
                }

                override fun onPayloadSent(id: String?) {
                    Log.d(ZerodataMultipathSubscriberVPNService::class.java.simpleName, "On Payload Sent $id")
                    listeners.forEach { it.onPayloadSent(id) }
                }

                override fun onPayloadFailed(throwable: Throwable?, id: String?) {
                    Log.d(
                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
                        "On Payload Failed $id ${throwable?.message}"
                    )
                    listeners.forEach { it.onPayloadFailed(throwable, id) }
                }

                override fun onEndpointConnectionFailed(throwable: Throwable?) {
//                    Log.d(
//                        ZerodataMultipathSubscriberVPNService::class.java.simpleName,
//                        "On Endpoint connection Failed ${throwable?.message}"
//                    )
                    listeners.forEach { it.onEndpointConnectionFailed(throwable) }
                }
            }
        )
    }

    fun registerListener(listener: ZeroDataMultiPathConnector.ZeroDataMultiPathListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: ZeroDataMultiPathConnector.ZeroDataMultiPathListener) {
        listeners.remove(listener)
    }

    fun sendMessage(text: String) {
        zeroDataMultiPathConnector?.sendToGateWayNode(Payload.fromBytes(text.toByteArray()))
    }

    fun startAdvertising() {
        zeroDataMultiPathConnector?.startAdvertising()
    }

    fun startDiscovery() {
        zeroDataMultiPathConnector?.startDiscovery()
    }

    companion object {
        private val TAG = ZerodataMultipathSubscriberVPNService::class.java.simpleName
        const val ACTION_CONNECT = "com.simplifyd.zerodatavpn.START"
        const val ACTION_DISCONNECT = "com.simplifyd.zerodatavpn.STOP"
    }
}
