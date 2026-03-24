package it.unipg.gearlab;

import static it.unipg.gearlab.MissionFileRepository.copyFileToTempFolder;
import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconRotate;

import android.animation.ObjectAnimator;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Point;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import dji.sdk.keyvalue.key.CameraKey;
import dji.sdk.keyvalue.key.FlightControllerKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.sdk.keyvalue.value.common.EmptyMsg;
import dji.sdk.keyvalue.value.common.LocationCoordinate2D;
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem;
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel;
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode;
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode;
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam;
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode;
import dji.sdk.keyvalue.value.common.DoubleMinMax;
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation;
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode;
import dji.sdk.keyvalue.value.gimbal.GimbalAttitudeRange;
import dji.sdk.wpmz.value.mission.ActionAircraftHoverParam;
import dji.sdk.wpmz.value.mission.ActionGimbalRotateParam;
import dji.sdk.wpmz.value.mission.ActionTakePhotoParam;
import dji.sdk.wpmz.value.mission.WaylineActionGroup;
import dji.sdk.wpmz.value.mission.WaylineActionInfo;
import dji.sdk.wpmz.value.mission.WaylineActionType;
import dji.sdk.wpmz.value.mission.WaylineExecuteWaypoint;
import dji.sdk.wpmz.value.mission.WaylineGimbalActuatorRotateMode;
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate2D;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.common.register.DJISDKInitEvent;
import dji.v5.manager.KeyManager;
import dji.v5.manager.SDKManager;
import dji.v5.manager.aircraft.simulator.InitializationSettings;
import dji.v5.manager.aircraft.simulator.SimulatorManager;
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager;
import dji.v5.manager.datacenter.MediaDataCenter;
import dji.v5.manager.datacenter.camera.CameraStreamManager;
import dji.v5.manager.datacenter.media.MediaFile;
import dji.v5.manager.datacenter.media.MediaFileDownloadListener;
import dji.v5.manager.datacenter.media.MediaFileListData;
import dji.v5.manager.datacenter.media.MediaFileListDataSource;
import dji.v5.manager.datacenter.media.MediaManager;
import dji.v5.manager.datacenter.media.PullMediaFileListParam;
import dji.v5.manager.interfaces.ICameraStreamManager;
import dji.v5.manager.interfaces.SDKManagerCallback;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String FALLBACK_MAP_STYLE_URL = "https://demotiles.maplibre.org/style.json";
    private static final double EARTH_RADIUS_METERS = 6378137.0;
    private static final long VS_MISSION_TICK_MS = 200L;
    private static final int PICK_KMZ_FILE_REQUEST = 1234;
    private static final double MANUAL_WP_LAT = 43.06229630922861;
    private static final double MANUAL_WP_LON = 12.549784090599045;
    private static final double MANUAL_WP_HEIGHT = 10.0;
    private static final double[] MANUAL_PITCH_SWEEP_DEG = new double[]{-80, -70, -60, -50, -40, -30, -20, -10, 0};
    private final DecimalFormat decimalFormat = new DecimalFormat("#.##");
    private final DecimalFormat integerFormat = new DecimalFormat("#");
    private final LocationCoordinate2D droneHomeLocation = new LocationCoordinate2D(0., 0.);
    private final LocationCoordinate2D droneCurrentLocation = new LocationCoordinate2D(0., 0.);
    // Other stuff
    private Button btnStartStopMission;
    private Button btnPauseResumeMission;
    private TextView tvDroneConnected;
    private TextView tvBatteryPercentage;
    private TextView tvSimulator;
    private TextView tvDroneYaw;
    private TextView tvGimbalPitch;
    private TextView tvDroneAltitude;
    private TextView tvDroneSpeed;
    //    private TextView tvGimbalPitchYaw;
