package com.example.ble_app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = MainActivity.class.getSimpleName();
    private static final int RES_CODE_ENABLE_BT =     1;                                            //Codes to identify activities that return results such as enabling Bluetooth
    private static final int RES_CODE_SCAN_ACTIVITY = 2;                                            //or scanning for bluetooth devices
    private static final int REQ_CODE_ACCESS_LOC1 =   3;
    private static final long CONNECT_TIMEOUT = 10000;
    private Button mBleBt, mStBt, mStatBt;
    private TextView mCurHr, mTime, mMaxHr, mMinHr, mAvrHr;
    private enum StateConnection {DISCONNECTED, CONNECTING, DISCOVERING, CONNECTED, DISCONNECTING}
    private StateConnection stateConnection;
    private enum StateApp {STARTING_SERVICE, REQUEST_PERMISSION, ENABLING_BLUETOOTH, RUNNING}       //States of the app
    private StateApp stateApp;
    private BleService bleService;
    private ByteArrayOutputStream transparentUartData = new ByteArrayOutputStream();                //Stores all the incoming byte arrays received from BLE device in bleService     //Object that creates and shows all the alert pop ups used in the app
    private Handler connectTimeoutHandler;
    private String bleDeviceName, bleDeviceAddress;
    private boolean isStartButton;
    private LineGraphSeries<DataPoint> hrSeries;
    private double time=0.0;
    private Timer timer;
    private TimerTask timerTask;
    private float max =0, min=200, sum=0, cur;
    Handler handler = new Handler();
    private Runnable runnable;
    private ArrayList<HeartRateData> heartRates = new ArrayList<>();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        stateConnection = StateConnection.DISCONNECTED;                                             //Initial stateConnection when app starts
        stateApp = StateApp.STARTING_SERVICE;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { //Check whether we have location permission, required to scan
            stateApp = StateApp.REQUEST_PERMISSION;                                                 //Are requesting Location permission
        }
        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQ_CODE_ACCESS_LOC1); //Request fine location permission
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startForResult.launch(enableBtIntent);
        mCurHr = findViewById(R.id.curHr);
        mMaxHr = findViewById(R.id.max);
        mMinHr = findViewById(R.id.min);
        mAvrHr = findViewById(R.id.avrHr);
        mTime = findViewById(R.id.time);
        mBleBt = findViewById(R.id.bleButton);
        mBleBt.setOnClickListener((View.OnClickListener)(new View.OnClickListener(){
            public final void onClick(View it){
                startBleScanActivity();
            }
        }));
        isStartButton=true;
        mStBt = findViewById(R.id.stButton);
        timer= new Timer();
        mStBt.setOnClickListener((View.OnClickListener)(new View.OnClickListener() {
            @SuppressLint("SetTextI18n")
            public final void onClick(View view) {
                if(bleService.getBtGatt()!=null){
                    if (isStartButton) {
                        initializeDisplay();
                        isStartButton = false;
                        mStBt.setText("STOP");
                        bleService.startNotify();
                    } else {
                        isStartButton = true;
                        mStBt.setText("START");
                        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                        HeartRateData data = new HeartRateData((int)cur * 100, (int)max * 100, (int)min * 100, (int) (sum / time * 100), currentTime);
                        heartRates.add(data);
                        bleService.stopNotify();
                    }
                }
                else {
                    Toast.makeText(getApplicationContext(),"HAVEN'T CONNECTED YET", Toast.LENGTH_SHORT).show();
                }
            }
        }));
        mStatBt = findViewById(R.id.statiButton);
        mStatBt.setOnClickListener((View.OnClickListener)(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, ChildActivity.class);
                intent.putParcelableArrayListExtra("heartRates", heartRates);

                startActivity(intent);
            }
        }));
        timerTask=new TimerTask() {
            @SuppressLint({"SetTextI18n", "DefaultLocale"})
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if(!isStartButton){
                            time++;
                            int rounded= (int) Math.round(time);
                            mTime.setText(String.format("%02d", rounded/3600)+": "+String.format("%02d",(rounded%3600)/60)+": "+String.format("%02d",rounded%60));
                            sum=sum+cur;
                            mAvrHr.setText(String.format("%.2f", sum/time));

                        }
                    }
                });
            }
        };
        timer.schedule(timerTask, 0, 1000);

        connectTimeoutHandler = new Handler(Looper.getMainLooper());
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) GraphView hrGraph = findViewById(R.id.hrGraph);
        hrSeries=new LineGraphSeries<>();
        hrGraph.addSeries(hrSeries);
        hrGraph.getViewport().setXAxisBoundsManual(true);
        hrGraph.getViewport().setMinX(0);
        hrGraph.getViewport().setMaxX(60);
        hrGraph.getViewport().setYAxisBoundsManual(true);
        hrGraph.getViewport().setMinY(0);
        hrGraph.getViewport().setMaxY(200);
        hrGraph.getViewport().setScrollable(true);
        hrGraph.getViewport().setScalable(true);
    }

    private final Handler mStartGattHandler = new Handler();
    private final Runnable mStartGattRunnable = new Runnable() {
        @Override
        public void run() {
            connectWithAddress(bleDeviceAddress);
        }
    };

    private ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), new ActivityResultCallback<Boolean>() {
        @SuppressLint("MissingPermission")
        @Override
        public void onActivityResult(Boolean isGranted) {
            if(isGranted){
                mStartGattHandler.postDelayed(mStartGattRunnable, 500);
            }
        }
    });

    ActivityResultLauncher<Intent> startForResult = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>() {
        @Override
        public void onActivityResult(ActivityResult result) {
            switch (result.getResultCode()){
                case Activity.RESULT_OK:{
                    stateApp=StateApp.RUNNING;
                    break;
                }
                case Activity.RESULT_CANCELED: {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startForResult.launch(enableBtIntent);
                    break;
                }
                case RES_CODE_SCAN_ACTIVITY:{
                    if(result.getData()!=null){
                        stateApp = StateApp.RUNNING;                                                    //Service is running and Bluetooth is enabled, app is fully operational
                        bleDeviceAddress = result.getData().getStringExtra(BleScanActivity.EXTRA_SCAN_ADDRESS);   //Get the address of the BLE device selected in the BleScanActivity
                        bleDeviceName = result.getData().getStringExtra(BleScanActivity.EXTRA_SCAN_NAME);         //Get the name of the BLE device selected in the BleScanActivity
                        if (bleDeviceAddress == null) {                                                 //Check whether we were given a device address
                            stateConnection = StateConnection.DISCONNECTED;                             //No device address so not connected and not going to connect
                        } else {
                            stateConnection = StateConnection.CONNECTING;                               //Got an address so we are going to start connecting
                            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED){
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);                                       //Initiate a connection
                                }
                            }
                        }
                    }
                    else {
                        stateConnection=StateConnection.DISCONNECTED;
                    }
                    break;
                }
            }
        }
    });

    @Override
    protected void onResume() {
        super.onResume();                                                                           //Call superclass (AppCompatActivity) onResume method
        try {
            registerReceiver(bleServiceReceiver, bleServiceIntentFilter());
            if (stateApp == StateApp.RUNNING) {                                                 //Check that app is running, to make sure service is connected
                stateApp = StateApp.ENABLING_BLUETOOTH;                                         //Are going to request user to turn on Bluetooth
                Log.i(TAG, "Requesting user to enable Bluetooth radio");
            }
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();                                                                            //Call superclass (AppCompatActivity) onPause method
        unregisterReceiver(bleServiceReceiver);                                                     //Unregister receiver that was registered in onResume()
    }

    @Override
    public void onStop() {
        super.onStop();                                                                             //Call superclass (AppCompatActivity) onStop method
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();                                                                          //Call superclass (AppCompatActivity) onDestroy method
        if (stateApp != StateApp.REQUEST_PERMISSION) {                                              //See if we got past the permission request
            unbindService(bleServiceConnection);
            timer.cancel();
        }
    }

    private final ServiceConnection bleServiceConnection = new ServiceConnection() {                //Create new ServiceConnection interface to handle connection and disconnection

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {              //Service connects
            try {
                Log.i(TAG, "BleService connected");
                BleService.LocalBinder binder = (BleService.LocalBinder) service;                   //Get the Binder for the Service
                bleService = binder.getService();                                                   //Get a link to the Service from the Binder
            } catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {                            //BleService disconnects - should never happen
            Log.i(TAG, "BleService disconnected");
            bleService = null;                                                                      //Not bound to BleService
        }
    };

    private static IntentFilter bleServiceIntentFilter() {                                          //Method to create and return an IntentFilter
        final IntentFilter intentFilter = new IntentFilter();                                       //Create a new IntentFilter
        intentFilter.addAction(BleService.ACTION_BLE_CONNECTED);                                    //Add filter for receiving an Intent from BleService announcing a new connection
        intentFilter.addAction(BleService.ACTION_BLE_DISCONNECTED);                                 //Add filter for receiving an Intent from BleService announcing a disconnection
        intentFilter.addAction(BleService.ACTION_BLE_DISCOVERY_DONE);                               //Add filter for receiving an Intent from BleService announcing a service discovery
        intentFilter.addAction(BleService.ACTION_BLE_DISCOVERY_FAILED);                             //Add filter for receiving an Intent from BleService announcing failure of service discovery
        intentFilter.addAction(BleService.ACTION_BLE_NEW_DATA_RECEIVED);                            //Add filter for receiving an Intent from BleService announcing new data received
        return intentFilter;                                                                        //Return the new IntentFilter
    }

    private final BroadcastReceiver bleServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {                                     //Intent received
            final String action = intent.getAction();                                               //Get the action String from the Intent
            switch (action) {                                                                       //See which action was in the Intent
                case BleService.ACTION_BLE_CONNECTED: {                                             //Have connected to BLE device
                    Log.d(TAG, "Received Intent  ACTION_BLE_CONNECTED");
                    Toast.makeText(getApplicationContext(),"CONNECTED", Toast.LENGTH_SHORT).show();
                    initializeDisplay();                                                            //Clear the temperature and accelerometer text and graphs
                    transparentUartData.reset();                                                    //Also clear any buffered incoming data
                    stateConnection = StateConnection.DISCOVERING;                                  //BleService automatically starts service discovery after connecting
                    break;
                }
                case BleService.ACTION_BLE_DISCONNECTED: {                                          //Have disconnected from BLE device
                    Log.d(TAG, "Received Intent ACTION_BLE_DISCONNECTED");
                    initializeDisplay();                                                            //Clear the temperature and accelerometer text and graphs
                    transparentUartData.reset();                                                    //Also clear any buffered incoming data
                    Toast.makeText(getApplicationContext(),"DISCONNECTED", Toast.LENGTH_SHORT).show();
                    stateConnection = StateConnection.DISCONNECTED;                                 //Are disconnected
                    break;
                }
                case BleService.ACTION_BLE_DISCOVERY_DONE: {                                        //Have completed service discovery
                    Log.d(TAG, "Received Intent  ACTION_BLE_DISCOVERY_DONE");
                    connectTimeoutHandler.removeCallbacks(abandonConnectionAttempt);                //Stop the connection timeout handler from calling the runnable to stop the connection attempt
                    stateConnection = StateConnection.CONNECTED;                                    //Were already connected but showing discovering, not connected
                    break;
                }
                case BleService.ACTION_BLE_DISCOVERY_FAILED: {                                      //Service discovery failed to find the right service and characteristics
                    Log.d(TAG, "Received Intent  ACTION_BLE_DISCOVERY_FAILED");
                    Toast.makeText(getApplicationContext(),"DISCONNECTED (DISCOVERY FAILED)", Toast.LENGTH_SHORT).show();
                    stateConnection = StateConnection.DISCONNECTING;                                //Were already connected but showing discovering, so are now disconnecting
                    connectTimeoutHandler.removeCallbacks(abandonConnectionAttempt);                //Stop the connection timeout handler from calling the runnable to stop the connection attempt
                    bleService.disconnectBle();                                                     //Ask the BleService to disconnect from the Bluetooth device
                    break;
                }
                case BleService.ACTION_BLE_NEW_DATA_RECEIVED: {                                     //Have received data (characteristic notification) from BLE device
                    Log.d(TAG, "Received Intent ACTION_BLE_NEW_DATA_RECEIVED");
                    final byte[] newBytes = bleService.readFromTransparentUART();
                    handleData(newBytes);
                    break;
                }
                default: {
                    Log.w(TAG, "Received Intent with invalid action: " + action);
                }
            }
        }
    };

    @SuppressLint("DefaultLocale")
    private void handleData(byte[] newBytes){
        try{
            cur = (newBytes[0]&0xFF) +  ((float)newBytes[1] /100);
            mCurHr.setText(String.format(String.valueOf(cur)));
            max = Math.max(max, cur);
            mMaxHr.setText(String.format(String.valueOf(max)));
            min = Math.min(min,cur);
            mMinHr.setText(String.format(String.valueOf(min)));
            hrSeries.appendData(new DataPoint(time, cur), true, 600);
        }
        catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {                                 //See if location permission was granted
            Log.i(TAG, "Location permission granted");
            stateApp = StateApp.STARTING_SERVICE;
            Intent bleServiceIntent = new Intent(this, BleService.class);
            this.bindService(bleServiceIntent, bleServiceConnection, BIND_AUTO_CREATE);
        } else {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);               //Create Intent to open the app settings page
            Uri uri = Uri.fromParts("package", getPackageName(), null);            //Identify the package for the settings
            intent.setData(uri);                                                                    //Add the package to the Intent
            startActivity(intent);                                                             //Start the settings activity
        }
    }

    private void connectWithAddress(String address) {
        try {
//            connectTimeoutHandler.postDelayed(abandonConnectionAttempt, CONNECT_TIMEOUT);           //Start a delayed runnable to time out if connection does not occur
            bleService.connectBle(address);                                                         //Ask the BleService to connect to the device
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    private Runnable abandonConnectionAttempt = new Runnable() {
        @Override
        public void run() {
            try {
                stateConnection = StateConnection.DISCONNECTING;                                //Are now disconnecting
                bleService.disconnectBle();                                                     //Stop the Bluetooth connection attempt in progress
            } catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }
    };

    private void startBleScanActivity() {
        try {
            stateConnection = StateConnection.DISCONNECTING;
            bleService.disconnectBle();
            Intent bleScanActivityIntent = new Intent(MainActivity.this, BleScanActivity.class); //Create Intent to start the BleScanActivity
            startForResult.launch(bleScanActivityIntent);             //Start the BleScanActivity
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    public static class HeartRateData implements android.os.Parcelable {
        int nhiptimhientai;
        int nhiptimlonnhat;
        int nhiptimnhonhat;
        int nhiptimtrungbinh;
        String thoigian;

        HeartRateData(int currentHeartRate, int maxHeartRate, int minHeartRate, int averageHeartRate, String time) {
            this.nhiptimhientai = currentHeartRate;
            this.nhiptimlonnhat = maxHeartRate;
            this.nhiptimnhonhat = minHeartRate;
            this.nhiptimtrungbinh = averageHeartRate;
            this.thoigian = time;
        }

        protected HeartRateData(android.os.Parcel in) {
            nhiptimhientai = in.readInt();
            nhiptimlonnhat = in.readInt();
            nhiptimnhonhat = in.readInt();
            nhiptimtrungbinh = in.readInt();
            thoigian = in.readString();
        }

        public static final Creator<HeartRateData> CREATOR = new Creator<HeartRateData>() {
            @Override
            public HeartRateData createFromParcel(android.os.Parcel in) {
                return new HeartRateData(in);
            }

            @Override
            public HeartRateData[] newArray(int size) {
                return new HeartRateData[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(nhiptimhientai);
            dest.writeInt(nhiptimlonnhat);
            dest.writeInt(nhiptimnhonhat);
            dest.writeInt(nhiptimtrungbinh);
            dest.writeString(thoigian);
        }

    }

    @SuppressLint("SetTextI18n")
    private void initializeDisplay() {
        try {
            mCurHr.setText("");
            mMaxHr.setText("");
            mMinHr.setText("");
            mAvrHr.setText("");
            mTime.setText("00: 00: 00");
            max=0;
            min=200;
            sum=0;
            time=0.0;
            hrSeries.resetData(new DataPoint[0]);
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

}
