package chat.reachout.playfab;


import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.UiThread;

import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.Random;

import chat.reachout.playfab.base.ZerodataMultipathSubscriberVPNService;

public class MainActivity extends ConnectionsActivity  {
    /**
     * A set of background colors. We'll hash the authentication token we get from connecting to a
     * device to pick a color randomly from this list. Devices with the same background color are
     * talking to each other securely (with 1/COLORS.length chance of collision with another pair of
     * devices).
     */
    @ColorInt
    private static final int[] COLORS =
            new int[] {
                    0xFFF44336 /* red */,
                    0xFF9C27B0 /* deep purple */,
                    0xFF00BCD4 /* teal */,
                    0xFF4CAF50 /* green */,
                    0xFFFFAB00 /* amber */,
                    0xFFFF9800 /* orange */,
                    0xFF795548 /* brown */
            };

    /** If true, debug logs are shown on the device. */
    private static final boolean DEBUG = true;

    private static final String SERVICE_ID =
            "chat.reachout.playfab.SERVICE_ID";

    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

    /** Displays the previous state during animation transitions. */
    private TextView mPreviousStateView;

    /** Displays the current state. */
    private TextView mCurrentStateView;

    /** A running log of debug messages. Only visible when DEBUG=true. */
    private TextView mDebugLogView;

    /** A random UID used as this device's endpoint name. */
    private String mName = "1234567";

    /**
     * The state of the app. As the app changes states, the UI will update and advertising/discovery
     * will start/stop.
     */
    private State mState = State.UNKNOWN;

    ZerodataMultipathSubscriberVPNService mService;
    boolean mBound = false;

    /**
     * The background color of the 'CONNECTED' state. This is randomly chosen from the {@link #COLORS}
     * list, based off the authentication token.
     */
    @ColorInt
    private int mConnectedColor = COLORS[0];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPreviousStateView = (TextView) findViewById(R.id.previous_state);
        mCurrentStateView = (TextView) findViewById(R.id.current_state);

        mDebugLogView = (TextView) findViewById(R.id.debug_log);
        mDebugLogView.setVisibility(DEBUG ? View.VISIBLE : View.GONE);
        mDebugLogView.setMovementMethod(new ScrollingMovementMethod());

        mName = generateRandomName();

        ((TextView) findViewById(R.id.name)).setText(mName);

