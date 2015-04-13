package com.example.android.bluetoothgatt.server;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.example.android.bluetoothgatt.TimerGattProfile.*;

/*
 * Callback handles all incoming requests from GATT clients.
 * From connections to read/write requests.
 */
public class TimeServerCallback extends BluetoothGattServerCallback {
    private static final String TAG = TimeServerCallback.class.getSimpleName();

    //Basic callback interface to notify the user interface of events
    public interface ServerStatusListener {
        void onDeviceConnected(BluetoothDevice device);
        void onDeviceDisconnected(BluetoothDevice device);
        void onTimeOffsetUpdated();
    }

    private Handler mHandler = new Handler(Looper.getMainLooper());

    private BluetoothGattServer mGattServer;
    private List<BluetoothDevice> mConnectedDevices;

    private ServerStatusListener mStatusListener;

    public TimeServerCallback(ServerStatusListener listener) {
        mConnectedDevices = new ArrayList<BluetoothDevice>();
        mStatusListener = listener;
    }

    /*
     * Create the GATT server instance, attaching all services and
     * characteristics that should be exposed
     */
    public void initServer(Context context) {
        BluetoothManager manager =(BluetoothManager) context
                .getSystemService(Context.BLUETOOTH_SERVICE);
        mGattServer = manager.openGattServer(context, this);

        BluetoothGattService service =
                new BluetoothGattService(UUID_SERVICE_TIMER,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic elapsedCharacteristic =
                new BluetoothGattCharacteristic(UUID_CHARACTERISTIC_ELAPSED,
                        PROPERTIES_ELAPSED, PERMISSIONS_ELAPSED);
        BluetoothGattCharacteristic offsetCharacteristic =
                new BluetoothGattCharacteristic(UUID_CHARACTERISTIC_OFFSET,
                        PROPERTIES_OFFSET, PERMISSIONS_OFFSET);

        service.addCharacteristic(elapsedCharacteristic);
        service.addCharacteristic(offsetCharacteristic);

        mGattServer.addService(service);
    }

    /*
     * Terminate the server and any running callbacks
     */
    public void shutdownServer() {
        mHandler.removeCallbacks(mNotifyRunnable);

        if (mGattServer == null) return;

        mGattServer.close();
    }

    /** Server Event Callback Methods */

    @Override
    public void onConnectionStateChange(BluetoothDevice device,
                                        int status,
                                        int newState) {
        Log.i(TAG, "onConnectionStateChange "
                + getStatusDescription(status) + " "
                + getStateDescription(newState));

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            postDeviceChange(device, true);

        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            postDeviceChange(device, false);
        }
    }

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device,
                                int requestId,
                                int offset,
                                BluetoothGattCharacteristic characteristic) {
        final UUID characteristicUuid = characteristic.getUuid();
        Log.i(TAG, "onCharacteristicReadRequest "
                + characteristicUuid.toString());

        if (UUID_CHARACTERISTIC_ELAPSED.equals(characteristicUuid)) {
            mGattServer.sendResponse(device, requestId,
                    BluetoothGatt.GATT_SUCCESS, 0, getStoredValue());
        }else if (UUID_CHARACTERISTIC_OFFSET.equals(characteristicUuid)) {
            mGattServer.sendResponse(device, requestId,
                    BluetoothGatt.GATT_SUCCESS, 0, bytesFromInt(mTimeOffset));
        }

        /*
         * Always send a response back for any read request.
         */
        mGattServer.sendResponse(device, requestId,
                BluetoothGatt.GATT_FAILURE, 0, null);
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device,
                                 int requestId,
                                 BluetoothGattCharacteristic characteristic,
                                 boolean preparedWrite,
                                 boolean responseNeeded,
                                 int offset,
                                 byte[] value) {
        final UUID characteristicUuid = characteristic.getUuid();
        Log.i(TAG, "onCharacteristicWriteRequest "
                + characteristicUuid.toString());

        if (UUID_CHARACTERISTIC_OFFSET.equals(characteristicUuid)) {
            int newOffset = unsignedIntFromBytes(value);
            setStoredValue(newOffset);

            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId,
                        BluetoothGatt.GATT_SUCCESS, 0, value);
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    //Notify the user interface listener on the main thread
                    mStatusListener.onTimeOffsetUpdated();
                }
            });

            notifyConnectedDevices();
        }
    }

    private void postDeviceChange(final BluetoothDevice device,
                                  final boolean toAdd) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                //Notify the user interface listener on the main thread
                if (toAdd) {
                    mConnectedDevices.add(device);
                    mStatusListener.onDeviceConnected(device);
                } else {
                    mConnectedDevices.remove(device);
                    mStatusListener.onDeviceDisconnected(device);
                }

                //Trigger our periodic notification once devices connect
                mHandler.removeCallbacks(mNotifyRunnable);
                if (!mConnectedDevices.isEmpty()) {
                    mHandler.post(mNotifyRunnable);
                }
            }
        });
    }

    /** Notifier logic for characteristic changes */

    private Runnable mNotifyRunnable = new Runnable() {
        @Override
        public void run() {
            notifyConnectedDevices();
            mHandler.postDelayed(this, 2000);
        }
    };

    public void notifyConnectedDevices() {
        BluetoothGattCharacteristic readCharacteristic =
                mGattServer.getService(UUID_SERVICE_TIMER)
                        .getCharacteristic(UUID_CHARACTERISTIC_ELAPSED);
        readCharacteristic.setValue(getStoredValue());

        for (BluetoothDevice device : mConnectedDevices) {
            mGattServer.notifyCharacteristicChanged(device,
                    readCharacteristic,
                    false);
        }
    }

    /**
     * Synchronized access to stored value.
     * LE callbacks come from different threads.
     */

    private Object mLock = new Object();
    private int mTimeOffset;

    private byte[] getStoredValue() {
        synchronized (mLock) {
            return getShiftedTimeValue(mTimeOffset);
        }
    }

    private void setStoredValue(int newOffset) {
        synchronized (mLock) {
            mTimeOffset = newOffset;
        }
    }
}
