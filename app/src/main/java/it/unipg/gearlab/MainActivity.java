package it.unipg.gearlab;

import static it.unipg.gearlab.MissionFileRepository.copyFileToTempFolder;
import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconRotate;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

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
import java.util.List;
import java.util.Locale;

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
import dji.sdk.wpmz.value.mission.ActionGimbalRotateParam;
import dji.sdk.wpmz.value.mission.WaylineActionGroup;
import dji.sdk.wpmz.value.mission.WaylineActionInfo;
import dji.sdk.wpmz.value.mission.WaylineExecuteWaypoint;
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
    private static final int REQUEST_CODE_OPEN_DOCUMENT_TREE = 5678;
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
    private double droneHeading;
    private double droneAltitude;
    private String currentMissionPath;
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

    public static boolean checkGPSCoordinates(double latitude, double longitude) {
        return (latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180) && (latitude != 0f && longitude != 0f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setSimulationHome();

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
                    stopMissionLoopAndHoldPosition();
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
        if (BuildConfig.SIMULATION == 1) {
            droneHomeLocation.setLatitude(BuildConfig.SIMULATION_LATITUDE);
            droneHomeLocation.setLongitude(BuildConfig.SIMULATION_LONGITUDE);
        }
    }

    public void initUI(@Nullable Bundle savedInstanceState) {
        SwitchMaterial switchSimulator = findViewById(R.id.switch_simulator);
        SwitchMaterial switchRTK = findViewById(R.id.switch_rtk);
        Button btnPullMedia = findViewById(R.id.button_pull_media);
        Button btnLoadMission = findViewById(R.id.button_load_mission);
        btnStartStopMission = findViewById(R.id.button_start_stop_mission);
        btnPauseResumeMission = findViewById(R.id.button_pause_resume_mission);

        tvDroneConnected = findViewById(R.id.textview_is_drone_connected);
        tvBatteryPercentage = findViewById(R.id.textview_battery);
        tvSimulator = findViewById(R.id.textview_is_simulator_on);
        tvDroneYaw = findViewById(R.id.textview_drone_yaw);
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

        switchRTK.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                Log.i(TAG, "enableVirtualStick");
                enableVirtualStick();
            } else {
                Log.i(TAG, "disableVirtualStick");
                disableVirtualStick();
            }
        });

        btnPullMedia.setOnClickListener(view -> {
            Log.i(TAG, "pullMedia");
            pullMedia();
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
        tvLog.setText("Start pulling files from H20");

        // Select a destination folder here
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE);
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

    private void onActivityResultPullMedia(Uri treeUri) {
        MediaFileListDataSource mediaSource = new MediaFileListDataSource.Builder().setIndexType(ComponentIndexType.LEFT_OR_MAIN).build();
        MediaManager.getInstance().setMediaFileDataSource(mediaSource);

        PullMediaFileListParam param = new PullMediaFileListParam.Builder().mediaFileIndex(-1).build();
        MediaManager.getInstance().pullMediaFileListFromCamera(param, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "pullMediaFileListFromCamera - onSuccess");

                MediaFileListData fileListData = MediaManager.getInstance().getMediaFileListData();
                List<MediaFile> mediaFileList = fileListData.getData();

                int totalFiles = mediaFileList.size();
                final int[] totalDone = {0};

                if (totalFiles == 0) {
                    runOnUiThread(() ->
                            tvLog.setText("Nothing to pull from camera")
                    );
                    return;
                }

                DocumentFile pickedDir = DocumentFile.fromTreeUri(getApplicationContext(), treeUri);
                if (pickedDir == null) {
                    runOnUiThread(() -> tvLog.setText("Invalid destination folder"));
                    return;
                }

                for (MediaFile mediaFile : mediaFileList) {
                    String fileName = mediaFile.getFileName();
                    DocumentFile newFile = pickedDir.createFile("application/octet-stream", fileName);

                    if (newFile != null) {
                        try {
                            OutputStream outputStream = getContentResolver().openOutputStream(newFile.getUri());
                            if (outputStream == null) {
                                runOnUiThread(() ->
                                        tvLog.setText("Failed to open output stream for " + fileName)
                                );
                                continue;
                            }
                            BufferedOutputStream bos = new BufferedOutputStream(outputStream);

                            mediaFile.pullOriginalMediaFileFromCamera(0L, new MediaFileDownloadListener() {
                                @Override
                                public void onStart() {
                                    runOnUiThread(() ->
                                            Log.i(TAG, "Starting download for " + fileName)
                                    );
                                }

                                @Override
                                public void onProgress(long total, long current) {
                                    int progress = (int) (100 * current / total);
                                    runOnUiThread(() ->
                                            Log.i(TAG, "Download progress for " + fileName + ": " + progress + "%")
                                    );
                                }

                                @Override
                                public void onRealtimeDataUpdate(byte[] data, long position) {
                                    try {
                                        bos.write(data);
                                    } catch (IOException e) {
                                        Log.e(TAG, "Write error for " + fileName + ": " + e.getMessage());
                                    }
                                }

                                @Override
                                public void onFinish() {
                                    try {
                                        bos.flush();
                                        bos.close(); // Close the stream on finish
                                        runOnUiThread(() -> {
                                            totalDone[0]++;
                                            tvLog.setText("Downloaded " + totalDone[0] + "/" + totalFiles + ": " + fileName);

                                            if (totalDone[0] == totalFiles) {
                                                tvLog.setText("All files downloaded.");
                                                cleanUpMedia(mediaFileList);
                                            }
                                        });
                                    } catch (IOException e) {
                                        Log.e(TAG, "Error closing stream for " + fileName + ": " + e.getMessage());
                                    }
                                }

                                @Override
                                public void onFailure(IDJIError error) {
                                    Log.e(TAG, "Download failed for " + fileName + ": " + error);
                                    try {
                                        bos.close(); // Ensure stream is closed on failure
                                    } catch (IOException e) {
                                        Log.e(TAG, "Error closing stream on failure for " + fileName + ": " + e.getMessage());
                                    }

                                    runOnUiThread(() ->
                                            tvLog.setText("Failed to download " + fileName)
                                    );
                                }
                            });

                        } catch (IOException e) {
                            Log.e(TAG, "Error creating file " + fileName + ": " + e.getMessage());
                        }
                    } else {
                        Log.e(TAG, "Failed to create file: " + fileName);
                        runOnUiThread(() ->
                                tvLog.setText("Failed to create file: " + fileName)
                        );
                    }
                }
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                Log.i(TAG, "pullMediaFileListFromCamera - onFailure: " + idjiError);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_KMZ_FILE_REQUEST && resultCode == RESULT_OK) {
            if (data != null) {
                onActivityResultLoadMission(data.getData());
            }
        } else if (requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE && resultCode == RESULT_OK) {
            if (data != null) {
                onActivityResultPullMedia(data.getData());
            }
        }
    }

    private void loadMission() {
        if (!checkGPSCoordinates(droneHomeLocation.getLatitude(), droneHomeLocation.getLongitude())
                && !checkGPSCoordinates(droneCurrentLocation.getLatitude(), droneCurrentLocation.getLongitude())) {
            tvLog.setText(getString(R.string.home_point_unknown));
            return;
        }
        if (!loadBundledMission()) {
            openFileChooser();
        }
    }

    private void startMission() {
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
        vsMissionHandler.removeCallbacks(virtualStickMissionLoop);
        if (droneCommander != null) {
            droneCommander.holdPosition(droneHeading);
        }
    }

    private void setHome() {
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
                        }

                        @Override
                        public void onFailure(@NonNull IDJIError idjiError) {
                            Log.i(TAG, "KeyHomeLocation - onFailure: " + idjiError);
                        }
                    });
                }
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {

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
                Bitmap currentIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.drone);
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
                droneHomeLocation.setLatitude(latitude);
                droneHomeLocation.setLongitude(longitude);
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
                if (droneCurrentLocation.getLatitude() != 0 && droneHomeLocation.getLatitude() == 0) {
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
        });
    }
}