        // CONNECT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            findViewById(R.id.connect).setOnClickListener(v -> {
                Log.d("MainActivity", "********* Starting VPN Connection");
                Intent intent = VpnService.prepare(MainActivity.this);
                if (intent != null) {
                    Log.d("MainActivity", "********* Starting VPN Connection Intent Not null");
                    startActivityForResult(intent, 0);
                } else {
                    Log.d("MainActivity", "********* Starting VPN Connection Intent null");
                    onActivityResult(0, RESULT_OK, null);
                }
            });
        }
    }

    /** Defines callbacks for service binding, passed to bindService(). */
    private ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance.
            ZerodataMultipathSubscriberVPNService.LocalBinder binder = (ZerodataMultipathSubscriberVPNService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            //mService.setCallbacks(MainActivity.this); // register
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result == RESULT_OK) {
            Intent intent = getServiceIntent();
            startService(intent.setAction(ZerodataMultipathSubscriberVPNService.ACTION_CONNECT));
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
    }
    private Intent getServiceIntent() {
        return new Intent(this, ZerodataMultipathSubscriberVPNService.class);
    }

    @Override
    protected void onStart() {
        super.onStart();

        setState(State.SEARCHING);
    }

    @Override
    protected void onStop() {
        // After our Activity stops, we disconnect from Nearby Connections.
        setState(State.UNKNOWN);

        super.onStop();

        // Unbind from service
        if (mBound) {
            //.setCallbacks(null); // unregister
            unbindService(connection);
            mBound = false;
        }
    }

    /** {@see ConnectionsActivity#onReceive(Endpoint, Payload)} */
    @Override
    protected void onReceive(Endpoint endpoint, Payload payload) {
        if (payload.getType() == Payload.Type.BYTES) {
            Log.d("MainActivity", "******* Bytes Received " + payload.asBytes()[0]);
        }
    }

    private static String generateRandomName() {
        String name = "";
        Random random = new Random();
        for (int i = 0; i < 5; i++) {
            name += random.nextInt(10);
        }
        return name;
    }

    @Override
    protected void onEndpointDiscovered(Endpoint endpoint) {
        // We found an advertiser!
        stopDiscovering();
        connectToEndpoint(endpoint);
    }

    @Override
    protected void onConnectionInitiated(Endpoint endpoint, ConnectionInfo connectionInfo) {
        // A connection to another device has been initiated! We'll use the auth token, which is the
        // same on both devices, to pick a color to use when we're connected. This way, users can
        // visually see which device they connected with.
        mConnectedColor = COLORS[connectionInfo.getAuthenticationDigits().hashCode() % COLORS.length];

        // We accept the connection immediately.
        acceptConnection(endpoint);
    }

    @Override
    protected void onEndpointConnected(Endpoint endpoint) {
        Toast.makeText(this, getString(R.string.toast_connected, endpoint.getName()), Toast.LENGTH_SHORT).show();
        setState(State.CONNECTED);
    }

    @Override
    protected void onEndpointDisconnected(Endpoint endpoint) {
        Toast.makeText(
                this, getString(R.string.toast_disconnected, endpoint.getName()), Toast.LENGTH_SHORT)
                .show();
        setState(State.SEARCHING);
    }

    @Override
    protected void onConnectionFailed(Endpoint endpoint) {
        // Let's try someone else.
        if (getState() == State.SEARCHING) {
            startDiscovering();
        }
    }


    /**
     * Queries the phone's contacts for their own profile, and returns their name. Used when
     * connecting to another device.
     */
    @Override
    protected String getName() {
        return mName;
    }

    /** {@see ConnectionsActivity#getServiceId()} */
    @Override
    public String getServiceId() {
        return SERVICE_ID;
    }

    /** {@see ConnectionsActivity#getStrategy()} */
    @Override
    public Strategy getStrategy() {
        return STRATEGY;
    }

    /**
     * State has changed.
     *
     * @param oldState The previous state we were in. Clean up anything related to this state.
     * @param newState The new state we're now in. Prepare the UI for this state.
     */
    private void onStateChanged(State oldState, State newState) {

        // Update Nearby Connections to the new state.
        switch (newState) {
            case SEARCHING:
                disconnectFromAllEndpoints();
                startDiscovering();
                startAdvertising();
                break;
            case CONNECTED:
                stopDiscovering();
                stopAdvertising();
                break;
            case UNKNOWN:
                stopAllEndpoints();
                break;
            default:
                // no-op
                break;
        }

        // Update the UI.
        switch (oldState) {
            case UNKNOWN:
                // Unknown is our initial state. Whatever state we move to,
                // we're transitioning forwards.
                transitionForward(oldState, newState);
                break;
            case SEARCHING:
                switch (newState) {
                    case UNKNOWN:
                        transitionBackward(oldState, newState);
                        break;
                    case CONNECTED:
                        transitionForward(oldState, newState);
                        break;
                    default:
                        // no-op
                        break;
                }
                break;
            case CONNECTED:
                // Connected is our final state. Whatever new state we move to,
                // we're transitioning backwards.
                transitionBackward(oldState, newState);
                break;
        }
    }

    /** Transitions from the old state to the new state with an animation implying moving forward. */
    @UiThread
    private void transitionForward(State oldState, final State newState) {
        mPreviousStateView.setVisibility(View.VISIBLE);
        mCurrentStateView.setVisibility(View.VISIBLE);

        updateTextView(mPreviousStateView, oldState);
        updateTextView(mCurrentStateView, newState);
    }

    /** Transitions from the old state to the new state with an animation implying moving backward. */
    @UiThread
    private void transitionBackward(State oldState, final State newState) {
        mPreviousStateView.setVisibility(View.VISIBLE);
        mCurrentStateView.setVisibility(View.VISIBLE);

        updateTextView(mCurrentStateView, oldState);
        updateTextView(mPreviousStateView, newState);
    }

    /** Updates the {@link TextView} with the correct color/text for the given {@link State}. */
    @UiThread
    private void updateTextView(TextView textView, State state) {
        switch (state) {
            case SEARCHING:
                textView.setBackgroundResource(R.color.state_searching);
                textView.setText(R.string.status_searching);
                break;
            case CONNECTED:
                textView.setBackgroundColor(mConnectedColor);
                textView.setText(R.string.status_connected);
                break;
            default:
                textView.setBackgroundResource(R.color.state_unknown);
                textView.setText(R.string.status_unknown);
                break;
        }
    }


    /**
     * The state has changed. I wonder what we'll be doing now.
     *
     * @param state The new state.
     */
    private void setState(State state) {
        if (mState == state) {
            logW("State set to " + state + " but already in that state");
            return;
        }

        logD("State set to " + state);
        State oldState = mState;
        mState = state;
        onStateChanged(oldState, state);
    }

    /** @return The current state. */
    private State getState() {
        return mState;
    }


    /** States that the UI goes through. */
    public enum State {
        UNKNOWN,
        SEARCHING,
        CONNECTED
    }
}