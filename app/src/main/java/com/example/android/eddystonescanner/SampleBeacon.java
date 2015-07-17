package com.example.android.eddystonescanner;

import android.util.Log;

/**
 * Simple model class to house relevant data coming from
 * beacon advertisements.
 */
public class SampleBeacon {
    private static final String TAG = SampleBeacon.class.getSimpleName();

    public String deviceAddress;
    public String id;
    public float battery;
    public float temperature;

    public SampleBeacon(String address, String identifier) {
        deviceAddress = address;
        id = identifier;
        battery = -1f;
        temperature = -1f;
    }

    @Override
    public String toString() {
        if (battery < 0f && temperature < 0f) {
            return id;
        } else {
            return String.format("%s: %.1fV, %s", id, battery,
                    temperature < 0f ? "No Temp" : temperature+"C");
        }
    }

    @Override
    public boolean equals(Object object) {
        return (object instanceof SampleBeacon)
                && this.deviceAddress.equals(((SampleBeacon) object).deviceAddress);

    }

    // Parse the instance id out of a UID packet
    public static String getInstanceId(byte[] data) {
        StringBuilder sb = new StringBuilder();

        //UID packets are always 18 bytes in length
        //Parse out the last 6 bytes for the id
        int packetLength = 18;
        int offset = packetLength - 6;
        for (int i=offset; i < packetLength; i++) {
            sb.append(Integer.toHexString(data[i] & 0xFF));
        }

        return sb.toString();
    }

    // Parse the battery level out of a TLM packet
    public static float getTlmBattery(byte[] data) {
        byte version = data[1];
        if (version != 0) {
            Log.w(TAG, "Unknown telemetry version");
            return -1;
        }
        int voltage = (data[2] & 0xFF) << 8;
        voltage += (data[3] & 0xFF);

        //Value is 1mV per bit
        return voltage / 1000f;
    }

    // Parse the temperature out of a TLM packet
    public static float getTlmTemperature(byte[] data) {
        byte version = data[1];
        if (version != 0) {
            Log.w(TAG, "Unknown telemetry version");
            return -1;
        }

        if (data[4] == (byte)0x80 && data[5] == (byte)0x00) {
            Log.w(TAG, "Temperature not supported");
            return -1;
        }

        int temp = (data[4] << 8);
        temp += (data[5] & 0xFF);

        return temp / 256f;
    }
}
