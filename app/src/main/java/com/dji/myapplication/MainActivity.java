package com.dji.myapplication;

import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import dji.sdk.keyvalue.key.CameraKey;
import dji.sdk.keyvalue.key.DJIKey;
import dji.sdk.keyvalue.key.FlightControllerKey;
import dji.sdk.keyvalue.key.GimbalKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.camera.CameraMode;
import dji.sdk.keyvalue.value.common.EmptyMsg;
import dji.sdk.keyvalue.value.common.LocationCoordinate2D;
import dji.sdk.keyvalue.value.common.Velocity3D;
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem;
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode;
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode;
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam;
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode;
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation;
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.common.register.DJISDKInitEvent;
import dji.v5.manager.KeyManager;
import dji.v5.manager.SDKManager;
import dji.v5.manager.aircraft.simulator.InitializationSettings;
import dji.v5.manager.aircraft.simulator.SimulatorManager;
import dji.v5.manager.aircraft.simulator.SimulatorState;
import dji.v5.manager.aircraft.simulator.SimulatorStatusListener;
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager;
import dji.v5.manager.interfaces.SDKManagerCallback;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private Button startSimulator;
    private Button stopSimulator;
    private Button enableVirtualSticks;
    private Button disableVirtualSticks;
    private Button startMission;
    private Button stopMission;

    private TextView isDroneConnected;
    private TextView battery;
    private TextView isSimulatorOn;
    private TextView isVirtualSticksOn;
    private TextView areMotorsOn;
    private TextView isFlying;
    private TextView pitch;
    private TextView roll;
    private TextView yaw;
    private TextView positionXYZ;
//    private TextView location;
    private TextView speed;

//    private MapView mapView;
    private GoogleMap gMap;
    private Marker droneMarker = null;


    // Other stuff
    DecimalFormat decimalFormat = new DecimalFormat("#.#");

    boolean goFly = true;
    boolean takePhoto = false;
    boolean isResumable = false;
    private float droneLatitude;
    private float droneLongitude;
    private float droneHeading;
    double posX;
    double posY;
    double posZ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        initUI();
