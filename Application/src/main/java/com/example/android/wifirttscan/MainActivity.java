/*
 * Copyright (C) 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.wifirttscan;

import static com.example.android.wifirttscan.AccessPointRangingResultsActivity.SCAN_RESULT_EXTRA;

import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;

import android.os.WorkSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.example.android.wifirttscan.MyAdapter.ScanResultClickListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import me.weishu.reflection.Reflection;

/**
 * Displays list of Access Points enabled with WifiRTT (to check distance). Requests location
 * permissions if they are not approved via secondary splash screen explaining why they are needed.
 */
public class MainActivity extends AppCompatActivity implements ScanResultClickListener {

    private static final String TAG = "MainActivity";

    private boolean mLocationPermissionApproved = false;

    List<ScanResult> mAccessPoints;
    List<ScanResult> mAccessPointsSupporting80211mc;

    private WifiManager mWifiManager;
    private WifiScanReceiver mWifiScanReceiver;

    // 代码默认用户手机支持802.11mc
    private WifiRttManager mWifiRttManager_init;

    private TextView mOutputTextView;
    private RecyclerView mRecyclerView;

    private MyAdapter mAdapter;

    private int mMillisecondsDelayBeforeNewRangingRequest;

    // 设备扫描的轮数
    private int mTurn;

    private RttRangingResultCallback mRttRangingResultCallback;

    // Triggers additional RangingRequests with delay (mMillisecondsDelayBeforeNewRangingRequest).
    final Handler mRangeRequestDelayHandler = new Handler();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Reflection.unseal(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mOutputTextView = findViewById(R.id.access_point_summary_text_view);
        mRecyclerView = findViewById(R.id.recycler_view);

        // Improve performance if you know that changes in content do not change the layout size
        // of the RecyclerView
        mRecyclerView.setHasFixedSize(true);

        // use a linear layout manager
        LayoutManager layoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(layoutManager);

        // 支持80211mc协议的设备列表
        mAccessPointsSupporting80211mc = new ArrayList<>();

        // 扫描到的全部设备列表
        mAccessPoints = new ArrayList<>();

        //mAdapter = new MyAdapter(mAccessPointsSupporting80211mc, this);
        mAdapter = new MyAdapter(new ArrayList<ScanResult>(), this);
        mRecyclerView.setAdapter(mAdapter);

        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mWifiScanReceiver = new WifiScanReceiver();

        mWifiRttManager_init = (WifiRttManager) getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
        mRttRangingResultCallback = new RttRangingResultCallback();

        mTurn = 1;

        mMillisecondsDelayBeforeNewRangingRequest = 500;

    }

