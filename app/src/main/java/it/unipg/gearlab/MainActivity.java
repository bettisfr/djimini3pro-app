package it.unipg.gearlab;

import static it.unipg.gearlab.Util.copyFileToTempFolder;
import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconRotate;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineDasharray;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
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

import com.dji.wpmzsdk.common.data.KMZInfo;
import com.dji.wpmzsdk.manager.WPMZManager;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dji.sdk.keyvalue.key.CameraKey;
import dji.sdk.keyvalue.key.FlightControllerKey;
import dji.sdk.keyvalue.key.GimbalKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.sdk.keyvalue.value.common.EmptyMsg;
import dji.sdk.keyvalue.value.common.LocationCoordinate2D;
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel;
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
import dji.v5.manager.aircraft.waypoint3.WaypointActionListener;
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager;
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
    private boolean isMissionStarted = false;
    private boolean isMissionPaused = false;
    private boolean isVirtualStickEnabled = false;
    private boolean mapFallbackApplied = false;
    private double droneHeading;
    private String currentMissionPath;

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
        initRGBCamera();
        registerApp();

        // Again!
        setAppStyle();
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
            if (isMissionStarted) {
                Log.i(TAG, "stopMission - onClick");
                stopMission();
            } else {
                Log.i(TAG, "startMission - onClick");
                startMission();
            }
        });

        btnPauseResumeMission.setOnClickListener(view -> {
            if (isMissionPaused) {
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
                        .zoom(18)
                        .build());

                String key = BuildConfig.MAPTILER_API_KEY;
                // Find other maps in https://cloud.maptiler.com/maps/
                String mapId = "satellite";
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
            currentMissionPath = tempFile.getAbsolutePath();

            KMZInfo kmzInfo = WPMZManager.getInstance().getKMZInfo(currentMissionPath);
            if (kmzInfo == null) {
                tvLog.setText("Invalid KMZ file");
                return;
            }

            setMissionOnMap(kmzInfo);

            WaypointMissionManager.getInstance().pushKMZFileToAircraft(currentMissionPath, new CommonCallbacks.CompletionCallbackWithProgress<>() {
                @Override
                public void onProgressUpdate(Double progress) {
                    Log.d(TAG, "Upload progress: " + progress + "%");

                    tvLog.setText(String.format(Locale.US, "Upload progress: %.2f%%", progress));
                }

                @Override
                public void onSuccess() {
                    Log.i(TAG, "KMZ mission uploaded successfully.");

                    tvLog.setText(getString(R.string.kmz_upload_success));

                    btnStartStopMission.setEnabled(true);
                    btnStartStopMission.setText(getString(R.string.start_mission_button));

                    WaypointMissionManager.getInstance().addWaypointActionListener(new WaypointActionListener() {
                        @Override
                        public void onExecutionStart(int actionId) {
                            Log.i(TAG, "onExecutionStart - actionId=" + actionId);
                        }

                        @Override
                        public void onExecutionFinish(int actionId, @Nullable IDJIError error) {
                            Log.i(TAG, "onExecutionFinish - actionId=" + actionId);
                        }

                        @Override
                        public void onExecutionStart(int actionGroup, int actionId) {
                            Log.i(TAG, "onExecutionStart - actionGroup=" + actionGroup + ", actionId=" + actionId);

                            WaylineActionInfo actionInfo = WPMZManager.getInstance().getKMZInfo(currentMissionPath)
                                    .getWaylineWaylinesParseInfo()
                                    .getWaylines().get(0)
                                    .getActionGroups().get(actionGroup)
                                    .getActions().get(actionId);

                            // Get the action type
                            String actionType = actionInfo.getActionType().toString();

                            String actionDetails = actionType + ": ";

                            // Evaluate based on action type
                            switch (actionType) {
                                case "ROTATE_YAW":
                                    actionDetails += "Heading: " + actionInfo.getAircraftRotateYawParam().getHeading().toString();
                                    break;

                                case "HOVER":
                                    actionDetails += "Time: " + actionInfo.getAircraftHoverParam().getHoverTime().toString();
                                    break;

                                case "GIMBAL_ROTATE":
                                    actionDetails += "Pitch: " + actionInfo.getGimbalRotateParam().getPitch().toString()
                                            + ", Yaw: " + actionInfo.getGimbalRotateParam().getYaw().toString();
                                    break;

                                case "FOCUS":
                                    actionDetails += "X: " + actionInfo.getFocusParam().getFocus_x().toString()
                                            + ", Y: " + actionInfo.getFocusParam().getFocus_y().toString();
                                    break;

                                case "ZOOM":
                                    actionDetails += "Focal Length: " + actionInfo.getZoomParam().getFocalLength().toString();
                                    break;

                                case "TAKE_PHOTO":
                                    actionDetails += "Suffix: " + actionInfo.getTakePhotoParam().getFileSuffix();
                                    break;

                                default:
                                    actionDetails = "UNKNOWN ACTION";
                                    break;
                            }

                            // Update the log with the action type and details
                            tvLog.setText(getString(R.string.starting_wp_action, actionGroup, actionId, actionDetails));
                        }

                        @Override
                        public void onExecutionFinish(int actionGroup, int actionId, @Nullable IDJIError error) {
                            Log.i(TAG, "onExecutionFinish - actionGroup=" + actionGroup + ", actionId=" + actionId);
                        }
                    });
                }

                @Override
                public void onFailure(@NonNull IDJIError idjiError) {
                    Log.e(TAG, "Failed to upload KMZ mission: " + idjiError);
                }
            });
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
        if (droneHomeLocation.getLatitude() == 0) {
            tvLog.setText(getString(R.string.home_point_unknown));
            return;
        }

        openFileChooser();
    }

    private void startMission() {
        WaypointMissionManager.getInstance().startMission("mission", new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "startMission - onClick - onSuccess");
                isMissionStarted = true;
                btnStartStopMission.setText(getString(R.string.stop_mission_button));
                btnPauseResumeMission.setEnabled(true);
                btnPauseResumeMission.setText(getString(R.string.pause_mission_button));
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                Log.i(TAG, "startMission - onClick - onFailure: " + idjiError);
            }
        });
    }

    private void stopMission() {
        WaypointMissionManager.getInstance().stopMission("mission", new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "stopMission - onClick - onSuccess");
                isMissionStarted = false;
                btnStartStopMission.setText(getString(R.string.start_mission_button));
                btnStartStopMission.setEnabled(false);
                btnPauseResumeMission.setEnabled(false);

                startRTH();
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                Log.i(TAG, "startMission - onClick - onFailure: " + idjiError);
            }
        });
    }

    private void startRTH() {
        KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStartGoHome), new CommonCallbacks.CompletionCallbackWithParam<>() {
            @Override
            public void onSuccess(EmptyMsg emptyMsg) {
                Log.i(TAG, "startRTH - onSuccess");
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                Log.i(TAG, "startRTH - onFailure: " + idjiError);
            }
        });
    }

    private void pauseMission() {
        WaypointMissionManager.getInstance().pauseMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "pauseMission - onClick - onSuccess");
                isMissionPaused = true;
                btnPauseResumeMission.setEnabled(true);
                btnPauseResumeMission.setText(getString(R.string.resume_mission_button));
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                Log.i(TAG, "pauseMission - onClick - onFailure: " + idjiError);
            }
        });
    }

    private void resumeMission() {
        WaypointMissionManager.getInstance().resumeMission(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "resumeMission - onClick - onSuccess");
                isMissionPaused = false;
                btnPauseResumeMission.setEnabled(true);
                btnPauseResumeMission.setText(getString(R.string.pause_mission_button));
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                Log.i(TAG, "resumeMission - onClick - onFailure: " + idjiError);
            }
        });
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
        VirtualStickManager.getInstance().enableVirtualStick(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                isVirtualStickEnabled = true;
                VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true);
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
        VirtualStickManager.getInstance().disableVirtualStick(new CommonCallbacks.CompletionCallback() {
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

                tvSimulator.setText(getString(R.string.true_output));
                tvHome.setText(getString(R.string.true_output));
                tvHome.setTextColor(Color.CYAN);

                updateDroneHomeAndCurrentLocation();

                SimulatorManager.getInstance().addSimulatorStateListener(state -> updateDroneHomeAndCurrentLocation());
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

    private void setMissionOnMap(KMZInfo kmzInfo) {
        if (mapStyle == null || kmzInfo == null) {
            return;
        }

        List<WaylineExecuteWaypoint> waypoints;
        try {
            waypoints = kmzInfo.getWaylineWaylinesParseInfo()
                    .getWaylines()
                    .get(0)
                    .getWaypoints();
        } catch (Exception e) {
            tvLog.setText("KMZ mission data is invalid");
            return;
        }
        if (waypoints == null || waypoints.isEmpty()) {
            tvLog.setText("KMZ has no waypoints");
            return;
        }

        // Remove existing layers and sources to avoid duplication
        if (mapStyle.getLayer("mission-home-layer") != null) {
            mapStyle.removeLayer("mission-home-layer");
        }
        if (mapStyle.getLayer("waypoint-marker-layer") != null) {
            mapStyle.removeLayer("waypoint-marker-layer");
        }
        if (mapStyle.getLayer("home-to-firstwaypoint-layer") != null) {
            mapStyle.removeLayer("home-to-firstwaypoint-layer");
        }
        if (mapStyle.getLayer("lastwaypoint-to-home-layer") != null) {
            mapStyle.removeLayer("lastwaypoint-to-home-layer");
        }
        if (mapStyle.getLayer("waypoint-to-waypoint-layer") != null) {
            mapStyle.removeLayer("waypoint-to-waypoint-layer");
        }
        if (mapStyle.getSource("mission-home-source") != null) {
            mapStyle.removeSource("mission-home-source");
        }
        if (mapStyle.getSource("waypoint-marker-source") != null) {
            mapStyle.removeSource("waypoint-marker-source");
        }
        if (mapStyle.getSource("home-to-firstwaypoint-source") != null) {
            mapStyle.removeSource("home-to-firstwaypoint-source");
        }
        if (mapStyle.getSource("lastwaypoint-to-home-source") != null) {
            mapStyle.removeSource("lastwaypoint-to-home-source");
        }
        if (mapStyle.getSource("waypoint-to-waypoint-source") != null) {
            mapStyle.removeSource("waypoint-to-waypoint-source");
        }

        // Add magenta line from home to the first waypoint
        List<Point> homeToFirstWaypointPoints = new ArrayList<>();
        homeToFirstWaypointPoints.add(Point.fromLngLat(droneHomeLocation.getLongitude(), droneHomeLocation.getLatitude()));
        WaylineExecuteWaypoint firstWaypoint = waypoints.get(0);  // Get the first waypoint
        homeToFirstWaypointPoints.add(Point.fromLngLat(firstWaypoint.getLocation().getLongitude(), firstWaypoint.getLocation().getLatitude()));

        GeoJsonSource homeToFirstWaypointSource = new GeoJsonSource("home-to-firstwaypoint-source", LineString.fromLngLats(homeToFirstWaypointPoints));
        mapStyle.addSource(homeToFirstWaypointSource);

        LineLayer homeToFirstWaypointLayer = new LineLayer("home-to-firstwaypoint-layer", "home-to-firstwaypoint-source");
        homeToFirstWaypointLayer.setProperties(
                lineColor("magenta"),
                lineWidth(2.0f),
                lineDasharray(new Float[]{2f, 4f})
        );
        mapStyle.addLayer(homeToFirstWaypointLayer); // Add line layer from home to first waypoint

        // Add magenta line from last waypoint to home
        List<Point> lastWaypointToHomePoints = new ArrayList<>();
        WaylineExecuteWaypoint lastWaypoint = waypoints.get(waypoints.size() - 1);  // Get the last waypoint
        lastWaypointToHomePoints.add(Point.fromLngLat(lastWaypoint.getLocation().getLongitude(), lastWaypoint.getLocation().getLatitude()));
        lastWaypointToHomePoints.add(Point.fromLngLat(droneHomeLocation.getLongitude(), droneHomeLocation.getLatitude()));

        GeoJsonSource lastWaypointToHomeSource = new GeoJsonSource("lastwaypoint-to-home-source", LineString.fromLngLats(lastWaypointToHomePoints));
        mapStyle.addSource(lastWaypointToHomeSource);

        LineLayer lastWaypointToHomeLayer = new LineLayer("lastwaypoint-to-home-layer", "lastwaypoint-to-home-source");
        lastWaypointToHomeLayer.setProperties(
                lineColor("magenta"),
                lineWidth(2.0f),
                lineDasharray(new Float[]{2f, 4f})
        );
        mapStyle.addLayer(lastWaypointToHomeLayer); // Add line layer from last waypoint to home

        // Add blue lines connecting the waypoints (except home to first and last to home)
        List<Point> waypointToWaypointPoints = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            WaylineExecuteWaypoint currentWaypoint = waypoints.get(i);
            WaylineExecuteWaypoint nextWaypoint = waypoints.get(i + 1);

            waypointToWaypointPoints.add(Point.fromLngLat(currentWaypoint.getLocation().getLongitude(), currentWaypoint.getLocation().getLatitude()));
            waypointToWaypointPoints.add(Point.fromLngLat(nextWaypoint.getLocation().getLongitude(), nextWaypoint.getLocation().getLatitude()));
        }

        // Create GeoJsonSource for the waypoint-to-waypoint connections
        GeoJsonSource waypointToWaypointSource = new GeoJsonSource("waypoint-to-waypoint-source", LineString.fromLngLats(waypointToWaypointPoints));
        mapStyle.addSource(waypointToWaypointSource);

        // Create LineLayer for waypoint-to-waypoint connections (blue)
        LineLayer waypointToWaypointLayer = new LineLayer("waypoint-to-waypoint-layer", "waypoint-to-waypoint-source");
        waypointToWaypointLayer.setProperties(
                lineColor("orange"),
                lineWidth(2.0f)
        );

        // Add line layer for waypoint-to-waypoint connections
        mapStyle.addLayer(waypointToWaypointLayer);

        // Add home location marker (green) at the end
        mapStyle.addImage("home-marker-icon", BitmapFactory.decodeResource(getResources(), R.drawable.home_location));
        GeoJsonSource homeMarkerSource = new GeoJsonSource("mission-home-source");
        List<Feature> homeFeatures = new ArrayList<>();
        homeFeatures.add(Feature.fromGeometry(Point.fromLngLat(droneHomeLocation.getLongitude(), droneHomeLocation.getLatitude())));
        homeMarkerSource.setGeoJson(FeatureCollection.fromFeatures(homeFeatures));
        mapStyle.addSource(homeMarkerSource);

        SymbolLayer homeMarkerLayer = new SymbolLayer("mission-home-layer", "mission-home-source");
        homeMarkerLayer.setProperties(
                iconImage("home-marker-icon"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
        );
        mapStyle.addLayer(homeMarkerLayer);

        // Add waypoint markers (red) at the end
        mapStyle.addImage("waypoint-marker-icon", BitmapFactory.decodeResource(getResources(), R.drawable.marker_red));
        GeoJsonSource waypointMarkerSource = new GeoJsonSource("waypoint-marker-source");
        List<Feature> waypointFeatures = new ArrayList<>();

        for (WaylineExecuteWaypoint waypoint : waypoints) {
            double latitude = waypoint.getLocation().getLatitude();
            double longitude = waypoint.getLocation().getLongitude();
            waypointFeatures.add(Feature.fromGeometry(Point.fromLngLat(longitude, latitude)));
        }

        waypointMarkerSource.setGeoJson(FeatureCollection.fromFeatures(waypointFeatures));
        mapStyle.addSource(waypointMarkerSource);

        SymbolLayer waypointMarkerLayer = new SymbolLayer("waypoint-marker-layer", "waypoint-marker-source");
        waypointMarkerLayer.setProperties(
                iconImage("waypoint-marker-icon"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
        );

        mapStyle.addLayer(waypointMarkerLayer);
    }

    private void updateDroneHomeAndCurrentLocation() {
        if (mapStyle != null) {
            // Update home location marker (no rotation)
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

                listenerDroneConnection();
                listenerBatteryPercent();
                listenerAircraftAttitude();
                listenerHomeLocation();
                listenerAircraftLocation3D();
                listenerGimbalAttitude();
                listenerAircraftVelocity();
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

    private void listenerAircraftVelocity() {
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this, (velocity3D, t1) -> {
            if (velocity3D != null) {
                double speedX = velocity3D.getX();
                double speedY = velocity3D.getY();
                double speedZ = -velocity3D.getZ();  // Keep negative to have takeoff +, landing -

                double speedXY = Math.sqrt(speedX * speedX + speedY * speedY);

                tvDroneSpeed.setText(String.format(Locale.US, "%.1f, %.1f", speedXY, speedZ));
            }
        });
    }

    private void listenerGimbalAttitude() {
        KeyManager.getInstance().listen(KeyTools.createKey(GimbalKey.KeyGimbalAttitude), this, (attitude, t1) -> {
//            // Get gimbal attitude data. The yaw angle uses the north east down coordinate system.
//            // If you need to get the yaw angle of the gimbal relative to the nose of the aircraft, please call KeyYawRelativeToAircraftHeading.
//            if (attitude != null) {
//                tvGimbalPitchYaw.setText(String.format(
//                        "%s, %s",
//                        integerFormat.format(attitude.getPitch()),
//                        integerFormat.format(attitude.getYaw())
//                ));
//            }
        });
    }

    private void listenerAircraftLocation3D() {
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), this, (locationCoordinate3D, t1) -> {
            if (locationCoordinate3D != null) {
//                Log.i(TAG, "droneCurrentLocation: " + locationCoordinate3D.getLatitude() + ", " + locationCoordinate3D.getLongitude());
                droneCurrentLocation.setLatitude(locationCoordinate3D.getLatitude());
                droneCurrentLocation.setLongitude(locationCoordinate3D.getLongitude());

                if (droneCurrentLocation.getLatitude() != 0 && droneHomeLocation.getLatitude() == 0) {
                    setHome();
                }

                tvDroneAltitude.setText(String.format("%s", decimalFormat.format(locationCoordinate3D.getAltitude())));

                Location homeLocation = new Location("home");
                homeLocation.setLatitude(droneHomeLocation.getLatitude());
                homeLocation.setLongitude(droneHomeLocation.getLongitude());

                Location currentLocation = new Location("current");
                currentLocation.setLatitude(droneCurrentLocation.getLatitude());
                currentLocation.setLongitude(droneCurrentLocation.getLongitude());

                updateDroneHomeAndCurrentLocation();
            }
        });
    }

    private void listenerHomeLocation() {
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyHomeLocation), this, (locationCoordinate2D, t1) -> {
            if (locationCoordinate2D != null) {
                tvLog.setText(getString(R.string.home_point_updated, locationCoordinate2D.getLatitude(), locationCoordinate2D.getLongitude()));
//                tvLog.setText("Home point updated! " + locationCoordinate2D.getLatitude() + ", " + locationCoordinate2D.getLongitude());

                Log.i(TAG, "droneHomeLocation: " + locationCoordinate2D.getLatitude() + ", " + locationCoordinate2D.getLongitude());
                droneHomeLocation.setLatitude(locationCoordinate2D.getLatitude());
                droneHomeLocation.setLongitude(locationCoordinate2D.getLongitude());

                updateDroneHomeAndCurrentLocation();
//                centerCameraOnDroneCurrentLocation(); // maybe too much, let's see...
            }
        });
    }

    private void listenerAircraftAttitude() {
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude), this, (attitude, t1) -> {
            if (attitude != null) {
                droneHeading = attitude.getYaw();
                tvDroneYaw.setText(String.format("%s", integerFormat.format(attitude.getYaw())));
            }
        });
    }

    private void listenerBatteryPercent() {
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyBatteryPowerPercent), this, (oldValue, newValue) -> {
            if (newValue != null) {
                tvBatteryPercentage.setText(getString(R.string.battery_percentage_format, newValue));
            } else {
                tvBatteryPercentage.setText(getString(R.string.empty_label));
            }
        });
    }

    private void listenerDroneConnection() {
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyConnection), this, (oldValue, newValue) -> {
            if (newValue != null) {
                tvDroneConnected.setText(newValue.toString());
            } else {
                tvDroneConnected.setText(getString(R.string.false_output));
            }
        });
    }
}