//        mapView.onCreate(savedInstanceState);
        registerApp();
    }

    public void initUI() {
        startSimulator = findViewById(R.id.button_start_simulator);
        stopSimulator = findViewById(R.id.button_stop_simulator);
        enableVirtualSticks = findViewById(R.id.button_enable_virtual_sticks);
        disableVirtualSticks = findViewById(R.id.button_disable_virtual_sticks);
        startMission = findViewById(R.id.button_start_mission);
        stopMission = findViewById(R.id.button_stop_mission);

        isDroneConnected = findViewById(R.id.textview_is_drone_connected);
        battery = findViewById(R.id.textview_battery);
        isSimulatorOn = findViewById(R.id.textview_is_simulator_on);
        isVirtualSticksOn = findViewById(R.id.textview_is_virtual_sticks_on);
        areMotorsOn = findViewById(R.id.textview_are_motors_on);
        isFlying = findViewById(R.id.textview_is_flying);
        pitch = findViewById(R.id.textview_pitch);
        roll = findViewById(R.id.textview_roll);
        yaw = findViewById(R.id.textview_yaw);
        positionXYZ = findViewById(R.id.textview_position_x_y_z);
//        location = findViewById(R.id.textview_location);
        speed = findViewById(R.id.textview_speed);


        startMission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.i(TAG, "startMission - onClick");

                if (!isResumable) {
                    isResumable = true;
                } else {
                    goFly = true;
                    return;
                }

                List<Pair<Integer, Integer>> coordinatePairs = new ArrayList<>();
                coordinatePairs.add(new Pair<>(20, 0));
                coordinatePairs.add(new Pair<>(20, -20));
                coordinatePairs.add(new Pair<>(0, -20));
                coordinatePairs.add(new Pair<>(0, 0));

                double speed = 1;

                List<Pair<Double, Double>> rollPitchPairs = new ArrayList<>();
                rollPitchPairs.add(new Pair<>(speed, 0.));
                rollPitchPairs.add(new Pair<>(0., -speed));
                rollPitchPairs.add(new Pair<>(-speed, 0.));
                rollPitchPairs.add(new Pair<>(0., speed));

                List<Double> yaws = new ArrayList<>();
                yaws.add(0.);
                yaws.add(-90.);
                yaws.add(180.);
                yaws.add(90.);

                // Start the loop in a separate thread
                Thread loopThread = new Thread(new Runnable() {
                    int current = 0;

                    @Override
                    public void run() {

                        while (true) {

                            double altitude = 10.0;
                            double verticalThrottle = 0;
                            double yaw = yaws.get(current);
                            double roll = rollPitchPairs.get(current).first;
                            double pitch = rollPitchPairs.get(current).second;

                            double targetX = coordinatePairs.get(current).first;
                            double targetY = coordinatePairs.get(current).second;

                            double diffX = targetX - posX;
                            double diffY = targetY - posY;

                            double distance = Math.sqrt(diffX*diffX + diffY*diffY);
                            Log.i(TAG, "Distance to target: " + distance);
                            if (distance <= 1) {
                                goFly = false;
                                takePhoto = true;

                                current++;
                                Log.i(TAG, "Next target: " + current);
                            }

                            if (goFly) {
                                VirtualStickFlightControlParam param = new VirtualStickFlightControlParam();

                                param.setRollPitchCoordinateSystem(FlightCoordinateSystem.GROUND);
                                param.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
                                param.setVerticalControlMode(VerticalControlMode.POSITION);
                                param.setYawControlMode(YawControlMode.ANGLE);

                                param.setVerticalThrottle(verticalThrottle);
                                param.setYaw(yaw);
                                param.setPitch(pitch);
                                param.setRoll(roll);

                                KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeySendVirtualStickFlightControlData), param, new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                                    @Override
                                    public void onSuccess(EmptyMsg emptyMsg) {
                                        Log.i(TAG, "KeySendVirtualStickFlightControlData - onSuccess");
                                    }

                                    @Override
                                    public void onFailure(@NonNull IDJIError error) {
                                        Log.e(TAG, "KeySendVirtualStickFlightControlData - onFailure: " + error);
                                    }
                                });
                            }

                            if (takePhoto) {
                                GimbalAngleRotation param = new GimbalAngleRotation();
                                param.setMode(GimbalAngleRotationMode.ABSOLUTE_ANGLE);
                                param.setPitch(20.);
                                param.setRoll(0.);
                                param.setYaw(0.);

                                KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle), param, new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                                    @Override
                                    public void onSuccess(EmptyMsg emptyMsg) {
                                        Log.i(TAG, "KeyRotateByAngle - onSuccess");

                                        takePhoto = false;

                                        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCameraMode, 0), CameraMode.PHOTO_NORMAL, new CommonCallbacks.CompletionCallback() {
                                            @Override
                                            public void onSuccess() {
                                                Log.i(TAG, "KeyCameraMode - onSuccess");

                                                KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto), new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                                                    @Override
                                                    public void onSuccess(EmptyMsg emptyMsg) {
                                                        Log.i(TAG, "KeyStartShootPhoto - onSuccess");
                                                    }

                                                    @Override
                                                    public void onFailure(@NonNull IDJIError error) {
                                                        Log.e(TAG, "KeyStartShootPhoto - onFailure: " + error);
                                                    }
                                                });
                                            }

                                            @Override
                                            public void onFailure(@NonNull IDJIError error) {
                                                Log.e(TAG, "KeyCameraMode - onFailure: " + error);
                                            }
                                        });
                                    }

                                    @Override
                                    public void onFailure(@NonNull IDJIError error) {
                                        Log.e(TAG, "KeyRotateByAngle - onFailure: " + error);
                                    }
                                });
                            }

                            // Adjust the sleep time as needed
                            try {
                                Thread.sleep(200); // Sleep for 200ms
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });

                loopThread.start();
            }
        });

        stopMission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                goFly = false;
            }
        });

        startSimulator.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LocationCoordinate2D home = new LocationCoordinate2D(43.062496, 12.550976);
                InitializationSettings data = InitializationSettings.createInstance(home, 15);

                SimulatorManager.getInstance().enableSimulator(data, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onSuccess() {
                        isSimulatorOn.setText("true");
                        Log.i(TAG, "enableSimulator - onSuccess");
                        setSimulatorStateListener();

                        LatLng pos = new LatLng(home.latitude, home.longitude);
                        gMap.addMarker(new MarkerOptions()
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                                .position(pos));
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError error) {
                        isSimulatorOn.setText("error");
                        Log.i(TAG, "enableSimulator - onFailure: " + error);
                    }
                });
            }
        });

        stopSimulator.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SimulatorManager.getInstance().disableSimulator(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onSuccess() {
                        isSimulatorOn.setText("false");
                        Log.i(TAG, "disableSimulator - onSuccess");
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError error) {
                        isSimulatorOn.setText("error");
                        Log.i(TAG, "disableSimulator - onFailure: " + error);
                    }
                });
            }
        });

        enableVirtualSticks.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VirtualStickManager.getInstance().enableVirtualStick(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onSuccess() {
                        isVirtualSticksOn.setText("true");
                        Log.i(TAG, "enableVirtualSticks - onSuccess");

                        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true);
                        Log.i(TAG, "enableVirtualSticks - setVirtualStickAdvancedModeEnabled - done");
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError error) {
                        isVirtualSticksOn.setText("error");
                        Log.i(TAG, "enableVirtualSticks - onFailure: " + error);
                    }
                });
            }
        });

        disableVirtualSticks.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VirtualStickManager.getInstance().disableVirtualStick(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onSuccess() {
                        isVirtualSticksOn.setText("false");
                        Log.i(TAG, "disableVirtualSticks - onSuccess");
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError error) {
                        isVirtualSticksOn.setText("error");
                        Log.i(TAG, "disableVirtualSticks - onFailure: " + error);
                    }
                });
            }
        });

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map_view);

        mapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(@NonNull GoogleMap googleMap) {
                if (gMap == null) {
                    gMap = googleMap;
                    initMapView();
                }
            }
        });
    }

    private void initMapView() {
        // Add a marker to the map
        gMap.moveCamera(CameraUpdateFactory.zoomTo(20));
        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        updateDroneLocation();
        cameraUpdate();
    }

    // Update the drone location based on states from MCU.
    private void updateDroneLocation() {
        LatLng pos = new LatLng(droneLatitude, droneLongitude);
        // Create MarkerOptions object
        final MarkerOptions markerOptions = new MarkerOptions();
        markerOptions.position(pos);
        markerOptions.icon(BitmapDescriptorFactory.fromResource(R.drawable.aircraft));

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (droneMarker != null) {
                    droneMarker.remove();
                }

                if (checkGpsCoordination(droneLatitude, droneLongitude)) {
                    droneMarker = gMap.addMarker(markerOptions);
                    droneMarker.setRotation(droneHeading * 1.0f);
                    cameraUpdate();
                }
            }
        });
    }

    private void cameraUpdate() {
        LatLng pos = new LatLng(droneLatitude, droneLongitude);
        CameraUpdate cu = CameraUpdateFactory.newLatLng(pos);
        gMap.moveCamera(cu);
    }

    public static boolean checkGpsCoordination(double latitude, double longitude) {
        return (latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180) && (latitude != 0f && longitude != 0f);
    }

    private void setSimulatorStateListener() {
        SimulatorManager.getInstance().addSimulatorStateListener(new SimulatorStatusListener() {
            @Override
            public void onUpdate(@NonNull SimulatorState state) {
                Log.i(TAG, "addSimulatorStateListener - onUpdate");
//                Log.i(TAG, "areMotorsOn:" + state.areMotorsOn());
//                Log.i(TAG, "isFlying:" + state.isFlying());
//                Log.i(TAG, "getPitch:" + state.getPitch());
//                Log.i(TAG, "getRoll:" + state.getRoll());
//                Log.i(TAG, "getYaw:" + state.getYaw());
//                Log.i(TAG, "getPositionX:" + state.getPositionX());
//                Log.i(TAG, "getPositionY:" + state.getPositionY());
//                Log.i(TAG, "getPositionZ:" + state.getPositionZ());
//                Log.i(TAG, "getLocation:" + state.getLocation());

                areMotorsOn.setText(String.valueOf(state.areMotorsOn()));
                isFlying.setText(String.valueOf(state.isFlying()));
                pitch.setText(String.valueOf(decimalFormat.format(state.getPitch())));
                roll.setText(String.valueOf(decimalFormat.format(state.getRoll())));
                yaw.setText(String.valueOf(decimalFormat.format(state.getYaw())));
                positionXYZ.setText(
                        String.valueOf(decimalFormat.format(state.getPositionX())) + ", " +
                        String.valueOf(decimalFormat.format(state.getPositionY())) + ", " +
                        String.valueOf(decimalFormat.format(state.getPositionZ()))
                );
//                location.setText(state.getLocation().getLatitude());

                droneLatitude = state.getLocation().getLatitude().floatValue();
                droneLongitude = state.getLocation().getLongitude().floatValue();
                droneHeading = state.getYaw();

                posX = state.getPositionX();
                posY = state.getPositionY();
                posZ = state.getPositionZ();

                updateDroneLocation();
            }
        });
    }

    private void registerApp() {
        SDKManager.getInstance().init(this, new SDKManagerCallback() {
            @Override
            public void onRegisterSuccess() {
                Log.i(TAG, "onRegisterSuccess: ");
            }

            @Override
            public void onRegisterFailure(IDJIError error) {
                Log.i(TAG, "onRegisterFailure: " + error);
            }

            @Override
            public void onProductDisconnect(int productId) {
                Log.i(TAG, "onProductDisconnect: " + productId);
            }

            @Override
            public void onProductConnect(int productId) {
                Log.i(TAG, "onProductConnect: " + productId);

                KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyConnection), this, new CommonCallbacks.KeyListener<Boolean>() {

                    @Override
                    public void onValueChange(@Nullable Boolean oldValue, @Nullable Boolean newValue) {
                        Log.i(TAG, "KeyConnection onValueChange: " + newValue);
                        if (newValue != null) {
                            isDroneConnected.setText(newValue.toString());
                        } else {
                            isDroneConnected.setText("false");
                        }
                    }
                });

                KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyBatteryPowerPercent), this, new CommonCallbacks.KeyListener<Integer>() {
                    @Override
                    public void onValueChange(@Nullable Integer oldValue, @Nullable Integer newValue) {
                        Log.i(TAG, "KeyBatteryPowerPercent onValueChange: " + newValue);
                        if (newValue != null) {
                            battery.setText(newValue.toString());
                        } else {
                            battery.setText("-");
                        }
                    }
                });

                KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this, new CommonCallbacks.KeyListener<Velocity3D>() {
                    @Override
                    public void onValueChange(@Nullable Velocity3D oldValue, @Nullable Velocity3D newValue) {
                        if (newValue != null) {
                            Log.i(TAG, "KeyAircraftVelocity onValueChange: " + newValue.toString());
                            if (newValue.getX() != null) {
                                speed.setText(
                                        String.valueOf(decimalFormat.format(newValue.getX())) + ", " +
                                        String.valueOf(decimalFormat.format(newValue.getY())) + ", " +
                                        String.valueOf(decimalFormat.format(newValue.getZ()))
                                );
                            } else {
                                speed.setText("-");
                            }
                        }
                    }
                });

            }

            @Override
            public void onProductChanged(int productId) {
                Log.i(TAG, "onProductChanged: " + productId);
            }

            @Override
            public void onInitProcess(DJISDKInitEvent event, int totalProcess) {
                Log.i(TAG, "onInitProcess: ");
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    SDKManager.getInstance().registerApp();
                }
            }

            @Override
            public void onDatabaseDownloadProgress(long current, long total) {
                Log.i(TAG, "onDatabaseDownloadProgress: " + current / total);
            }
        });
    }
}
