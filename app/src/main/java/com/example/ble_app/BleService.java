package com.example.ble_app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanFilter.Builder;
import android.widget.Toast;
import android.widget.ListView;
import android.widget.ArrayAdapter;

import java.io.ByteArrayOutputStream;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;

public class BleService extends Service {
    private final static String TAG = BleService.class.getSimpleName();
    private final static String ACTION_ADAPTER_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED";           //Identifier for Intent that announces a change in the state of the Bluetooth radio
    private final static String EXTRA_ADAPTER_STATE = "android.bluetooth.adapter.extra.STATE";                    //Identifier for Bluetooth connection state attached to state changed Intent
    public final static String ACTION_BLE_CONNECTED = "com.example.ble_app.ACTION_BLE_CONNECTED";         //Identifier for Intent to announce that a BLE device connected
    public final static String ACTION_BLE_DISCONNECTED = "com.example.ble_app.ACTION_BLE_DISCONNECTED";      //Identifier for Intent to announce that a BLE device disconnected
    public final static String ACTION_BLE_DISCOVERY_DONE = "com.example.ble_app.ACTION_BLE_DISCOVERY_DONE";    //Identifier for Intent to announce that service discovery is complete
    public final static String ACTION_BLE_DISCOVERY_FAILED = "com.example.ble_app.ACTION_BLE_DISCOVERY_FAILED";  //Identifier for Intent to announce that service discovery failed to find the service and characteristics
    public final static String ACTION_BLE_NEW_DATA_RECEIVED = "com.example.ble_app.ACTION_BLE_NEW_DATA_RECEIVED"; //Identifier for Intent to announce a new characteristic notification

    private final static UUID UUID_TRANSPARENT_PRIVATE_SERVICE = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb"); //Private service for Microchip Transparent UART
    private final static UUID UUID_TRANSPARENT_SEND_CHAR = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb"); //Characteristic for Transparent UART to send to RN or BM module, properties - write, write no response
    private final static UUID UUID_TRANSPARENT_RECEIVE_CHAR = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb"); //Characteristic for Transparent UART to receive from RN or BM module, properties - notify, write, write no response
    private final static UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"); //Descriptor to enable notification for a characteristic

