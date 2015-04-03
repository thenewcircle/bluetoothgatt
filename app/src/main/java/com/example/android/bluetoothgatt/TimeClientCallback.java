package com.example.android.bluetoothgatt;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import static com.example.android.bluetoothgatt.DeviceProfile.*;

/*
 * Callback handles GATT client events, such as results from
 * reading or writing a characteristic value on the server.
 */
public class TimeClientCallback extends BluetoothGattCallback {
    private static final String TAG = TimeClientCallback.class.getSimpleName();

    //Simple callback interface to notify the user interface of events
    public interface ClientStatusListener {
        void onTimeValueChanged(int value);
        void onTimeOffsetChanged(long offset);
    }

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private ClientStatusListener mStatusListener;

    public TimeClientCallback(ClientStatusListener listener) {
        mStatusListener = listener;
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt,
                                        int status,
                                        int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        Log.d(TAG, "onConnectionStateChange "
                + getStatusDescription(status) + " "
                + getStateDescription(newState));

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.discoverServices();
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        Log.d(TAG, "onServicesDiscovered:");

        for (BluetoothGattService service : gatt.getServices()) {
            Log.d(TAG, "Service: "+service.getUuid());

            if (SERVICE_UUID.equals(service.getUuid())) {
                //Read the current characteristic's value
                gatt.readCharacteristic(service
                        .getCharacteristic(CHARACTERISTIC_ELAPSED_UUID));
            }
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt,
                                     BluetoothGattCharacteristic characteristic,
                                     int status) {
        super.onCharacteristicRead(gatt, characteristic, status);
        final int charValue = characteristic
                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT32, 0);

        if (CHARACTERISTIC_ELAPSED_UUID.equals(characteristic.getUuid())) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mStatusListener.onTimeValueChanged(charValue);
                }
            });

            //Register for further updates as notifications
            gatt.setCharacteristicNotification(characteristic, true);
        }

        if (CHARACTERISTIC_OFFSET_UUID.equals(characteristic.getUuid())) {
            Log.d(TAG, "Current time offset: "+charValue);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mStatusListener.onTimeOffsetChanged((long)charValue * 1000);
                }
            });
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        Log.i(TAG, "Notification of time characteristic changed on server.");
        final int charValue = characteristic
                .getIntValue(BluetoothGattCharacteristic.FORMAT_UINT32, 0);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mStatusListener.onTimeValueChanged(charValue);
            }
        });
    }
}