    private void startRangingRequest() throws ClassNotFoundException {
        // Permission for fine location should already be granted via MainActivity (you can't get
        // to this class unless you already have permission. If they get to this class, then disable
        // fine location permission, we kick them back to main activity.
        if (ActivityCompat.checkSelfPermission(this, permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            finish();
        }

        int num = mAccessPoints.size();
        //RangingRequest rangingRequest;

        if (mTurn*10 <= num){
            // 构造一个目标为mScanResult的Ranging请求数据包
            RangingRequest rangingRequest = new RangingRequest.Builder().addAccessPoints(mAccessPoints.subList((mTurn-1)*10,mTurn*10)).build();
            // 开始向mScanResult请求测距
            mWifiRttManager_init.startRanging(
                    rangingRequest, getApplication().getMainExecutor(), mRttRangingResultCallback);
        }
        else if((mTurn*10 > num) && (mTurn*10 -num < 10)){
            int temp = num-(mTurn-1)*10;
            RangingRequest rangingRequest = new RangingRequest.Builder().addAccessPoints(mAccessPoints.subList((mTurn-1)*10,num)).build();
            // 开始向mScanResult请求测距
            mWifiRttManager_init.startRanging(
                    rangingRequest, getApplication().getMainExecutor(), mRttRangingResultCallback);
        }
        else{

            mAdapter.swapData(mAccessPointsSupporting80211mc);

            logToUi(
                    mAccessPoints.size()
                            + " APs discovered, "
                            + mAccessPointsSupporting80211mc.size()
                            + " RTT capable.");

            mTurn = 1;

            // 删除已经发现的设备，开启新一轮搜索
            mAccessPointsSupporting80211mc.clear();

            return;
        }

        mTurn += 1;

    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();

        getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_RTT);

        mLocationPermissionApproved =
                ActivityCompat.checkSelfPermission(this, permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        registerReceiver(
                mWifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause()");
        super.onPause();
        unregisterReceiver(mWifiScanReceiver);
    }

    private void logToUi(final String message) {
        if (!message.isEmpty()) {
            Log.d(TAG, message);
            mOutputTextView.setText(message);
        }
    }

    @Override
    public void onScanResultItemClick(ScanResult scanResult) {
        Log.d(TAG, "onScanResultItemClick(): ssid: " + scanResult.SSID);

        Intent intent = new Intent(this, AccessPointRangingResultsActivity.class);
        intent.putExtra(SCAN_RESULT_EXTRA, scanResult);
        startActivity(intent);
    }

    public void onClickFindDistancesToAccessPoints(View view) {
        if (mLocationPermissionApproved) {
            logToUi(getString(R.string.retrieving_access_points));
            mWifiManager.startScan();

        } else {
            // On 23+ (M+) devices, fine location permission not granted. Request permission.
            Intent startIntent = new Intent(this, LocationPermissionRequestActivity.class);
            startActivity(startIntent);
        }
    }

    private class WifiScanReceiver extends BroadcastReceiver {

        // 这个函数用于修改Scanresult的mc支持标志位
        public void changFlagfield(List<ScanResult> list) {
            Class policyClass;
            Field field;
            try {
                //policyClass = Class.forName("android.net.wifi.ScanResult.RadioChainInfo");
                policyClass = Class.forName("android.net.wifi.ScanResult");
                field = policyClass.getField("flags");
                field.setAccessible(true);
                for (ScanResult result : list){
                    field.set(result, 0x000000000000FFFF);
                }
            } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
                e.printStackTrace();
            }

        }

        // This is checked via mLocationPermissionApproved boolean
        @SuppressLint("MissingPermission")
        public void onReceive(Context context, Intent intent) {

            mAccessPoints = mWifiManager.getScanResults();

            //修改mc标示位
            changFlagfield(mAccessPoints);

            if (mAccessPoints != null) {
                try {
                    mAccessPointsSupporting80211mc.clear();
                    mTurn = 1;
                    startRangingRequest();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    //
    private class RttRangingResultCallback extends RangingResultCallback {

        // Deprecated：间隔一定时间后发送下一个测距请求
        private void queueNextRangingRequest() {
            mRangeRequestDelayHandler.postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                mTurn -= 1;
                                startRangingRequest();
                            } catch (ClassNotFoundException e) {
                                e.printStackTrace();
                            }
                        }
                    },
                    mMillisecondsDelayBeforeNewRangingRequest);
        }

        // Deprecated：测距失败处理函数
        @Override
        public void onRangingFailure(int code) {
            Log.d(TAG, "onRangingFailure() code: " + code);
            //queueNextRangingRequest();
            try {
                startRangingRequest();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onRangingResults(@NonNull List<RangingResult> list) {
            // 节点能成功测距，说明支持802.11mc协议，将其添加到mc支持设备列表中
            Log.d(TAG, "onRangingResults(): " + list);

            // Because we are only requesting RangingResult for one access point (not multiple
            // access points), this will only ever be one. (Use loops when requesting RangingResults
            // for multiple access points.)

            for (RangingResult rangingResult : list) {
                // 搜索RangingResult MAC地址对应的WiFi节点

                if (rangingResult.getStatus() == RangingResult.STATUS_SUCCESS) {
                    for (ScanResult result : mAccessPoints) {
                        String mac = rangingResult.getMacAddress().toString();
                        if (result.BSSID.equals(mac)) {
                            mAccessPointsSupporting80211mc.add(result);
                        }
                    }
                }

            }

            // 开始新一轮探测,直到全部探测到为止
            try {
                startRangingRequest();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            //cancelRanging(null);
        }

    }
}
