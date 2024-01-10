package chat.reachout.playfab

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import chat.reachout.playfab.base.ZeroDataMultiPathConnector
import chat.reachout.playfab.base.ZeroDataMultiPathGateWayNodeService
import chat.reachout.playfab.base.ZerodataMultipathSubscriberVPNService
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy


class MultiPathActivity : Activity(), ZeroDataMultiPathConnector.ZeroDataMultiPathListener {

    private lateinit var aSwitch: Switch
    private lateinit var editText: EditText
    private lateinit var connectTextView: TextView
    private lateinit var messageTextView: TextView
    private lateinit var errorTextView: TextView
    private lateinit var sendMessage: Button
    private lateinit var connectButton: Button

    private var gateWayService: ZeroDataMultiPathGateWayNodeService? = null
    private val gateWayServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ZeroDataMultiPathGateWayNodeService.LocalBinder
            gateWayService = binder.service
            isBound = true
            gateWayService?.registerListener(this@MultiPathActivity)

            gateWayService?.initializeConnector(
                ZeroDataMultiPathConnector.getDeviceIdentifier(
                    this@MultiPathActivity,
                    ZeroDataMultiPathConnector.Endpoint.EndpointType.GATEWAY_NODE
                ),
                "Multipath Test",
                Strategy.P2P_CLUSTER
            )
            gateWayService?.startAdvertising()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    private var subscriberService: ZerodataMultipathSubscriberVPNService? = null
    private val subscriberServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            className: ComponentName,
            service: IBinder
        ) {
            val binder = service as ZerodataMultipathSubscriberVPNService.LocalBinder
            subscriberService = binder.getService()
            isBound = true

            subscriberService?.registerListener(this@MultiPathActivity)

            subscriberService?.initializeConnector(
                ZeroDataMultiPathConnector.getDeviceIdentifier(
                    this@MultiPathActivity,
                    ZeroDataMultiPathConnector.Endpoint.EndpointType.SUBSCRIBER_NODE
                ),
                "Multipath Test",
                Strategy.P2P_CLUSTER
            )
            subscriberService?.startAdvertising()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    private var isBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multi_path)

        aSwitch = findViewById(R.id.switch_id)
        editText = findViewById(R.id.editor)
        connectTextView = findViewById(R.id.connect)
        messageTextView = findViewById(R.id.text)
        errorTextView = findViewById(R.id.error)
        sendMessage = findViewById(R.id.send_message)
        connectButton = findViewById(R.id.connect_btn)

        sendMessage.setOnClickListener {
            gateWayService?.sendMessage(editText.text.toString())
            editText.setText("")
        }

        connectButton.setOnClickListener {
            if (aSwitch.isChecked) {
                startGateWayService()
            } else {
                startSubscriberService()
            }
        }


    }

    private fun startGateWayService() {
        val intent = Intent(this, ZeroDataMultiPathGateWayNodeService::class.java)
        bindService(intent, gateWayServiceConnection, BIND_AUTO_CREATE)
    }

    private fun startSubscriberService() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            Log.d("MainActivity", "********* Starting VPN Connection Intent Not null")
            startActivityForResult(intent, 0)
        } else {
            Log.d("MainActivity", "********* Starting VPN Connection Intent null")
            onActivityResult(0, RESULT_OK, null)
        }
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (result == RESULT_OK) {
            val intent = getServiceIntent()
            startService(intent.setAction(ZerodataMultipathSubscriberVPNService.ACTION_CONNECT))
            bindService(intent, subscriberServiceConnection, BIND_AUTO_CREATE)
        }
    }

    private fun getServiceIntent(): Intent {
        return Intent(this, ZerodataMultipathSubscriberVPNService::class.java)
    }

    override fun onStop() {
        super.onStop()
//        if (isBound) {
//            gateWayService?.unregisterListener(this)
//            unbindService(gateWayServiceConnection)
//            isBound = false
//        }
    }

    override fun onAdvertisingStarted() {
//        Log.d(MainActivity::class.java.simpleName, "On Advertising Started")
        errorTextView.text = errorTextView.text.toString() + "\n" + "On Advertising Started "
    }

    override fun onAdvertisingFailed() {
//        Log.d(MainActivity::class.java.simpleName, "On Advertising Failed")
        errorTextView.text = errorTextView.text.toString() + "\n" + "On Advertising Failed "
    }

    override fun onDiscoveryStarted() {
//        Log.d(MainActivity::class.java.simpleName, "On Discovery Started")
        errorTextView.text = errorTextView.text.toString() + "\n" + "On Discovery Started "
    }

    override fun onDiscoveryFailed(throwable: Throwable?) {
//        Log.d(MainActivity::class.java.simpleName, "On Discovery Failed $throwable")
        errorTextView.text =
            errorTextView.text.toString() + "\n" + "On Discovery Failed " + throwable?.message
    }

    override fun onEndpointConnected(endpoint: ZeroDataMultiPathConnector.Endpoint?) {
//        Log.d(MainActivity::class.java.simpleName, "On Endpoint Connected")
        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Endpoint Connected " + endpoint?.name
    }

    override fun onEndpointDisconnected(endpoint: ZeroDataMultiPathConnector.Endpoint?) {
//        Log.d(MainActivity::class.java.simpleName, "On Endpoint Disconnected")
        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Endpoint Disconnected " + endpoint?.name
    }

    override fun onEndpointDiscovered(endpoint: ZeroDataMultiPathConnector.Endpoint?) {
        //Log.d(MainActivity::class.java.simpleName, "On Endpoint Discovered")
        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Endpoint Discovered " + endpoint?.name
    }

    override fun onPayloadReceived(endpointId: String, payload: Payload) {
//        Log.d(
//            MainActivity::class.java.simpleName,
//            "On Payload Received: ${String(payload.asBytes()!!)}"
//        )

        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Payload Received " + String(payload.asBytes()!!)
    }

    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        //Log.d(MainActivity::class.java.simpleName, "On Payload Transfer Updated $endpointId")
        //messageTextView.text =
            //messageTextView.text.toString() + "\n" + "On Payload Transfer Updated " + endpointId
    }

    override fun onPayloadSent(id: String?) {
//        Log.d(MainActivity::class.java.simpleName, "On Payload Sent $id")
        messageTextView.text = messageTextView.text.toString() + "\n" + "On Payload Sent " + id
    }

    override fun onPayloadFailed(throwable: Throwable?, id: String?) {
//        Log.d(MainActivity::class.java.simpleName, "On Payload Failed $id $throwable")
        errorTextView.text =
            errorTextView.text.toString() + "\n" + "On Payload Failed " + id + " " + throwable?.message
    }

    override fun onEndpointConnectionInitiated(
        endpoint: ZeroDataMultiPathConnector.Endpoint?,
        connectionInfo: ConnectionInfo
    ) {
//        Log.d(
//            MainActivity::class.java.simpleName,
//            "On Connection Initiated ${endpoint?.id} ${connectionInfo.endpointName}"
//        )
        messageTextView.text =
            messageTextView.text.toString() + "\n" + "On Connection Initiated " + { endpoint?.id } + " " + connectionInfo.endpointName
    }

    override fun onEndpointConnectionFailed(throwable: Throwable?) {
//        Log.d(
//            MainActivity::class.java.simpleName,
//            "On Endpoint connection Failed ${throwable?.message}"
//        )
        errorTextView.text =
            errorTextView.text.toString() + "\n" + "On Endpoint connection Failed ${throwable?.message}"
    }

}