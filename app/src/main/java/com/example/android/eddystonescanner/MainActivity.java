package com.example.android.eddystonescanner;

import android.app.ListActivity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import java.util.ArrayList;


public class MainActivity extends ListActivity implements
        ServiceConnection, EddystoneScannerService.OnBeaconEventListener {
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int EXPIRE_TIMEOUT = 5000;
    private static final int EXPIRE_TASK_PERIOD = 1000;

    private EddystoneScannerService mService;
    private ArrayAdapter<SampleBeacon> mAdapter;
    private ArrayList<SampleBeacon> mAdapterItems;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAdapterItems = new ArrayList<>();
        mAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1,
                mAdapterItems);

        setListAdapter(mAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkBluetoothStatus()) {
            Intent intent = new Intent(this, EddystoneScannerService.class);
            bindService(intent, this, BIND_AUTO_CREATE);

            mHandler.post(mPruneTask);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mPruneTask);

        mService.setBeaconEventListener(null);
        unbindService(this);
    }

    /* This task checks for beacons we haven't seen in awhile */
    private Handler mHandler = new Handler();
    private Runnable mPruneTask = new Runnable() {
        @Override
        public void run() {
            final ArrayList<SampleBeacon> expiredBeacons = new ArrayList<>();
            final long now = System.currentTimeMillis();
            for (SampleBeacon beacon : mAdapterItems) {
                long delta = now - beacon.lastDetectedTimestamp;
                if (delta >= EXPIRE_TIMEOUT) {
                    expiredBeacons.add(beacon);
                }
            }

            if (!expiredBeacons.isEmpty()) {
                Log.d(TAG, "Found " + expiredBeacons.size() + " expired");
                mAdapterItems.removeAll(expiredBeacons);
                mAdapter.notifyDataSetChanged();
            }

            mHandler.postDelayed(this, EXPIRE_TASK_PERIOD);
        }
    };

    /* Verify Bluetooth Support */
    private boolean checkBluetoothStatus() {
        BluetoothManager manager =
                (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();
        /*
         * We need to enforce that Bluetooth is first enabled, and take the
         * user to settings to enable it if they have not done so.
         */
        if (adapter == null || !adapter.isEnabled()) {
            //Bluetooth is disabled
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            finish();
            return false;
        }

        /*
         * Check for Bluetooth LE Support.  In production, our manifest entry will keep this
         * from installing on these devices, but this will allow test devices or other
         * sideloads to report whether or not the feature exists.
         */
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "No LE Support.", Toast.LENGTH_SHORT).show();
            finish();
            return false;
        }

        return true;
    }

    /* Handle connection events to the discovery service */
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        Log.d(TAG, "Connected to scanner service");
        mService = ((EddystoneScannerService.LocalBinder) service).getService();
        mService.setBeaconEventListener(this);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.d(TAG, "Disconnected from scanner service");
        mService = null;
    }

    /* Handle callback events from the discovery service */
    @Override
    public void onBeaconIdentifier(String deviceAddress, int rssi, String instanceId) {
        final long now = System.currentTimeMillis();
        for (SampleBeacon item : mAdapterItems) {
            if (instanceId.equals(item.id)) {
                //Already have this one, make sure device info is up to date
                item.update(deviceAddress, rssi, now);
                mAdapter.notifyDataSetChanged();
                return;
            }
        }

        //New beacon, add it
        SampleBeacon beacon =
                new SampleBeacon(deviceAddress, rssi, instanceId, now);
        mAdapterItems.add(beacon);
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onBeaconTelemetry(String deviceAddress, float battery, float temperature) {
        for (SampleBeacon item : mAdapterItems) {
            if (deviceAddress.equals(item.deviceAddress)) {
                //Found it, update voltage
                item.battery = battery;
                item.temperature = temperature;
                mAdapter.notifyDataSetChanged();
                return;
            }
        }
    }
}
