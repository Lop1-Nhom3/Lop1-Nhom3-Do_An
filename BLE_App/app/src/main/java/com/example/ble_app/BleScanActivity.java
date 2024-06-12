package com.example.ble_app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BleScanActivity extends AppCompatActivity {
    private final static String TAG = BleScanActivity.class.getSimpleName();                        //Activity name for logging messages on the ADB
    private static final int RES_CODE_SCAN_ACTIVITY = 2;
    public static final String EXTRA_SCAN_ADDRESS = "BLE_SCAN_DEVICE_ADDRESS";                      //Identifier for Bluetooth device address attached to Intent that returns a result
    public static final String EXTRA_SCAN_NAME = "BLE_SCAN_DEVICE_NAME";                            //Identifier for Bluetooth device name attached to Intent that returns a result
    private static final int REQ_CODE_ENABLE_BT = 1;                                                //Code to identify activity that enables Bluetooth
    private static final UUID EXAMPLE_SERVICE_UUID = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb"); //Advertised service UUID for scan filter
    private static final long SCAN_TIME = 20000;

    private BluetoothAdapter btAdapter;                                                             //BluetoothAdapter represents the Bluetooth radio in the phone
    private BluetoothLeScanner bleScanner;                                                          //BluetoothLeScanner handles the scanning for BLE devices that are advertising
    private Handler stopScanHandler;
    private final Handler mStopScanHandler = new Handler();
    private DeviceListAdapter deviceListAdapter;                                                  //ArrayAdapter to manage the ListView showing the devices found during the scan
    private boolean areScanning;
    private Button mHomeButton;
    private TextView deviceListText;
    private static final long STOP_SCAN_DELAY = 800; // msec

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.scan_list_screen);
        Toolbar myToolbar = findViewById(R.id.toolbar);                                             //Get a reference to the Toolbar at the top of the screen
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        deviceListText = findViewById(R.id.deviceListText);                                         //Text to indicate devices found or not found
        ListView deviceListView = (ListView) findViewById(R.id.deviceListView);                                //ListView to show all the devices found during the scan
        deviceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @SuppressLint("MissingPermission")
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                final BluetoothDevice device = deviceListAdapter.getItem(i);                            //Get the device from the list adapter
                stopScanHandler.removeCallbacks(stopScanRunnable);                                      //Stop the scan timeout handler from calling the runnable to stop the scan
                stopScan();                                                                             //Stop a scan that might still be running
                final Intent intent = new Intent();                                                     //Create Intent to return information to the BleMainActivity that started this activity
                if (device != null) {                                                                   //Check that a valid device was received
                    intent.putExtra(EXTRA_SCAN_NAME, device.getName());                                 //Add BLE device name to the Intent
                    intent.putExtra(EXTRA_SCAN_ADDRESS, device.getAddress());                           //Add BLE device address to the Intent
                    setResult(RES_CODE_SCAN_ACTIVITY, intent);                                              //Set the Intent to return a result to the calling activity with the selected BLE name and address
                } else {
                    setResult(Activity.RESULT_CANCELED, intent);                                        //Something went wrong so indicate cancelled
                }
                finish();
            }
        });
        mHomeButton = findViewById(R.id.homeButton);
        mHomeButton.setOnClickListener((View.OnClickListener) (new View.OnClickListener() {
            public final void onClick(View view) {
                onBackPress();
            }
        }));
        deviceListAdapter = new DeviceListAdapter(this, R.layout.scan_list_item);           //Create new ArrayAdapter to hold a list of BLE devices found during the scan
        deviceListView.setAdapter(deviceListAdapter);                                               //Bind our ArrayAdapter the new list adapter in our ListActivity
        stopScanHandler = new Handler(Looper.getMainLooper());                                      //Create a handler for a delayed runnable that will stop the scan after a time delay
        try {
            btAdapter = BluetoothAdapter.getDefaultAdapter();                                       //Get a reference to the BluetoothAdapter
            if (btAdapter == null) {                                                                //Unlikely that there is no Bluetooth radio but best to check anyway
                Log.e(TAG, "Unable to obtain a BluetoothAdapter");
                finish();                                                                           //End the activity, can do nothing without a BluetoothAdapter
            }
            bleScanner = btAdapter.getBluetoothLeScanner();                                         //Get a BluetoothLeScanner so we can scan for BLE devices

        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }

    }


    @Override
    protected void onResume() {
        super.onResume();                                                                           //Call superclass (AppCompatActivity) onResume method
        try {
            if (!btAdapter.isEnabled()) {                                                           //Check that Bluetooth is still enabled
                startForResult.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); //Invoke the Intent to start the activity to turn on Bluetooth
                Log.d(TAG, "Requesting user to enable Bluetooth radio");
            } else {
                startScan();                                                                        //Always start a scan when resuming from a pause
            }
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
//        mStopScanHandler.postDelayed(stopScanRunnable, STOP_SCAN_DELAY);
        stopScanHandler.removeCallbacks(stopScanRunnable);
        stopScan();
    }

    private ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), new ActivityResultCallback<Boolean>() {
        @SuppressLint("MissingPermission")
        @Override
        public void onActivityResult(Boolean isGranted) {
            if(isGranted){
                bleScanner.startScan(scanFilterList, scanSettings, bleScanCallback);
//                bleScanner.startScan(bleScanCallback);
            }
            else {
                onBackPress();
            }
        }
    });

    List<ScanFilter> scanFilterList;
    ScanSettings scanSettings;
    private void startScan() {
        try {
            if (!areScanning) {                                                                     //Only start scanning if not already scanning
                if (btAdapter.isEnabled() && bleScanner != null) {                                  //Check that Bluetooth is enabled
                    areScanning = true;                                                             //Indicate that we are scanning - used for menu context and to avoid starting scan twice
                    deviceListText.setText("No device found");                                     //Show "No devices found" until scan returns a result
                    deviceListAdapter.clear();                                                      //Clear list of BLE devices found
                    deviceListAdapter.notifyDataSetChanged();
                    scanFilterList = new ArrayList<>();                            //Create a new ScanFilter list
                    scanFilterList.add(new ScanFilter.Builder().setDeviceName("BLE_TEST").build());
//                    scanFilterList.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(EXAMPLE_SERVICE_UUID)).build()); //Add a service UUID to the filter list
                    scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(); //Set the scan mode to low latency
                    if (ActivityCompat.checkSelfPermission(BleScanActivity.this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN);
                        }
                    }

                }
                else {                                                                              //Radio needs to be enabled
                    startForResult.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); //Invoke the Intent to start the activity that will return a result based on user input
                    Log.d(TAG, "Requesting user to enable Bluetooth radio");
                }
            }
        }
        catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    private ScanCallback bleScanCallback = new ScanCallback() {
        @SuppressLint({"SetTextI18n", "MissingPermission"})
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            try {
                BluetoothDevice device = result.getDevice();                                        //Get the device found by the scan
                deviceListAdapter.addDevice(device);                                                //Add the new BleDevice object to our list adapter that displays a list on the screen
                deviceListAdapter.notifyDataSetChanged();                                           //Refresh the list on the screen
                deviceListText.setText("Device found");                                           //Show "Devices found:" because we have found a device
                Log.i(TAG, "ScanResult: Addr - " + device.getAddress() + ", Name - " + device.getName());
            }
            catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan Failed: Error Code: " + errorCode);
        }
    };


    @SuppressLint("MissingPermission")
    private void stopScan() {
        try {
            bleScanner.stopScan(bleScanCallback);                                               //Stop scanning
            areScanning = false;                                                                //Indicate that we are not scanning

        }
        catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    private Runnable stopScanRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan();                                                                             //Stop the scan
        }
    };

    ActivityResultLauncher<Intent> startForResult = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>() {
        @Override
        public void onActivityResult(ActivityResult result) {
            if(result.getResultCode() != Activity.RESULT_OK){
                setResult(RESULT_CANCELED);
                finish();
            }
        }
    });

    private void onBackPress(){
        setResult(Activity.RESULT_OK);
        finish();
    }

    private static class DeviceListAdapter extends ArrayAdapter<BluetoothDevice> {

        private ArrayList<BluetoothDevice> btDevices;                                               //An ArrayList to hold the BluetoothDevice objects in the list
        private int layoutResourceId;
        private Context context;

        public DeviceListAdapter(Context context, int layoutResourceId) {                           //Constructor for the DeviceListAdapter
            super(context, layoutResourceId);
            this.layoutResourceId = layoutResourceId;
            this.context = context;
            btDevices = new ArrayList<>();                                                          //Create the list to hold BleDevice objects
        }

        public void addDevice(BluetoothDevice device) {                                             //Add a new device to the list
            if(!btDevices.contains(device)) {                                                       //See if device is not already in the list
                btDevices.add(device);                                                              //Add the device to the list
            }
        }

        public void clear() {                                                                       //Clear the list of devices
            btDevices.clear();
        }

        @Override
        public int getCount() {                                                                     //Get the number of devices in the list
            return btDevices.size();
        }

        @Override
        public BluetoothDevice getItem(int i) {                                                      //Get a device from the list based on its position
            return btDevices.get(i);
        }

        @Override
        public long getItemId(int i) {                                                              //Get device ID which is just its position in the list
            return i;
        }

        //Called by the Android OS to show each item in the view. View items that scroll off the screen are reused.
        @SuppressLint("MissingPermission")
        @Override
        public View getView(int position, View convertView, ViewGroup parentView) {
            if (convertView == null) {                                                              //Only inflate a new layout if not recycling a view
                LayoutInflater inflater = ((Activity) context).getLayoutInflater();                 //Get the layout inflater for this activity
                convertView = inflater.inflate(layoutResourceId, parentView, false);    //Inflate a new view containing the device information
            }
            BluetoothDevice device = btDevices.get(position);                                       //Get device item based on the position
            TextView textViewAddress = convertView.findViewById(R.id.device_address);               //Get the TextView for the address
            textViewAddress.setText(device.getAddress());                                           //Set the text to the name of the device
            TextView textViewName = convertView.findViewById(R.id.device_name);                     //Get the TextView for the name
            textViewName.setText(device.getName());                                                 //Set the text to the name of the device
            return convertView;
        }
    }
}