//    private TextView tvRtk;
    private TextView tvHome;
    private TextView tvLog;
    private MapView mapView;
    private MapLibreMap gMap;
    private Style mapStyle;
    private SurfaceView svCameraStream;
    private boolean isRightPanelOpen = true;
    private boolean isLeftPanelOpen = true;
    private boolean isVirtualStickEnabled = false;
    private boolean isSimulatorEnabled = false;
    private boolean mapFallbackApplied = false;
    private boolean hasEffectiveHome = false;
    private boolean isEffectiveHomeFromSimulation = false;
    private boolean isSetHomeInProgress = false;
    private double droneHeading;
    private double droneAltitude;
    private String currentMissionPath;
    private long missionStartTimestampMs = 0L;
    private final List<WaylineExecuteWaypoint> missionWaypoints = new ArrayList<>();
    private final List<WaylineActionGroup> missionActionGroups = new ArrayList<>();
    private MissionPlanner missionPlanner;
    private DroneCommander droneCommander;
    private DroneStateRepository droneStateRepository;
    private MissionMapRenderer missionMapRenderer;
    private final Handler vsMissionHandler = new Handler(Looper.getMainLooper());
    private boolean isMissionLoaded = false;
    private final Runnable virtualStickMissionLoop = new Runnable() {
        @Override
        public void run() {
            runVirtualStickMissionTick();
            if (missionPlanner != null && missionPlanner.isMissionStarted() && !missionPlanner.isMissionPaused()) {
                vsMissionHandler.postDelayed(this, VS_MISSION_TICK_MS);
            }
        }
    };

    private static final class DownloadTarget {
        @Nullable
        final Uri uri;
        @NonNull
        final OutputStream outputStream;

        private DownloadTarget(@Nullable Uri uri, @NonNull OutputStream outputStream) {
            this.uri = uri;
            this.outputStream = outputStream;
        }
    }

    public static boolean checkGPSCoordinates(double latitude, double longitude) {
        return (latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180) && (latitude != 0f && longitude != 0f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapLibre.getInstance(this);

        setAppStyle();

        setContentView(R.layout.activity_main);
        initUI(savedInstanceState);
        initMissionPlanner();
        initRGBCamera();
        registerApp();

        // Again!
        setAppStyle();
    }

    private void initMissionPlanner() {
        droneCommander = new DroneCommander();
        droneStateRepository = new DroneStateRepository();
        missionPlanner = new MissionPlanner(new MissionPlanner.Callbacks() {
            @Override
            public void sendVirtualStickCommand(double pitch, double roll, double yawAngleDeg, double verticalVelocity) {
                if (droneCommander != null) {
                    droneCommander.sendVirtualStickCommand(pitch, roll, yawAngleDeg, verticalVelocity, droneHeading);
                }
            }

            @Override
            public void executeGimbalRotate(@Nullable ActionGimbalRotateParam param) {
                if (droneCommander != null) {
                    droneCommander.executeGimbalRotate(param);
                }
            }

            @Override
            public void executeTakePhoto(@Nullable ActionTakePhotoParam param, @NonNull MissionPlanner.TakePhotoCallback callback) {
                if (droneCommander != null) {
                    droneCommander.executeTakePhoto(param, callback::onComplete);
                } else {
                    callback.onComplete(false, "droneCommander null");
                }
            }

            @Override
            public void onPlannerStatus(@NonNull String status) {
                runOnUiThread(() -> tvLog.setText(status));
            }

            @Override
            public void onPlannerLog(@NonNull String line) {
                Log.i(TAG, line);
            }

            @Override
            public void onMissionComplete() {
                runOnUiThread(() -> {
                    btnStartStopMission.setText(getString(R.string.start_mission_button));
                    btnPauseResumeMission.setEnabled(false);
                    btnPauseResumeMission.setText(getString(R.string.pause_mission_button));
                    stopMissionLoop();
                    triggerMissionRTH();
                });
            }
        });
        missionMapRenderer = new MissionMapRenderer(getResources());
    }

    private void setAppStyle() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN       // Hide status bar
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // Hide navigation bar
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY // Enable immersive mode
        );

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();

        setAppStyle();

        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mapView != null) {
            mapView.onStart();
        }
    }

    @Override
    protected void onPause() {
        if (mapView != null) {
            mapView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (mapView != null) {
            mapView.onStop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mapView != null) {
            mapView.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) {
            mapView.onLowMemory();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) {
            mapView.onSaveInstanceState(outState);
        }
    }

    private void setSimulationHome() {
        if (BuildConfig.SIMULATION != 1 || hasEffectiveHome) {
            return;
        }
        setEffectiveHome(
                BuildConfig.SIMULATION_LATITUDE,
                BuildConfig.SIMULATION_LONGITUDE,
                true,
                "simulator fallback"
        );
    }

    private void setEffectiveHome(double latitude, double longitude, boolean fromSimulation, @NonNull String source) {
        if (!checkGPSCoordinates(latitude, longitude)) {
            Log.i(TAG, "Ignoring invalid effective home from " + source + ": " + latitude + ", " + longitude);
            return;
        }
        droneHomeLocation.setLatitude(latitude);
        droneHomeLocation.setLongitude(longitude);
        hasEffectiveHome = true;
        isEffectiveHomeFromSimulation = fromSimulation;
        Log.i(TAG, "Effective home set from " + source + ": " + latitude + ", " + longitude);
    }

    private void clearSimulationFallbackHomeIfAny() {
        if (!isEffectiveHomeFromSimulation) {
            return;
        }
        droneHomeLocation.setLatitude(0.0);
        droneHomeLocation.setLongitude(0.0);
        hasEffectiveHome = false;
        isEffectiveHomeFromSimulation = false;
        Log.i(TAG, "Cleared simulator fallback home");
    }

    public void initUI(@Nullable Bundle savedInstanceState) {
        SwitchMaterial switchSimulator = findViewById(R.id.switch_simulator);
        SwitchMaterial switchVirtualStick = findViewById(R.id.switch_virtual_stick);
        Button btnForceRth = findViewById(R.id.button_force_rth);
        Button btnLoadMission = findViewById(R.id.button_load_mission);
        btnStartStopMission = findViewById(R.id.button_start_stop_mission);
        btnPauseResumeMission = findViewById(R.id.button_pause_resume_mission);

        tvDroneConnected = findViewById(R.id.textview_is_drone_connected);
        tvBatteryPercentage = findViewById(R.id.textview_battery);
        tvSimulator = findViewById(R.id.textview_is_simulator_on);
        tvDroneYaw = findViewById(R.id.textview_drone_yaw);
        tvGimbalPitch = findViewById(R.id.textview_gimbal_pitch);
        tvGimbalPitch.setText(String.format(Locale.US, "%.1f", 0.0));
        tvDroneAltitude = findViewById(R.id.textview_drone_altitude);
        tvDroneSpeed = findViewById(R.id.textview_drone_speed);
//        tvGimbalPitchYaw = findViewById(R.id.textview_gimbal_pitch_yaw);

        tvLog = findViewById(R.id.textview_log);
//        tvRtk = findViewById(R.id.textview_rtk);
        tvHome = findViewById(R.id.textview_home);

        Button toggleButton = findViewById(R.id.button_toggle_view);
        svCameraStream = findViewById(R.id.sv_camera_stream);
        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        mapView.setVisibility(View.GONE);

        View slidingRightPanel = findViewById(R.id.sliding_panel_right);
        Button toggleButtonRight = findViewById(R.id.button_toggle_panel_right);

        View slidingLeftPanel = findViewById(R.id.sliding_panel_left);
        Button toggleButtonLeft = findViewById(R.id.button_toggle_panel_left);

        toggleButtonLeft.setOnClickListener(v -> {
            if (isLeftPanelOpen) {
                ObjectAnimator animator = ObjectAnimator.ofFloat(
                        slidingLeftPanel, "translationX", 0f, -slidingLeftPanel.getWidth());
                animator.setDuration(300);
                animator.start();
                toggleButtonLeft.setText(getString(R.string.slide_right));
            } else {
                ObjectAnimator animator = ObjectAnimator.ofFloat(
                        slidingLeftPanel, "translationX", -slidingLeftPanel.getWidth(), 0f);
                animator.setDuration(300);
                animator.start();
                toggleButtonLeft.setText(getString(R.string.slide_left));
            }
            isLeftPanelOpen = !isLeftPanelOpen;
        });

        toggleButtonRight.setOnClickListener(v -> {
            if (isRightPanelOpen) {
                ObjectAnimator animator = ObjectAnimator.ofFloat(
                        slidingRightPanel, "translationX", 0f, slidingRightPanel.getWidth());
                animator.setDuration(300);
                animator.start();
                toggleButtonRight.setText(getString(R.string.slide_left));
            } else {
                ObjectAnimator animator = ObjectAnimator.ofFloat(
                        slidingRightPanel, "translationX", slidingRightPanel.getWidth(), 0f);
                animator.setDuration(300);
                animator.start();
                toggleButtonRight.setText(getString(R.string.slide_right));
            }
            isRightPanelOpen = !isRightPanelOpen;
        });

        toggleButton.setOnClickListener(v -> {
            if (svCameraStream.getVisibility() == View.VISIBLE) {
                svCameraStream.setVisibility(View.GONE);
                mapView.setVisibility(View.VISIBLE);
                toggleButton.setText(getString(R.string.toggle_left_view_camera));
            } else {
                svCameraStream.setVisibility(View.VISIBLE);
                initRGBCamera();
                mapView.setVisibility(View.GONE);
                toggleButton.setText(getString(R.string.toggle_left_view_map));
            }
        });

        switchSimulator.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                Log.i(TAG, "startSimulator");
                startSimulator();
            } else {
                Log.i(TAG, "startSimulator");
                stopSimulator();
            }
        });

        switchVirtualStick.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                Log.i(TAG, "enableVirtualStick");
                enableVirtualStick();
            } else {
                Log.i(TAG, "disableVirtualStick");
                disableVirtualStick();
            }
        });

        btnForceRth.setOnClickListener(view -> {
            Log.i(TAG, "forceRTH");
            forceRTH();
        });

        btnLoadMission.setOnClickListener(view -> {
            Log.i(TAG, "loadMission");
            loadMission();
        });

        btnStartStopMission.setOnClickListener(view -> {
            if (missionPlanner != null && missionPlanner.isMissionStarted()) {
                Log.i(TAG, "stopMission - onClick");
                stopMission();
            } else {
                Log.i(TAG, "startMission - onClick");
                startMission();
            }
        });

        btnPauseResumeMission.setOnClickListener(view -> {
            if (missionPlanner != null && missionPlanner.isMissionPaused()) {
                Log.i(TAG, "resumeMission - onClick");
                resumeMission();
            } else {
                Log.i(TAG, "pauseMission - onClick");
                pauseMission();
            }
        });

        mapView.getMapAsync(map -> {
            if (gMap == null) {
                gMap = map;

                gMap.setCameraPosition(new CameraPosition.Builder()
                        .target(new LatLng(droneHomeLocation.getLatitude(), droneHomeLocation.getLongitude()))
                        .zoom(17)
                        .build());

                String key = BuildConfig.MAPTILER_API_KEY;
                // Find other maps in https://cloud.maptiler.com/maps/
                String mapId = "streets-v2";
                String styleUrl = "https://api.maptiler.com/maps/" + mapId + "/style.json?key=" + key;
                if (key == null || key.trim().isEmpty()) {
                    styleUrl = FALLBACK_MAP_STYLE_URL;
                }
                final String selectedStyleUrl = styleUrl;

                mapView.addOnDidFailLoadingMapListener(errorMessage -> {
                    Log.e(TAG, "Map style failed: " + errorMessage);
                    if (mapFallbackApplied || gMap == null) {
                        return;
                    }
                    mapFallbackApplied = true;
                    runOnUiThread(() -> gMap.setStyle(new Style.Builder().fromUri(FALLBACK_MAP_STYLE_URL), style -> {
                        mapStyle = style;
                        tvLog.setText("Map fallback style loaded");
                        updateDroneHomeAndCurrentLocation();
                    }));
                });

                gMap.setStyle(new Style.Builder().fromUri(selectedStyleUrl), style -> {
                    mapStyle = style;
                    Log.i(TAG, "Map style loaded: " + selectedStyleUrl);

                    updateDroneHomeAndCurrentLocation();
                });
            }
        });
    }

    private void cleanUpMedia(List<MediaFile> mediaFileList) {
        MediaDataCenter.getInstance().getMediaManager().enable(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                MediaManager.getInstance().deleteMediaFiles(mediaFileList, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(TAG, "Media files deleted.");
                        runOnUiThread(() -> tvLog.setText("H20 memory cleaned!"));

                        MediaDataCenter.getInstance().getMediaManager().disable(new CommonCallbacks.CompletionCallback() {
                            @Override
                            public void onSuccess() {
                                Log.i(TAG, "MediaManager disabled successfully.");
                            }

                            @Override
                            public void onFailure(@NonNull IDJIError idjiError) {
                                Log.e(TAG, "Failed to disable MediaManager: " + idjiError);
                            }
                        });
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError idjiError) {
                        Log.e(TAG, "Failed to delete media files: " + idjiError);
                    }
                });
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                Log.e(TAG, "Failed to enable MediaManager: " + idjiError);
            }
        });
    }


    private void pullMedia() {
        if (missionStartTimestampMs <= 0L) {
            tvLog.setText("Pulling all media to Downloads...");
        } else {
            tvLog.setText("Pulling mission media to Downloads...");
        }
        pullMediaToDownloads();
    }

    private void forceRTH() {
        if (missionPlanner != null && missionPlanner.isMissionStarted()) {
            missionPlanner.stop();
            stopMissionLoop();
        }
        KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStartGoHome),
                new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                    @Override
                    public void onSuccess(EmptyMsg emptyMsg) {
                        runOnUiThread(() -> tvLog.setText("Manual RTH started"));
                        Log.i(TAG, "Manual RTH start onSuccess");
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError idjiError) {
                        Log.e(TAG, "Manual RTH start (KeyStartGoHome) onFailure: " + idjiError);
                        KeyManager.getInstance().performAction(
                                KeyTools.createKey(FlightControllerKey.KeyGoHomeConfirm),
                                true,
                                new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                                    @Override
                                    public void onSuccess(EmptyMsg emptyMsg) {
                                        runOnUiThread(() -> tvLog.setText("Manual RTH started"));
                                        Log.i(TAG, "Manual RTH start via confirm onSuccess");
                                    }

                                    @Override
                                    public void onFailure(@NonNull IDJIError confirmError) {
                                        runOnUiThread(() -> tvLog.setText("Manual RTH failed"));
                                        Log.e(TAG, "Manual RTH start onFailure: " + confirmError);
                                    }
                                }
                        );
                    }
                }
        );
    }

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.google-earth.kmz");
        startActivityForResult(intent, PICK_KMZ_FILE_REQUEST);
    }

    private void onActivityResultLoadMission(Uri uri) {
        File tempFile = copyFileToTempFolder(this, uri);
        if (tempFile != null) {
            processMissionFile(tempFile);
        }
    }

    private void processMissionFile(@NonNull File missionFile) {
        KmzMissionParser.Result parseResult = KmzMissionParser.parse(missionFile);
        if (!parseResult.isSuccess() || parseResult.mission == null) {
            tvLog.setText(parseResult.error != null ? parseResult.error : "KMZ parse error");
            return;
        }
        KmzMissionParser.ParsedMission parsedMission = parseResult.mission;
        currentMissionPath = parsedMission.missionPath;

        missionWaypoints.clear();
        missionActionGroups.clear();
        missionWaypoints.addAll(parsedMission.waypoints);
        missionActionGroups.addAll(parsedMission.actionGroups);

        if (missionPlanner != null) {
            missionPlanner.setMissionData(missionWaypoints, missionActionGroups);
        }

        setMissionOnMap();
        logParsedWaypointsAndActions();

        isMissionLoaded = true;
        if (missionPlanner != null) {
            missionPlanner.resetExecutionState();
        }
        btnStartStopMission.setEnabled(true);
        btnStartStopMission.setText(getString(R.string.start_mission_button));
        btnPauseResumeMission.setEnabled(false);
        btnPauseResumeMission.setText(getString(R.string.pause_mission_button));
        tvLog.setText(String.format(Locale.US, "KMZ loaded: %d waypoints (actions: yaw/hover/gimbal)", missionWaypoints.size()));
    }

    private void logParsedWaypointsAndActions() {
        Log.i(TAG, "===== Parsed KMZ Mission =====");
        for (WaylineExecuteWaypoint waypoint : missionWaypoints) {
            if (waypoint == null || waypoint.getLocation() == null) {
                continue;
            }
            int wpIndex = waypoint.getWaypointIndex() != null ? waypoint.getWaypointIndex() : -1;
            double lat = waypoint.getLocation().getLatitude();
            double lon = waypoint.getLocation().getLongitude();
            Double height = waypoint.getExecuteHeight();
            Log.i(TAG, String.format(Locale.US, "WP %d -> lat=%.8f lon=%.8f h=%.2f",
                    wpIndex, lat, lon, height != null ? height : 0.0));

            boolean hasActions = false;
            for (WaylineActionGroup actionGroup : missionActionGroups) {
                if (actionGroup == null) {
                    continue;
                }
                Integer start = actionGroup.getStartIndex();
                Integer end = actionGroup.getEndIndex();
                if (start == null || end == null || wpIndex < start || wpIndex > end) {
                    continue;
                }
                hasActions = true;
                Integer groupId = actionGroup.getGroupId();
                List<WaylineActionInfo> actions = actionGroup.getActions();
                if (actions == null || actions.isEmpty()) {
                    Log.i(TAG, String.format(Locale.US, "  - group %s: no actions", String.valueOf(groupId)));
                    continue;
                }
                for (int i = 0; i < actions.size(); i++) {
                    Log.i(TAG, String.format(Locale.US, "  - group %s action %d: %s",
                            String.valueOf(groupId), i, MissionPlanner.describeAction(actions.get(i))));
                }
            }
            if (!hasActions) {
                Log.i(TAG, "  - no decoded actions");
            }
        }
        Log.i(TAG, "===== End Parsed KMZ Mission =====");
    }

    private boolean loadBundledMission() {
        File missionFile = new File(getCacheDir(), "mission.kmz");
        try (InputStream inputStream = getResources().openRawResource(R.raw.mission);
             FileOutputStream outputStream = new FileOutputStream(missionFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            processMissionFile(missionFile);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load bundled mission", e);
            return false;
        }
    }

    private void pullMediaToDownloads() {
        MediaFileListDataSource mediaSource = new MediaFileListDataSource.Builder().setIndexType(ComponentIndexType.LEFT_OR_MAIN).build();
        MediaManager.getInstance().setMediaFileDataSource(mediaSource);

        PullMediaFileListParam param = new PullMediaFileListParam.Builder().mediaFileIndex(-1).build();
        MediaManager.getInstance().pullMediaFileListFromCamera(param, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "pullMediaFileListFromCamera - onSuccess");

                MediaFileListData fileListData = MediaManager.getInstance().getMediaFileListData();
                List<MediaFile> mediaFileList = fileListData != null ? fileListData.getData() : null;
                if (mediaFileList == null) {
                    runOnUiThread(() -> tvLog.setText("No media list available"));
                    return;
                }

                List<MediaFile> filteredFiles = filterMediaFilesByMissionTimestamp(mediaFileList, missionStartTimestampMs);
                final String targetFolder = buildMissionDownloadFolderName(missionStartTimestampMs);
                int totalFiles = filteredFiles.size();
                Log.i(TAG, String.format(Locale.US, "PULL media list size=%d filtered=%d missionStartMs=%d folder=%s",
                        mediaFileList.size(), totalFiles, missionStartTimestampMs, targetFolder));

                if (totalFiles == 0) {
                    runOnUiThread(() ->
                            tvLog.setText("No new media after mission start")
                    );
                    return;
                }

                runOnUiThread(() -> tvLog.setText(String.format(Locale.US, "Pull %d files -> Download/%s", totalFiles, targetFolder)));
                downloadMediaFilesSequential(filteredFiles, 0, targetFolder, new AtomicInteger(0), new AtomicInteger(0));
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                Log.i(TAG, "pullMediaFileListFromCamera - onFailure: " + idjiError);
            }
        });
    }

    private void downloadMediaFilesSequential(@NonNull List<MediaFile> files,
                                              int index,
                                              @NonNull String targetFolder,
                                              @NonNull AtomicInteger successCount,
                                              @NonNull AtomicInteger failureCount) {
        final int totalFiles = files.size();
        if (index >= totalFiles) {
            runOnUiThread(() -> tvLog.setText(String.format(Locale.US,
                    "Pull complete: %d ok, %d failed -> Download/%s",
                    successCount.get(), failureCount.get(), targetFolder)));
            return;
        }

        MediaFile mediaFile = files.get(index);
        String fileName = mediaFile != null && mediaFile.getFileName() != null
                ? mediaFile.getFileName()
                : ("media_" + System.currentTimeMillis() + "_" + index);
        long expectedSize = mediaFile != null ? mediaFile.getFileSize() : -1L;

        final DownloadTarget target;
        final BufferedOutputStream bos;
        try {
            target = createDownloadOutputStream(targetFolder, fileName);
            if (target == null) {
                failureCount.incrementAndGet();
                runOnUiThread(() -> tvLog.setText(String.format(Locale.US, "Create output failed %s (%d/%d)",
                        fileName, successCount.get() + failureCount.get(), totalFiles)));
                downloadMediaFilesSequential(files, index + 1, targetFolder, successCount, failureCount);
                return;
            }
            bos = new BufferedOutputStream(target.outputStream);
        } catch (Exception e) {
            failureCount.incrementAndGet();
            Log.e(TAG, "Error creating output for " + fileName, e);
            runOnUiThread(() -> tvLog.setText(String.format(Locale.US, "Create output failed %s (%d/%d)",
                    fileName, successCount.get() + failureCount.get(), totalFiles)));
            downloadMediaFilesSequential(files, index + 1, targetFolder, successCount, failureCount);
            return;
        }

        final long[] bytesWritten = {0L};
        final boolean[] writeError = {false};
        final String progressPrefix = String.format(Locale.US, "[%d/%d] %s", index + 1, totalFiles, fileName);
        Log.i(TAG, String.format(Locale.US, "Start download %s expectedSize=%d", progressPrefix, expectedSize));

        mediaFile.pullOriginalMediaFileFromCamera(0L, new MediaFileDownloadListener() {
            @Override
            public void onStart() {
                runOnUiThread(() -> tvLog.setText("Downloading " + progressPrefix));
            }

            @Override
            public void onProgress(long total, long current) {
                int progress = total > 0 ? (int) (100 * current / total) : 0;
                Log.i(TAG, String.format(Locale.US, "Download progress %s: %d%% (%d/%d)", progressPrefix, progress, current, total));
            }

            @Override
            public void onRealtimeDataUpdate(byte[] data, long position) {
                if (data == null || data.length == 0) {
                    return;
                }
                try {
                    bos.write(data);
                    bytesWritten[0] += data.length;
                } catch (IOException e) {
                    writeError[0] = true;
                    Log.e(TAG, "Write error for " + progressPrefix, e);
                }
            }

            @Override
            public void onFinish() {
                boolean ok = !writeError[0] && bytesWritten[0] > 0;
                try {
                    bos.flush();
                    bos.close();
                } catch (IOException e) {
                    ok = false;
                    Log.e(TAG, "Error closing stream for " + progressPrefix, e);
                }
                if (ok) {
                    successCount.incrementAndGet();
                    Log.i(TAG, String.format(Locale.US, "Download OK %s bytesWritten=%d expectedSize=%d",
                            progressPrefix, bytesWritten[0], expectedSize));
                } else {
                    failureCount.incrementAndGet();
                    Log.e(TAG, String.format(Locale.US, "Download INVALID %s bytesWritten=%d expectedSize=%d",
                            progressPrefix, bytesWritten[0], expectedSize));
                    deleteDownloadTarget(target);
                }
                runOnUiThread(() -> tvLog.setText(String.format(Locale.US,
                        "Downloaded %d/%d (fail %d) -> %s",
                        successCount.get(), totalFiles, failureCount.get(), progressPrefix)));
                downloadMediaFilesSequential(files, index + 1, targetFolder, successCount, failureCount);
            }

            @Override
            public void onFailure(IDJIError error) {
                Log.e(TAG, "Download failed for " + progressPrefix + ": " + error);
                try {
                    bos.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing stream on failure for " + progressPrefix, e);
                }
                failureCount.incrementAndGet();
                deleteDownloadTarget(target);
                runOnUiThread(() -> tvLog.setText(String.format(Locale.US,
                        "Download failed %s (%d/%d)", progressPrefix, successCount.get() + failureCount.get(), totalFiles)));
                downloadMediaFilesSequential(files, index + 1, targetFolder, successCount, failureCount);
            }
        });
    }

    private List<MediaFile> filterMediaFilesByMissionTimestamp(@NonNull List<MediaFile> mediaFileList, long missionStartMs) {
        if (missionStartMs <= 0L) {
            return new ArrayList<>(mediaFileList);
        }
        List<MediaFile> filtered = new ArrayList<>();
        for (MediaFile mediaFile : mediaFileList) {
            if (mediaFile == null) {
                continue;
            }
            long mediaMs = mediaFileDateToEpochMillis(mediaFile);
            if (mediaMs >= missionStartMs) {
                filtered.add(mediaFile);
            }
        }
        return filtered;
    }

    private long mediaFileDateToEpochMillis(@NonNull MediaFile mediaFile) {
        if (mediaFile.getDate() == null) {
            return Long.MIN_VALUE;
        }
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.YEAR, safeInt(mediaFile.getDate().getYear(), 1970));
            calendar.set(Calendar.MONTH, Math.max(0, safeInt(mediaFile.getDate().getMonth(), 1) - 1));
            calendar.set(Calendar.DAY_OF_MONTH, safeInt(mediaFile.getDate().getDay(), 1));
            calendar.set(Calendar.HOUR_OF_DAY, safeInt(mediaFile.getDate().getHour(), 0));
            calendar.set(Calendar.MINUTE, safeInt(mediaFile.getDate().getMinute(), 0));
            calendar.set(Calendar.SECOND, safeInt(mediaFile.getDate().getSecond(), 0));
            calendar.set(Calendar.MILLISECOND, 0);
            return calendar.getTimeInMillis();
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse media file date for " + mediaFile.getFileName(), e);
            return Long.MIN_VALUE;
        }
    }

    private int safeInt(@Nullable Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    @NonNull
    private String buildMissionDownloadFolderName(long missionStartMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(missionStartMs > 0 ? missionStartMs : System.currentTimeMillis());
        return String.format(Locale.US, "Mini3Pro_%04d%02d%02d_%02d%02d%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND));
    }

    @Nullable
    private DownloadTarget createDownloadOutputStream(@NonNull String folderName, @NonNull String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folderName);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            OutputStream outputStream = getContentResolver().openOutputStream(uri);
            if (outputStream == null) {
                getContentResolver().delete(uri, null, null);
                return null;
            }
            return new DownloadTarget(uri, outputStream);
        }
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File targetDir = new File(downloadDir, folderName);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return null;
        }
        return new DownloadTarget(null, new FileOutputStream(new File(targetDir, fileName), false));
    }

    private void deleteDownloadTarget(@NonNull DownloadTarget target) {
        if (target.uri == null) {
            return;
        }
        try {
            getContentResolver().delete(target.uri, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete invalid downloaded file uri=" + target.uri, e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_KMZ_FILE_REQUEST && resultCode == RESULT_OK) {
            if (data != null) {
                onActivityResultLoadMission(data.getData());
            }
        }
    }

    private void loadMission() {
        if (droneCommander != null) {
            droneCommander.applyDefaultRthAltitude();
        }
        if (!checkGPSCoordinates(droneHomeLocation.getLatitude(), droneHomeLocation.getLongitude())
                && !checkGPSCoordinates(droneCurrentLocation.getLatitude(), droneCurrentLocation.getLongitude())) {
            tvLog.setText(getString(R.string.home_point_unknown));
            return;
        }
        if (!loadBundledMission()) {
            openFileChooser();
        }
    }

    private void loadManualPitchMission() {
        currentMissionPath = "manual://pitch_sweep";
        missionWaypoints.clear();
        missionActionGroups.clear();

        WaylineExecuteWaypoint waypoint = new WaylineExecuteWaypoint();
        waypoint.setWaypointIndex(0);
        waypoint.setLocation(new WaylineLocationCoordinate2D(MANUAL_WP_LAT, MANUAL_WP_LON));
        waypoint.setExecuteHeight(MANUAL_WP_HEIGHT);
        missionWaypoints.add(waypoint);

        WaylineActionGroup actionGroup = new WaylineActionGroup();
        actionGroup.setGroupId(0);
        actionGroup.setStartIndex(0);
        actionGroup.setEndIndex(0);

        List<WaylineActionInfo> actions = new ArrayList<>();
        int actionId = 0;
        for (int i = 0; i < MANUAL_PITCH_SWEEP_DEG.length; i++) {
            double pitchDeg = MANUAL_PITCH_SWEEP_DEG[i];

            ActionGimbalRotateParam rotateParam = new ActionGimbalRotateParam();
            rotateParam.setPayloadPositionIndex(0);
            rotateParam.setRotateMode(WaylineGimbalActuatorRotateMode.ABSOLUTE_ANGLE);
            rotateParam.setEnablePitch(true);
            rotateParam.setPitch(pitchDeg);
            rotateParam.setEnableRoll(false);
            rotateParam.setRoll(0.0);
            rotateParam.setEnableYaw(false);
            rotateParam.setYaw(0.0);
            rotateParam.setEnableRotateTime(true);
            rotateParam.setRotateTime(1.0);

            WaylineActionInfo rotateAction = new WaylineActionInfo();
            rotateAction.setActionId(actionId++);
            rotateAction.setActionType(WaylineActionType.GIMBAL_ROTATE);
            rotateAction.setGimbalRotateParam(rotateParam);
            actions.add(rotateAction);

            ActionAircraftHoverParam hoverParam = new ActionAircraftHoverParam();
            hoverParam.setHoverTime(2.0);
            WaylineActionInfo hoverAction = new WaylineActionInfo();
            hoverAction.setActionId(actionId++);
            hoverAction.setActionType(WaylineActionType.HOVER);
            hoverAction.setAircraftHoverParam(hoverParam);
            actions.add(hoverAction);

            ActionTakePhotoParam takePhotoParam = new ActionTakePhotoParam();
            takePhotoParam.setPayloadPositionIndex(0);
            WaylineActionInfo takePhotoAction = new WaylineActionInfo();
            takePhotoAction.setActionId(actionId++);
            takePhotoAction.setActionType(WaylineActionType.TAKE_PHOTO);
            takePhotoAction.setTakePhotoParam(takePhotoParam);
            actions.add(takePhotoAction);
        }
        actionGroup.setActions(actions);
        missionActionGroups.add(actionGroup);

        if (missionPlanner != null) {
            missionPlanner.setMissionData(missionWaypoints, missionActionGroups);
            missionPlanner.resetExecutionState();
        }

        setMissionOnMap();
        logParsedWaypointsAndActions();

        isMissionLoaded = true;
        btnStartStopMission.setEnabled(true);
        btnStartStopMission.setText(getString(R.string.start_mission_button));
        btnPauseResumeMission.setEnabled(false);
        btnPauseResumeMission.setText(getString(R.string.pause_mission_button));
        tvLog.setText("Manual mission loaded: 1 WP, pitch sweep -80..0 (hover 2s)");
    }

    private void startMission() {
        missionStartTimestampMs = System.currentTimeMillis();
        if (!isMissionLoaded || missionWaypoints.isEmpty()) {
            tvLog.setText("Load a mission first");
            return;
        }
        if (!isVirtualStickEnabled) {
            tvLog.setText("Enable VS first");
            return;
        }
        if (!checkGPSCoordinates(droneCurrentLocation.getLatitude(), droneCurrentLocation.getLongitude())
                && isSimulatorEnabled
                && checkGPSCoordinates(droneHomeLocation.getLatitude(), droneHomeLocation.getLongitude())) {
            droneCurrentLocation.setLatitude(droneHomeLocation.getLatitude());
            droneCurrentLocation.setLongitude(droneHomeLocation.getLongitude());
        }
        if (!checkGPSCoordinates(droneCurrentLocation.getLatitude(), droneCurrentLocation.getLongitude())) {
            tvLog.setText("Current GPS not valid");
            return;
        }

        if (droneAltitude < 0.8) {
            tvLog.setText("VS mission: auto takeoff...");
            KeyManager.getInstance().performAction(
                    KeyTools.createKey(FlightControllerKey.KeyStartTakeoff),
                    new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                        @Override
                        public void onSuccess(EmptyMsg emptyMsg) {
                            vsMissionHandler.postDelayed(() -> beginMissionLoop(0), 4500);
                        }

                        @Override
                        public void onFailure(@NonNull IDJIError idjiError) {
                            Log.i(TAG, "startTakeoff - onFailure: " + idjiError);
                            beginMissionLoop(0);
                        }
                    }
            );
            return;
        }
        beginMissionLoop(0);
    }

    private void beginMissionLoop(int startCursor) {
        if (missionPlanner == null || missionWaypoints.isEmpty()) {
            return;
        }
        missionPlanner.startFromWaypoint(startCursor);
        btnStartStopMission.setText(getString(R.string.stop_mission_button));
        btnPauseResumeMission.setEnabled(true);
        btnPauseResumeMission.setText(getString(R.string.pause_mission_button));
        int wpIndex = missionWaypoints.get(missionPlanner.getCurrentWaypointCursor()).getWaypointIndex();
        tvLog.setText(String.format(Locale.US, "VS mission started from WP %d", wpIndex));
        vsMissionHandler.removeCallbacks(virtualStickMissionLoop);
        vsMissionHandler.post(virtualStickMissionLoop);
    }

    private void stopMission() {
        if (missionPlanner != null) {
            missionPlanner.stop();
        }
        stopMissionLoopAndHoldPosition();
        btnStartStopMission.setText(getString(R.string.start_mission_button));
        btnStartStopMission.setEnabled(isMissionLoaded);
        btnPauseResumeMission.setEnabled(false);
        btnPauseResumeMission.setText(getString(R.string.pause_mission_button));
        tvLog.setText("VS mission stopped");
    }

    private void pauseMission() {
        if (missionPlanner == null || !missionPlanner.isMissionStarted()) {
            return;
        }
        missionPlanner.pause();
        stopMissionLoopAndHoldPosition();
        btnPauseResumeMission.setEnabled(true);
        btnPauseResumeMission.setText(getString(R.string.resume_mission_button));
        tvLog.setText("VS mission paused");
    }

    private void resumeMission() {
        if (missionPlanner == null || !missionPlanner.isMissionStarted()) {
            return;
        }
        missionPlanner.resume();
        btnPauseResumeMission.setEnabled(true);
        btnPauseResumeMission.setText(getString(R.string.pause_mission_button));
        tvLog.setText("VS mission resumed");
        vsMissionHandler.removeCallbacks(virtualStickMissionLoop);
        vsMissionHandler.post(virtualStickMissionLoop);
    }

    private void runVirtualStickMissionTick() {
        if (missionPlanner == null) {
            return;
        }
        missionPlanner.tick(droneCurrentLocation, droneAltitude, droneHeading);
    }

    private void stopMissionLoopAndHoldPosition() {
        stopMissionLoop();
        if (droneCommander != null) {
            droneCommander.holdPosition(droneHeading);
        }
    }

    private void stopMissionLoop() {
        vsMissionHandler.removeCallbacks(virtualStickMissionLoop);
    }

    private void triggerMissionRTH() {
        KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStartGoHome),
                new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                    @Override
                    public void onSuccess(EmptyMsg emptyMsg) {
                        tvLog.setText("Mission complete: RTH started");
                        Log.i(TAG, "RTH start onSuccess");
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError idjiError) {
                        Log.e(TAG, "RTH start (KeyStartGoHome) onFailure: " + idjiError);
                        KeyManager.getInstance().performAction(
                                KeyTools.createKey(FlightControllerKey.KeyGoHomeConfirm),
                                true,
                                new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                                    @Override
                                    public void onSuccess(EmptyMsg emptyMsg) {
                                        tvLog.setText("Mission complete: RTH started");
                                        Log.i(TAG, "RTH start via confirm onSuccess");
                                    }

                                    @Override
                                    public void onFailure(@NonNull IDJIError confirmError) {
                                        tvLog.setText("Mission complete: RTH failed, holding");
                                        Log.e(TAG, "RTH start onFailure: " + confirmError);
                                        if (droneCommander != null) {
                                            droneCommander.holdPosition(droneHeading);
                                        }
                                    }
                                }
                        );
                    }
                }
        );
    }

    private void setHome() {
        if (isSetHomeInProgress) {
            return;
        }
        isSetHomeInProgress = true;
        KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel), new CommonCallbacks.CompletionCallbackWithParam<>() {
            @Override
            public void onSuccess(GPSSignalLevel gpsSignalLevel) {
                Log.i(TAG, "KeyGPSSignalLevel - onSuccess: " + gpsSignalLevel.value());
                if (gpsSignalLevel.value() >= 2) {

                    KeyManager.getInstance().setValue(KeyTools.createKey(FlightControllerKey.KeyHomeLocation), droneCurrentLocation, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "KeyHomeLocation - onSuccess");
                            tvHome.setText(getString(R.string.true_output));
                            isSetHomeInProgress = false;
                        }

                        @Override
                        public void onFailure(@NonNull IDJIError idjiError) {
                            Log.i(TAG, "KeyHomeLocation - onFailure: " + idjiError);
                            isSetHomeInProgress = false;
                        }
                    });
                } else {
                    isSetHomeInProgress = false;
                }
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                isSetHomeInProgress = false;
            }
        });
    }

    private void enableVirtualStick() {
        if (droneCommander == null) {
            return;
        }
        droneCommander.enableVirtualStick(new DroneCommander.CommandCallback() {
            @Override
            public void onSuccess() {
                isVirtualStickEnabled = true;
                tvLog.setText(R.string.vs_enabled);
                Log.i(TAG, "enableVirtualStick - onSuccess");
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                isVirtualStickEnabled = false;
                tvLog.setText(getString(R.string.vs_enable_failed, idjiError.description()));
                Log.i(TAG, "enableVirtualStick - onFailure: " + idjiError);
            }
        });
    }

    private void disableVirtualStick() {
        if (droneCommander == null) {
            return;
        }
        droneCommander.disableVirtualStick(new DroneCommander.CommandCallback() {
            @Override
            public void onSuccess() {
                isVirtualStickEnabled = false;
                tvLog.setText(R.string.vs_disabled);
                Log.i(TAG, "disableVirtualStick - onSuccess");
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                tvLog.setText(getString(R.string.vs_disable_failed, idjiError.description()));
                Log.i(TAG, "disableVirtualStick - onFailure: " + idjiError);
            }
        });
    }

    private void startSimulator() {
//        disableVirtualStick();
        if (!checkGPSCoordinates(droneHomeLocation.getLatitude(), droneHomeLocation.getLongitude())) {
            setSimulationHome();
        }
        if (!checkGPSCoordinates(droneHomeLocation.getLatitude(), droneHomeLocation.getLongitude())) {
            tvLog.setText("Cannot start simulator: home unknown");
            return;
        }

        SimulatorManager.getInstance().enableSimulator(InitializationSettings.createInstance(droneHomeLocation, 20), new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "enableSimulator - onSuccess");
                isSimulatorEnabled = true;

                tvSimulator.setText(getString(R.string.true_output));
                tvHome.setText(getString(R.string.true_output));
                tvHome.setTextColor(Color.CYAN);

                updateDroneHomeAndCurrentLocation();

                SimulatorManager.getInstance().addSimulatorStateListener(state -> {
                    if (state != null && state.getLocation() != null) {
                        droneCurrentLocation.setLatitude(state.getLocation().getLatitude());
                        droneCurrentLocation.setLongitude(state.getLocation().getLongitude());
                    }
                    updateDroneHomeAndCurrentLocation();
                });
            }

            @Override
            public void onFailure(@NonNull IDJIError error) {
                Log.i(TAG, "enableSimulator - onFailure: " + error);
                tvSimulator.setText(getString(R.string.error_output));
            }
        });
    }

    private void stopSimulator() {
        SimulatorManager.getInstance().disableSimulator(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "disableSimulator - onSuccess");
                isSimulatorEnabled = false;
                clearSimulationFallbackHomeIfAny();

                tvSimulator.setText(getString(R.string.false_output));
                tvHome.setTextColor(Color.WHITE);
            }

            @Override
            public void onFailure(@NonNull IDJIError error) {
                Log.i(TAG, "disableSimulator - onFailure: " + error);
                tvSimulator.setText(getString(R.string.error_output));
            }
        });
    }

    private void initRGBCamera() {
        CameraStreamManager.getInstance().addAvailableCameraUpdatedListener(availableCameraList -> {
            Log.i(TAG, "onAvailableCameraUpdated: " + availableCameraList.size());

            if (!availableCameraList.isEmpty()) {
                MediaDataCenter.getInstance().getCameraStreamManager().putCameraStreamSurface(
                        ComponentIndexType.LEFT_OR_MAIN,
                        svCameraStream.getHolder().getSurface(),
                        svCameraStream.getWidth(),
                        svCameraStream.getHeight(),
                        ICameraStreamManager.ScaleType.CENTER_CROP
                );

                KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource), CameraVideoStreamSourceType.ZOOM_CAMERA, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(TAG, "onSuccess ZOOM_CAMERA");
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError idjiError) {
                        Log.i(TAG, "onFailure ZOOM_CAMERA: " + idjiError);
                    }

                });
            }
        });

        svCameraStream.setVisibility(View.VISIBLE);
    }

    private void setMissionOnMap() {
        if (missionWaypoints.isEmpty() || missionMapRenderer == null) {
            return;
        }
        double homeLat = droneHomeLocation.getLatitude();
        double homeLon = droneHomeLocation.getLongitude();
        if (!checkGPSCoordinates(homeLat, homeLon)) {
            homeLat = missionWaypoints.get(0).getLocation().getLatitude();
            homeLon = missionWaypoints.get(0).getLocation().getLongitude();
        }
        missionMapRenderer.renderMission(mapStyle, missionWaypoints, homeLat, homeLon);
    }

    private void updateDroneHomeAndCurrentLocation() {
        if (mapStyle != null) {
            // Update home location marker (no rotation)
            if (checkGPSCoordinates(droneHomeLocation.getLatitude(), droneHomeLocation.getLongitude())) {
                Bitmap homeIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.home_location);
                if (mapStyle.getSource("live-home-source") != null) {
                    GeoJsonSource source = mapStyle.getSourceAs("live-home-source");
                    if (source != null) {
                        source.setGeoJson(Point.fromLngLat(droneHomeLocation.getLongitude(), droneHomeLocation.getLatitude()));
                    }
                } else {
                    GeoJsonSource source = new GeoJsonSource("live-home-source", Point.fromLngLat(droneHomeLocation.getLongitude(), droneHomeLocation.getLatitude()));
                    mapStyle.addSource(source);
                    SymbolLayer layer = new SymbolLayer("live-home-layer", "live-home-source");
                    layer.setProperties(
                            iconImage("live-home-icon"),
                            iconAllowOverlap(true),
                            iconIgnorePlacement(true)
                    );
                    mapStyle.addLayer(layer);
                    mapStyle.addImage("live-home-icon", homeIconBitmap);
                }
            }

            // Update current location marker (only if valid GPS coordinates) with rotation for the drone
            if (checkGPSCoordinates(droneCurrentLocation.getLatitude(), droneCurrentLocation.getLongitude())) {
                Bitmap currentIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.aircraft);
                if (mapStyle.getSource("current-location-source") != null) {
                    GeoJsonSource source = mapStyle.getSourceAs("current-location-source");
                    if (source != null) {
                        source.setGeoJson(Point.fromLngLat(droneCurrentLocation.getLongitude(), droneCurrentLocation.getLatitude()));
                    }
                } else {
                    GeoJsonSource source = new GeoJsonSource("current-location-source", Point.fromLngLat(droneCurrentLocation.getLongitude(), droneCurrentLocation.getLatitude()));
                    mapStyle.addSource(source);
                    SymbolLayer layer = new SymbolLayer("current-marker-layer", "current-location-source");
                    layer.setProperties(
                            iconImage("marker-current-icon"),
                            iconAllowOverlap(true),
                            iconIgnorePlacement(true)
                    );
                    mapStyle.addLayer(layer);
                    mapStyle.addImage("marker-current-icon", currentIconBitmap);
                }

                // Update the layer with the icon's rotation based on droneHeading
                SymbolLayer layer = (SymbolLayer) mapStyle.getLayer("current-marker-layer");
                if (layer != null) {
                    layer.setProperties(
                            iconRotate((float) droneHeading)  // Rotate the icon based on droneHeading
                    );
                }
            }
        }
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
                startDroneStateListeners();
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

    private void startDroneStateListeners() {
        if (droneStateRepository == null) {
            return;
        }
        droneStateRepository.start(this, new DroneStateRepository.Callbacks() {
            @Override
            public void onConnectionChanged(@Nullable Boolean connected) {
                runOnUiThread(() -> tvDroneConnected.setText(
                        connected != null ? connected.toString() : getString(R.string.false_output)
                ));
            }

            @Override
            public void onBatteryPercentChanged(@Nullable Integer percent) {
                runOnUiThread(() -> {
                    if (percent != null) {
                        tvBatteryPercentage.setText(getString(R.string.battery_percentage_format, percent));
                    } else {
                        tvBatteryPercentage.setText(getString(R.string.empty_label));
                    }
                });
            }

            @Override
            public void onAircraftAttitudeChanged(double yawDeg) {
                droneHeading = yawDeg;
                runOnUiThread(() -> tvDroneYaw.setText(String.format("%s", integerFormat.format(yawDeg))));
            }

            @Override
            public void onHomeLocationChanged(double latitude, double longitude) {
                if (!checkGPSCoordinates(latitude, longitude)) {
                    Log.i(TAG, "Ignoring invalid home location: " + latitude + ", " + longitude);
                    return;
                }
                if (!hasEffectiveHome || isEffectiveHomeFromSimulation) {
                    setEffectiveHome(latitude, longitude, false, "first GPS home fix");
                }
                isSetHomeInProgress = false;
                runOnUiThread(() -> {
                    tvLog.setText(getString(R.string.home_point_updated, latitude, longitude));
                    updateDroneHomeAndCurrentLocation();
                });
                Log.i(TAG, "droneHomeLocation: " + latitude + ", " + longitude);
            }

            @Override
            public void onAircraftLocation3DChanged(double latitude, double longitude, double altitude) {
                if (checkGPSCoordinates(latitude, longitude)) {
                    droneCurrentLocation.setLatitude(latitude);
                    droneCurrentLocation.setLongitude(longitude);
                }
                droneAltitude = altitude;
                if (!isSimulatorEnabled
                        && checkGPSCoordinates(droneCurrentLocation.getLatitude(), droneCurrentLocation.getLongitude())
                        && !hasEffectiveHome
                        && !isSetHomeInProgress) {
                    setHome();
                }
                runOnUiThread(() -> {
                    tvDroneAltitude.setText(String.format("%s", decimalFormat.format(altitude)));
                    updateDroneHomeAndCurrentLocation();
                });
            }

            @Override
            public void onAircraftVelocityChanged(double speedX, double speedY, double speedZ) {
                double speedXY = Math.sqrt(speedX * speedX + speedY * speedY);
                double speedZUi = -speedZ;
                runOnUiThread(() -> tvDroneSpeed.setText(String.format(Locale.US, "%.1f, %.1f", speedXY, speedZUi)));
            }

            @Override
            public void onGimbalYawAdjustSupported(boolean isSupported) {
                if (droneCommander != null) {
                    droneCommander.updateGimbalYawAdjustCapability(isSupported);
                }
                Log.i(TAG, "Gimbal yaw adjust supported: " + isSupported);
            }

            @Override
            public void onGimbalAttitudeRangeChanged(@NonNull GimbalAttitudeRange range) {
                if (droneCommander != null) {
                    droneCommander.updateGimbalAttitudeRange(range);
                }
                Log.i(TAG, "Gimbal range pitch=" + String.valueOf(range.getPitch())
                        + " roll=" + String.valueOf(range.getRoll())
                        + " yaw=" + String.valueOf(range.getYaw()));
            }

            @Override
            public void onGimbalAttitudeChanged(double pitchDeg, double rollDeg, double yawDeg) {
                if (droneCommander != null) {
                    droneCommander.updateCurrentGimbalPitch(pitchDeg);
                }
                runOnUiThread(() -> tvGimbalPitch.setText(String.format(Locale.US, "%.1f", pitchDeg)));
            }

        });
    }
}