    private final Queue<byte[]> characteristicWriteQueue = new LinkedList<>();                      //Queue to buffer multiple writes since the radio does one at a time
    private final Queue<BluetoothGattDescriptor> descriptorWriteQueue = new LinkedList<>();         //Queue to buffer multiple writes since the radio does one at a time
    private BluetoothAdapter btAdapter;                                                             //BluetoothAdapter is used to control the Bluetooth radio
    private BluetoothGatt btGatt;                                                                   //BluetoothGatt is used to control the Bluetooth connection
    private BluetoothGattCharacteristic transparentSendCharacteristic;                              //Characteristic used to send data from the Android device to the BM7x or RN487x module
    private ByteArrayOutputStream transparentReceiveOutput = new ByteArrayOutputStream();           //Object to hold incoming bytes from the Transparent UART Receive characteristic until the Main Activity requests them
    private int CharacteristicSize = 20;                                                            //To keep track of the maximum length of the characteristics (always 3 less than the real MTU size to fit in opcode and handle)
    private int connectionAttemptCountdown = 0;
    private BluetoothDevice btDevice;
    private BluetoothGattDescriptor descriptor;
    private BluetoothGattCharacteristic transparentReceiveCharacteristic;
    public BleService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "Binding to BleService");
        try {
            btAdapter = BluetoothAdapter.getDefaultAdapter();                                       //Get a reference to the BluetoothAdapter
            if (btAdapter == null) {                                                                //Unlikely that there is no Bluetooth radio but best to check anyway
                Log.e(TAG, "Unable to obtain a BluetoothAdapter");
            }
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
        return new LocalBinder();                                                                   //Return Binder object that the binding Activity needs to use the service
    }

    // ----------------------------------------------------------------------------------------------------------------
    // All activities have stopped using the service and it will be destroyed

    public class LocalBinder extends Binder {
        BleService getService() {
            return BleService.this;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    public void onDestroy() {
        try {
            if (btGatt != null) {                                                                   //See if there is an existing Bluetooth connection
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    btGatt.close();
                }
            }
        }
        catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
        super.onDestroy();
    }
    private final BluetoothGattCallback btGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {         //Connected or disconnected
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectionAttemptCountdown = 0;                                                 //Stop counting connection attempts
                    switch (newState) {
                        case BluetoothProfile.STATE_CONNECTED: {                                    //Are now connected
                            Log.i(TAG, "Connected to BLE device");
                            transparentReceiveOutput.reset();                                       //Reset (empty) the ByteArrayOutputStream of any data left over from a previous connection
                            sendBroadcast(new Intent(ACTION_BLE_CONNECTED));                        //Let the BleMainActivity know that we are connected by broadcasting an Intent
                            descriptorWriteQueue.clear();                                           //Clear write queues in case there was something left in the queue from the previous connection
                            characteristicWriteQueue.clear();
                            btGatt.discoverServices();                                              //Discover services after successful connection
                            break;
                        }
                        case BluetoothProfile.STATE_DISCONNECTED: {                                 //Are now disconnected
                            Log.i(TAG, "Disconnected from BLE device");
                            sendBroadcast(new Intent(ACTION_BLE_DISCONNECTED));                     //Let the BleMainActivity know that we are disconnected by broadcasting an Intent
                            gatt.close();
                        }
                    }
                } else {                                                                              //Something went wrong with the connection or disconnection request
                    if (status==133&&connectionAttemptCountdown++<4) {
                        gatt.close();
                        gatt.connect();
                        Log.d(TAG, "Connection attempt failed, trying again");
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connectionAttemptCountdown=0;
                        sendBroadcast(new Intent(ACTION_BLE_DISCONNECTED));                         //Let the BleMainActivity know that we are disconnected by broadcasting an Intent
                        Log.i(TAG, "Unexpectedly disconnected from BLE device");
                        gatt.close();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }

//        @Override
//        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
//            transparentSendCharacteristic=null;
//        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {                          //Service discovery completed
            try {
                boolean discoveryFailed = false;                                                    //Record any failures as services, characteristics, and descriptors are requested
                transparentSendCharacteristic = null;                                               //Have not found characteristic yet
                if (status == BluetoothGatt.GATT_SUCCESS) {                                         //See if service discovery was successful
                    BluetoothGattService gattService = gatt.getService(UUID_TRANSPARENT_PRIVATE_SERVICE); //Get the Transparent UART service
                    if (gattService != null) {                                                      //Check that the service was discovered
                        Log.i(TAG, "Found Transparent UART service");
                        transparentReceiveCharacteristic = gattService.getCharacteristic(UUID_TRANSPARENT_RECEIVE_CHAR); //Get the characteristic for receiving from the Transparent UART
                        if (transparentReceiveCharacteristic != null) {                             //See if the characteristic was found
                            Log.i(TAG, "Found Transparent Receive characteristic");
                            final int characteristicProperties = transparentReceiveCharacteristic.getProperties(); //Get the properties of the characteristic
                            if ((characteristicProperties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY)) > 0) { //See if the characteristic has the Notify property
                                descriptor = transparentReceiveCharacteristic.getDescriptor(UUID_CCCD); //Get the descriptor that enables notification on the server
                            }
                            else {
                                discoveryFailed = true;
                                Log.w(TAG, "Transparent Receive characteristic does not have notify property");
                            }
                        }
                        else {
                            discoveryFailed = true;
                            Log.w(TAG, "Did not find Transparent Receive characteristic");
                        }

                        transparentSendCharacteristic = gattService.getCharacteristic(UUID_TRANSPARENT_SEND_CHAR); //Get the Transparent Send characteristic
                        if (transparentSendCharacteristic != null) {                                //See if the characteristic was found
                            Log.i(TAG, "Found Transparent Send characteristic");
                            final int characteristicProperties = transparentSendCharacteristic.getProperties(); //Get the properties of the characteristic
                            if ((characteristicProperties & (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) > 0) { //See if the characteristic has the Write (unacknowledged) property
                                transparentSendCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE); //If so then set the write type (write with no acknowledge) in the BluetoothGatt
                            } else if ((characteristicProperties & (BluetoothGattCharacteristic.PROPERTY_WRITE)) > 0) { //Else see if the characteristic has the Write (acknowledged) property
                                transparentSendCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); //If so then set the write type (write with no acknowledge) in the BluetoothGatt
                            } else {
                                discoveryFailed = true;
                                Log.w(TAG, "Transparent Send characteristic does not have write property");
                            }
                        }
                        else {
                            discoveryFailed = true;
                            Log.w(TAG, "Did not find Transparent Send characteristic");
                        }
                    }
                    else {
                        discoveryFailed = true;
                        Log.w(TAG, "Did not find Transparent UART service");
                    }
                }
                else {
                    discoveryFailed = true;
                    Log.w(TAG, "Failed service discovery with status: " + status);
                }

                if (!discoveryFailed) {                                                             //Service discovery returned the correct service and characteristics
                    btGatt.requestMtu(512);                                                         //Request max data length and get the negotiated length in mtu argument of onMtuChanged()
                    sendBroadcast(new Intent(ACTION_BLE_DISCOVERY_DONE));                           //Broadcast Intent to announce the completion of service discovery
                }
                else {
                    sendBroadcast(new Intent(ACTION_BLE_DISCOVERY_FAILED));                         //Broadcast Intent to announce the failure of service discovery
                }
            }
            catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {                         //A new maximum transmission unit (MTU) size was negotiated with the Bluetooth device
            super.onMtuChanged(gatt, mtu, status);
            CharacteristicSize = mtu - 3;                                                           //The mtu argument indicates the size of a characteristic using Data Length Extension. It includes space for 1 byte opcode and 2 byte handle in addition to data so subtract 3.
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) { //Received notification or indication with a new value for a characteristic
            try {
                if (UUID_TRANSPARENT_RECEIVE_CHAR.equals(characteristic.getUuid())) {               //See if it is the Transparent Receive characteristic (the only notification expected)
                    Log.d(TAG, "New notification or indication");
                    transparentReceiveOutput.write(characteristic.getValue());                      //Get the bytes from the characteristic and put them in the ByteArrayOutputStream for later
                    sendBroadcast(new Intent(ACTION_BLE_NEW_DATA_RECEIVED));                        //Broadcast Intent to announce the new data. This does not send the data, it needs to be read by calling readFromTransparentUART() below
                }
            } catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) { //Write completed
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {                                         //See if the write was successful
                    Log.w(TAG, "Error writing GATT characteristic with status: " + status);
                }                                                                                   //A queue is used because BluetoothGatt can only do one write at a time
                Log.d(TAG, "Characteristic write completed");
                characteristicWriteQueue.remove();                                                  //Pop the item that we just finishing writing
                if(!characteristicWriteQueue.isEmpty()) {                                           //See if there is more to write
                    transparentSendCharacteristic.setValue(characteristicWriteQueue.element());     //Set the new value of the characteristic
                    btGatt.writeCharacteristic(transparentSendCharacteristic);                      //Write characteristic
                }
            }
            catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) { //Write descriptor completed
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Error writing GATT descriptor with status: " + status);
                }                                                                                   //A queue is used because BluetoothGatt can only do one write at a time
                Log.d(TAG, "Descriptor write completed");
                descriptorWriteQueue.remove();                                                      //Pop the item that we just finishing writing
                if(!descriptorWriteQueue.isEmpty()) {                                               //See if there are more descriptors to write
                    btGatt.writeDescriptor(descriptorWriteQueue.element());                         //Write descriptor
                }
            }
            catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }

