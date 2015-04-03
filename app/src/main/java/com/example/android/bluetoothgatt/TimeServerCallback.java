package com.example.android.bluetoothgatt;

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

import static com.example.android.bluetoothgatt.DeviceProfile.*;

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
                new BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic elapsedCharacteristic =
                new BluetoothGattCharacteristic(CHARACTERISTIC_ELAPSED_UUID,
                        //Read-only characteristic, supports notifications
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattCharacteristic offsetCharacteristic =
                new BluetoothGattCharacteristic(CHARACTERISTIC_OFFSET_UUID,
                        //Read+write permissions
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_READ
                                | BluetoothGattCharacteristic.PERMISSION_WRITE);

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
        Log.i(TAG, "onCharacteristicReadRequest "
                + characteristic.getUuid().toString());

        if (CHARACTERISTIC_ELAPSED_UUID.equals(characteristic.getUuid())) {
            mGattServer.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    getStoredValue());
        }

        if (CHARACTERISTIC_OFFSET_UUID.equals(characteristic.getUuid())) {
            mGattServer.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    bytesFromInt(mTimeOffset));
        }

        /*
         * Always send a response back for any read request.
         */
        mGattServer.sendResponse(device,
                requestId,
                BluetoothGatt.GATT_FAILURE,
                0,
                null);
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device,
                                 int requestId,
                                 BluetoothGattCharacteristic characteristic,
                                 boolean preparedWrite,
                                 boolean responseNeeded,
                                 int offset,
                                 byte[] value) {
        Log.i(TAG, "onCharacteristicWriteRequest "
                + characteristic.getUuid().toString());

        if (CHARACTERISTIC_OFFSET_UUID.equals(characteristic.getUuid())) {
            int newOffset = unsignedIntFromBytes(value);
            setStoredValue(newOffset);

            if (responseNeeded) {
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        value);
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

                //Trigger our periodic notification once devices are connected
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
        for (BluetoothDevice device : mConnectedDevices) {
            BluetoothGattCharacteristic readCharacteristic =
                    mGattServer.getService(SERVICE_UUID)
                    .getCharacteristic(CHARACTERISTIC_ELAPSED_UUID);
            readCharacteristic.setValue(getStoredValue());

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
