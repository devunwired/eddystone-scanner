package com.example.android.eddystonescanner;

import android.app.Service;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;


public class EddystoneScannerService extends Service {
    private static final String TAG =
            EddystoneScannerService.class.getSimpleName();

    // …if you feel like making the log a bit noisier…
    private static boolean DEBUG_SCAN = false;

    // Eddystone service uuid (0xfeaa)
    private static final ParcelUuid UID_SERVICE =
            ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb");

    // Default namespace id for KST Eddystone beacons (d89bed6e130ee5cf1ba1)
    private static final byte[] NAMESPACE_FILTER = {
            0x00, //Frame type
            0x00, //TX power
            (byte)0xd8, (byte)0x9b, (byte)0xed, (byte)0x6e, (byte)0x13,
            (byte)0x0e, (byte)0xe5, (byte)0xcf, (byte)0x1b, (byte)0xa1,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    // Force frame type and namespace id to match
    private static final byte[] NAMESPACE_FILTER_MASK = {
            (byte)0xFF,
            0x00,
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
            (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    private static final byte[] TLM_FILTER = {
            0x20, //Frame type
            0x00, //Protocol version = 0
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00
    };

    // Force frame type and protocol to match
    private static final byte[] TLM_FILTER_MASK = {
            (byte)0xFF,
            (byte)0xFF,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00
    };

    // Eddystone frame types
    private static final byte TYPE_UID = 0x00;
    private static final byte TYPE_URL = 0x10;
    private static final byte TYPE_TLM = 0x20;

    //Callback interface for the UI
    public interface OnBeaconEventListener {
        void onBeaconIdentifier(String deviceAddress, int rssi, String instanceId);
        void onBeaconTelemetry(String deviceAddress, float battery, float temperature);
    }

    private BluetoothLeScanner mBluetoothLeScanner;
    private OnBeaconEventListener mBeaconEventListener;

    @Override
    public void onCreate() {
        super.onCreate();

        BluetoothManager manager =
                (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothLeScanner = manager.getAdapter().getBluetoothLeScanner();

        startScanning();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopScanning();
    }

    public void setBeaconEventListener(OnBeaconEventListener listener) {
        mBeaconEventListener = listener;
    }

    /* Using as a bound service to allow event callbacks */
    private LocalBinder mBinder = new LocalBinder();
    public class LocalBinder extends Binder {
        public EddystoneScannerService getService() {
            return EddystoneScannerService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /* Being scanning for Eddystone advertisers */
    private void startScanning() {
        ScanFilter beaconFilter = new ScanFilter.Builder()
                .setServiceUuid(UID_SERVICE)
                .setServiceData(UID_SERVICE, NAMESPACE_FILTER, NAMESPACE_FILTER_MASK)
                .build();

        ScanFilter telemetryFilter = new ScanFilter.Builder()
                .setServiceUuid(UID_SERVICE)
                .setServiceData(UID_SERVICE, TLM_FILTER, TLM_FILTER_MASK)
                .build();

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(beaconFilter);
        filters.add(telemetryFilter);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        mBluetoothLeScanner.startScan(filters, settings, mScanCallback);
        if (DEBUG_SCAN) Log.d(TAG, "Scanning started…");
    }

    /* Terminate scanning */
    private void stopScanning() {
        mBluetoothLeScanner.stopScan(mScanCallback);
        if (DEBUG_SCAN) Log.d(TAG, "Scanning stopped…");
    }

    /* Handle UID packet discovery on the main thread */
    private void processUidPacket(String deviceAddress, int rssi, String id) {
        if (DEBUG_SCAN) {
            Log.d(TAG, "Eddystone(" + deviceAddress + ") id = " + id);
        }

        if (mBeaconEventListener != null) {
            mBeaconEventListener
                    .onBeaconIdentifier(deviceAddress, rssi, id);
        }
    }

    /* Handle TLM packet discovery on the main thread */
    private void processTlmPacket(String deviceAddress, float battery, float temp) {
        if (DEBUG_SCAN) {
            Log.d(TAG, "Eddystone(" + deviceAddress + ") battery = " + battery
                    + ", temp = " + temp);
        }

        if (mBeaconEventListener != null) {
            mBeaconEventListener
                    .onBeaconTelemetry(deviceAddress, battery, temp);
        }
    }

    /* Process each unique BLE scan result */
    private ScanCallback mScanCallback = new ScanCallback() {
        private Handler mCallbackHandler =
                new Handler(Looper.getMainLooper());

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            processResult(result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "Scan Error Code: " + errorCode);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                processResult(result);
            }
        }

        private void processResult(ScanResult result) {
            byte[] data = result.getScanRecord().getServiceData(UID_SERVICE);
            if (data == null) {
                Log.w(TAG, "Invalid Eddystone scan result.");
                return;
            }

            final String deviceAddress = result.getDevice().getAddress();
            final int rssi = result.getRssi();
            byte frameType = data[0];
            switch (frameType) {
                case TYPE_UID:
                    final String id = SampleBeacon.getInstanceId(data);

                    mCallbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            processUidPacket(deviceAddress, rssi, id);
                        }
                    });
                    break;
                case TYPE_TLM:
                    //Parse out battery voltage
                    final float battery = SampleBeacon.getTlmBattery(data);
                    final float temp = SampleBeacon.getTlmTemperature(data);
                    mCallbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            processTlmPacket(deviceAddress, battery, temp);
                        }
                    });
                    break;
                case TYPE_URL:
                    //Do nothing, ignoring these
                    return;
                default:
                    Log.w(TAG, "Invalid Eddystone scan result.");
            }
        }
    };
}
