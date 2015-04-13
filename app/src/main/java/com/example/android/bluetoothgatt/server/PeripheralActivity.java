package com.example.android.bluetoothgatt.server;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.example.android.bluetoothgatt.TimerGattProfile;

public class PeripheralActivity extends Activity
        implements TimeServerCallback.ServerStatusListener {
    private static final String TAG = PeripheralActivity.class.getSimpleName();

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;

    private TimeServerCallback mGattServerCallback;

    private ArrayAdapter<BluetoothDevice> mConnectedDevicesAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ListView list = new ListView(this);
        setContentView(list);

        mConnectedDevicesAdapter = new ArrayAdapter<BluetoothDevice>(this,
                android.R.layout.simple_list_item_1);
        list.setAdapter(mConnectedDevicesAdapter);

        /*
         * Bluetooth in Android 4.3+ is accessed via the BluetoothManager,
         * rather than the old static BluetoothAdapter.getInstance()
         */
        BluetoothManager manager =
                (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = manager.getAdapter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        /*
         * We need to enforce that Bluetooth is first enabled, and take the
         * user to settings to enable it if they have not done so.
         */
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            //Bluetooth is disabled
            Intent enableBtIntent =
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            finish();
            return;
        }

        /*
         * Check for Bluetooth LE Support.  In production, our manifest entry
         * will keep this from installing on these devices, but this will
         * allow test devices or other sideloads to report whether or not
         * the feature exists.
         */
        if (!getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "No LE Support.", Toast.LENGTH_SHORT)
                    .show();
            finish();
            return;
        }

        /*
         * Check for advertising support. Not all devices are enabled to
         * advertise Bluetooth LE data.
         */
        if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            Toast.makeText(this, "No Advertising Support.", Toast.LENGTH_SHORT)
                    .show();
            finish();
            return;
        }

        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        mGattServerCallback = new TimeServerCallback(this);

        mGattServerCallback.initServer(this);
        startAdvertising();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAdvertising();
        mGattServerCallback.shutdownServer();
    }

    /*
     * Initialize the advertiser
     */
    private void startAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(TimerGattProfile.UUID_SERVICE_TIMER))
                .build();

        mBluetoothLeAdvertiser.startAdvertising(settings,
                data,
                mAdvertiseCallback);
    }

    /*
     * Terminate the advertiser
     */
    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;

        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    /*
     * Callback handles events from the framework describing
     * if we were successful in starting the advertisement requests.
     */
    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "Peripheral Advertise Started.");
            postStatusMessage("GATT Server Ready");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "Peripheral Advertise Failed: "+errorCode);
            postStatusMessage("GATT Server Error "+errorCode);
        }
    };

    //Updates to the UI must be posted to the main thread
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private void postStatusMessage(final String message) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                setTitle(message);
            }
        });
    }

    /** UI Handlers for events from the GATT server instance */

    @Override
    public void onDeviceConnected(BluetoothDevice device) {
        mConnectedDevicesAdapter.add(device);
    }

    @Override
    public void onDeviceDisconnected(BluetoothDevice device) {
        mConnectedDevicesAdapter.remove(device);
    }

    @Override
    public void onTimeOffsetUpdated() {
        Toast.makeText(this, "Time Offset Updated", Toast.LENGTH_SHORT)
                .show();
    }
}