//        @Override
//        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {} //Read completed - not used because this application uses Notification or Indication to receive characteristic data
//
//        @Override
//        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {} //Read descriptor completed - not used
//
//        @Override
//        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {}                     //Write with acknowledgement completed - not used
//
//        @Override
//        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {}                   //Read remote RSSI completed - not used
    };

    @SuppressLint("MissingPermission")
    public void connectBle(final String address) {
        try {
            if (btAdapter == null || address == null) {                                             //See if there is a radio and an address
                Log.w(TAG, "BluetoothAdapter not initialized or unspecified address");
                return;
            }
            btDevice = btAdapter.getRemoteDevice(address);                          //Use the address to get the remote device
            if (btDevice == null) {                                                                 //See if the device was found
                Log.w(TAG, "Unable to connect because device was not found");
                return;
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (btGatt != null) {                                                                   //See if an existing connection needs to be closed
                    btGatt.close();
                }
                connectionAttemptCountdown = 3;                                                         //Try to connect three times for reliability
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {                                   //Build.VERSION_CODES.M = 23 for Android 6
                    btGatt = btDevice.connectGatt(this, true, btGattCallback, BluetoothDevice.TRANSPORT_LE); //Directly connect to the device now, so set autoConnect to false, connect using BLE if device is dual-mode
                }
                Log.d(TAG, "Attempting to create a new Bluetooth connection");
            }
        }
        catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnectBle() {
        try {
            if (btAdapter != null && btGatt != null) {                                              //See if we have a connection before attempting to disconnect
                connectionAttemptCountdown = 0;                                                     //Stop counting connection attempts
                btGatt.disconnect();                                                                //Disconnect
            }
        }
        catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public void writeToTransparentUART(byte[] bytesToWrite) {
        try {
            if (btAdapter != null && btGatt != null && transparentSendCharacteristic != null) {     //See if there is a radio, a connection, and a valid characteristic
                while (bytesToWrite.length > 0) {                                                   //Keep doing writes (adding to the write queue) until all bytes have been written
                    int length = Math.min(bytesToWrite.length, CharacteristicSize);                 //Get the number of bytes to write, limited to the max size of a characteristic
                    byte[] limitedBytesToWrite = Arrays.copyOf(bytesToWrite, length);               //Get a subset of the bytes that will fit into a characteristic
                    bytesToWrite = Arrays.copyOfRange(bytesToWrite, length, bytesToWrite.length);   //Get the remaining bytes ready for the next write
                    characteristicWriteQueue.add(limitedBytesToWrite);                              //Put the characteristic value into the write queue
                    if (characteristicWriteQueue.size() == 1) {                                     //If there is only 1 item in the queue, then write it.  If more than 1, we do it in the onCharacteristicWrite() callback above
                        transparentSendCharacteristic.setValue(limitedBytesToWrite);                //Put the bytes into the characteristic value
                        Log.i(TAG, "Characteristic write started");
                        if (!btGatt.writeCharacteristic(transparentSendCharacteristic)) {           //Request the BluetoothGatt to do the Write
                            Log.w(TAG, "Failed to write characteristic");                      //Warning that write request was not accepted by the BluetoothGatt
                        }
                    }
                }
            }
            else {
                Log.w(TAG, "Write attempted with Bluetooth uninitialized or not connected");
            }
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public void startNotify(){
        if (descriptor != null) {                                           //See if we got the descriptor
            btGatt.setCharacteristicNotification(transparentReceiveCharacteristic, true); //If so then enable notification in the BluetoothGatt
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); //Set the value of the descriptor to enable notification
            descriptorWriteQueue.add(descriptor);                           //Put the descriptor into the write queue
            if (descriptorWriteQueue.size() == 1) {                         //If there is only 1 item in the queue, then write it.  If more than 1, we handle asynchronously in the onDescriptorWrite callback below
                btGatt.writeDescriptor(descriptor);                         //Write the descriptor
            }
        }
        else {
            Log.w(TAG, "No CCCD descriptor for Transparent Receive characteristic");
        }
    }

    public byte[] readFromTransparentUART() {
        try {
            final byte[] out = transparentReceiveOutput.toByteArray();                              //Get bytes from the ByteArrayOutputStream where they were put when onCharacteristicChanged was executed
            transparentReceiveOutput.reset();
            return out;                                                                             //Return the array of bytes
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
        return new byte[0];
    }

    @SuppressLint("MissingPermission")
    public void stopNotify(){
        if(descriptor!=null){
            descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            descriptorWriteQueue.add(descriptor);
            if (descriptorWriteQueue.size() == 1) {
                btGatt.writeDescriptor(descriptor);
            }
        }
    }

    public BluetoothGatt getBtGatt(){
        return btGatt;
    }
}

