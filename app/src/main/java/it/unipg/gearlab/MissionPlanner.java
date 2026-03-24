package it.unipg.gearlab;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dji.sdk.keyvalue.value.common.LocationCoordinate2D;
import dji.sdk.wpmz.value.mission.ActionGimbalRotateParam;
import dji.sdk.wpmz.value.mission.ActionTakePhotoParam;
import dji.sdk.wpmz.value.mission.WaylineActionGroup;
import dji.sdk.wpmz.value.mission.WaylineActionInfo;
import dji.sdk.wpmz.value.mission.WaylineExecuteWaypoint;

public class MissionPlanner {
    private static final double WP_REACHED_DISTANCE_METERS = 1.5;
    private static final double WP_REACHED_ALTITUDE_METERS = 1.0;
    private static final double YAW_ACTION_REACHED_THRESHOLD_DEG = 3.0;
    private static final long YAW_ACTION_SETTLE_MS = 400L;
    private static final long DEFAULT_GIMBAL_ACTION_DURATION_MS = 1200L;
    private static final long POST_PHOTO_SETTLE_MS = 1500L;

    public interface TakePhotoCallback {
        void onComplete(boolean success, @Nullable String details);
    }

    public interface Callbacks {
        void sendVirtualStickCommand(double pitch, double roll, double yawAngleDeg, double verticalVelocity);

        void executeGimbalRotate(@Nullable ActionGimbalRotateParam param);

        void executeTakePhoto(@Nullable ActionTakePhotoParam param, @NonNull TakePhotoCallback callback);

        void onPlannerStatus(@NonNull String status);

        void onPlannerLog(@NonNull String line);

        void onMissionComplete();
    }

    private enum MissionActionType {
        ROTATE_YAW,
        HOVER,
        GIMBAL_ROTATE,
        TAKE_PHOTO
    }

    private static class MissionActionStep {
        MissionActionType type;
        int groupId = -1;
        int actionIndex = -1;
        double yawHeadingDeg;
        double hoverSeconds;
        ActionGimbalRotateParam gimbalRotateParam;
        ActionTakePhotoParam takePhotoParam;
    }

    private final Callbacks callbacks;
    private final List<WaylineExecuteWaypoint> missionWaypoints = new ArrayList<>();
    private final List<WaylineActionGroup> missionActionGroups = new ArrayList<>();
    private final Set<Integer> executedActionGroupIds = new HashSet<>();
    private final List<MissionActionStep> pendingMissionActions = new ArrayList<>();
    private int currentWaypointCursor = 0;
    private int currentMissionActionCursor = 0;
    private long currentActionReachedAtMs = 0L;
    private long currentActionWaitUntilMs = 0L;
    private boolean currentGimbalActionTriggered = false;
    private volatile boolean currentTakePhotoCompleted = false;
    private volatile boolean currentTakePhotoSuccess = false;
    @Nullable
    private volatile String currentTakePhotoDetails = null;
    private boolean isMissionStarted = false;
    private boolean isMissionPaused = false;
    private boolean isExecutingWaypointActions = false;

    public MissionPlanner(@NonNull Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void setMissionData(@NonNull List<WaylineExecuteWaypoint> waypoints, @NonNull List<WaylineActionGroup> actionGroups) {
        missionWaypoints.clear();
        missionActionGroups.clear();
        missionWaypoints.addAll(waypoints);
        missionActionGroups.addAll(actionGroups);
        resetExecutionState();
    }

    public void resetExecutionState() {
        isMissionStarted = false;
        isMissionPaused = false;
        isExecutingWaypointActions = false;
        currentWaypointCursor = 0;
        executedActionGroupIds.clear();
        pendingMissionActions.clear();
        currentMissionActionCursor = 0;
        currentActionReachedAtMs = 0L;
        currentActionWaitUntilMs = 0L;
        currentGimbalActionTriggered = false;
        currentTakePhotoCompleted = false;
        currentTakePhotoSuccess = false;
        currentTakePhotoDetails = null;
    }

    public void startFromWaypoint(int startCursor) {
        if (missionWaypoints.isEmpty()) {
            return;
        }
        executedActionGroupIds.clear();
        pendingMissionActions.clear();
        currentMissionActionCursor = 0;
        currentActionReachedAtMs = 0L;
        currentActionWaitUntilMs = 0L;
        currentGimbalActionTriggered = false;
        currentTakePhotoCompleted = false;
        currentTakePhotoSuccess = false;
        currentTakePhotoDetails = null;
        isExecutingWaypointActions = false;
        currentWaypointCursor = clampInt(startCursor, 0, missionWaypoints.size() - 1);
        isMissionStarted = true;
        isMissionPaused = false;
    }

    public void stop() {
        resetExecutionState();
    }

    public void pause() {
        if (isMissionStarted) {
            isMissionPaused = true;
        }
    }

    public void resume() {
        if (isMissionStarted) {
            isMissionPaused = false;
        }
    }

    public boolean isMissionStarted() {
        return isMissionStarted;
    }

    public boolean isMissionPaused() {
        return isMissionPaused;
    }

    public int getCurrentWaypointCursor() {
        return currentWaypointCursor;
    }

    public void tick(@NonNull LocationCoordinate2D droneCurrentLocation, double droneAltitude, double droneHeading) {
        if (!isMissionStarted || isMissionPaused || currentWaypointCursor >= missionWaypoints.size()) {
            return;
        }
        if (isExecutingWaypointActions) {
            runWaypointActionTick(droneHeading);
            return;
        }

        WaylineExecuteWaypoint targetWp = missionWaypoints.get(currentWaypointCursor);
        if (targetWp == null || targetWp.getLocation() == null) {
            advanceToNextWaypoint(currentWaypointCursor, droneHeading);
            return;
        }

        float[] distanceResult = new float[1];
        Location.distanceBetween(
                droneCurrentLocation.getLatitude(), droneCurrentLocation.getLongitude(),
                targetWp.getLocation().getLatitude(), targetWp.getLocation().getLongitude(),
                distanceResult
        );
        double distanceMeters = distanceResult[0];
        double targetAltitude = targetWp.getExecuteHeight() != null ? targetWp.getExecuteHeight() : droneAltitude;
        double altitudeError = targetAltitude - droneAltitude;

        if (distanceMeters <= WP_REACHED_DISTANCE_METERS && Math.abs(altitudeError) <= WP_REACHED_ALTITUDE_METERS) {
            onWaypointReached(targetWp, droneHeading);
            return;
        }

        double bearing = calculateBearing(
                droneCurrentLocation.getLatitude(),
                droneCurrentLocation.getLongitude(),
                targetWp.getLocation().getLatitude(),
                targetWp.getLocation().getLongitude()
        );
        double cruiseSpeed = Math.min(5.0, Math.max(1.0, distanceMeters * 0.35));
        if (droneAltitude < (targetAltitude - 0.5)) {
            cruiseSpeed = 0.0;
        }
        double[] groundVelocity = calculateGroundVelocity(bearing, cruiseSpeed);
        double verticalVelocity = clamp(Math.max(0.0, altitudeError * 0.8), 0.0, 2.0);
        if (droneAltitude < 2.0) {
            verticalVelocity = Math.max(verticalVelocity, 0.8);
        }
        callbacks.sendVirtualStickCommand(groundVelocity[0], groundVelocity[1], bearing, verticalVelocity);
        callbacks.onPlannerStatus(String.format(Locale.US, "VS WP %d dist %.1fm altErr %.1fm",
                targetWp.getWaypointIndex(), distanceMeters, altitudeError));
    }

    private void onWaypointReached(@NonNull WaylineExecuteWaypoint waypoint, double droneHeading) {
        int waypointIndex = waypoint.getWaypointIndex() != null ? waypoint.getWaypointIndex() : currentWaypointCursor;
        int actions = triggerWaypointActions(waypointIndex);
        if (actions > 0) {
            isExecutingWaypointActions = true;
            currentMissionActionCursor = 0;
            currentActionReachedAtMs = 0L;
            currentActionWaitUntilMs = 0L;
            currentGimbalActionTriggered = false;
            callbacks.onPlannerStatus(String.format(Locale.US, "WP %d: executing %d actions", waypointIndex, actions));
            return;
        }
        advanceToNextWaypoint(waypointIndex, droneHeading);
    }

    private int triggerWaypointActions(int waypointIndex) {
        pendingMissionActions.clear();
        for (WaylineActionGroup actionGroup : missionActionGroups) {
            if (actionGroup == null) continue;
            Integer groupId = actionGroup.getGroupId();
            if (groupId == null || executedActionGroupIds.contains(groupId)) continue;
            Integer start = actionGroup.getStartIndex();
            Integer end = actionGroup.getEndIndex();
            if (start == null || end == null || waypointIndex < start || waypointIndex > end) continue;

            executedActionGroupIds.add(groupId);
            List<WaylineActionInfo> actions = actionGroup.getActions();
            if (actions == null || actions.isEmpty()) continue;
            for (int i = 0; i < actions.size(); i++) {
                MissionActionStep step = buildMissionActionStep(actionGroup, i, actions.get(i));
                if (step == null) continue;
                pendingMissionActions.add(step);
                callbacks.onPlannerLog("WP " + waypointIndex + " actionGroup " + groupId + " action " + i + ": " + describeAction(actions.get(i)));
            }
        }
        return pendingMissionActions.size();
    }

    private void runWaypointActionTick(double droneHeading) {
        if (!isExecutingWaypointActions || currentWaypointCursor >= missionWaypoints.size()) {
            return;
        }
        if (currentMissionActionCursor >= pendingMissionActions.size()) {
            WaylineExecuteWaypoint currentWp = missionWaypoints.get(currentWaypointCursor);
            int waypointIndex = currentWp.getWaypointIndex() != null ? currentWp.getWaypointIndex() : currentWaypointCursor;
            pendingMissionActions.clear();
            isExecutingWaypointActions = false;
            currentMissionActionCursor = 0;
            currentActionReachedAtMs = 0L;
            currentActionWaitUntilMs = 0L;
            currentGimbalActionTriggered = false;
            currentTakePhotoCompleted = false;
            currentTakePhotoSuccess = false;
            currentTakePhotoDetails = null;
            advanceToNextWaypoint(waypointIndex, droneHeading);
            return;
        }

        MissionActionStep step = pendingMissionActions.get(currentMissionActionCursor);
        long now = System.currentTimeMillis();
        switch (step.type) {
            case ROTATE_YAW:
                executeRotateYawStep(step, now, droneHeading);
                break;
            case HOVER:
                executeHoverStep(step, now, droneHeading);
                break;
            case GIMBAL_ROTATE:
                executeGimbalRotateStep(step, now, droneHeading);
                break;
            case TAKE_PHOTO:
                executeTakePhotoStep(step, now, droneHeading);
                break;
            default:
                advanceActionCursor();
                break;
        }
    }

    private void executeRotateYawStep(@NonNull MissionActionStep step, long now, double droneHeading) {
        double yawError = normalizeAngleDegrees(step.yawHeadingDeg - droneHeading);
        callbacks.sendVirtualStickCommand(0.0, 0.0, step.yawHeadingDeg, 0.0);
        if (Math.abs(yawError) <= YAW_ACTION_REACHED_THRESHOLD_DEG) {
            if (currentActionReachedAtMs == 0L) {
                currentActionReachedAtMs = now;
            }
            if (now - currentActionReachedAtMs >= YAW_ACTION_SETTLE_MS) {
                callbacks.onPlannerLog(String.format(Locale.US, "ROTATE_YAW done target=%.1f current=%.1f", step.yawHeadingDeg, droneHeading));
                advanceActionCursor();
            }
        } else {
            currentActionReachedAtMs = 0L;
        }
        callbacks.onPlannerStatus(String.format(Locale.US, "Action %d/%d ROTATE_YAW target=%.1f err=%.1f",
                currentMissionActionCursor + 1, pendingMissionActions.size(), step.yawHeadingDeg, yawError));
    }

    private void executeHoverStep(@NonNull MissionActionStep step, long now, double droneHeading) {
        callbacks.sendVirtualStickCommand(0.0, 0.0, droneHeading, 0.0);
        if (currentActionWaitUntilMs == 0L) {
            currentActionWaitUntilMs = now + Math.max(100L, (long) (step.hoverSeconds * 1000.0));
            callbacks.onPlannerLog(String.format(Locale.US, "HOVER start %.1fs", step.hoverSeconds));
        }
        long remainMs = Math.max(0L, currentActionWaitUntilMs - now);
        callbacks.onPlannerStatus(String.format(Locale.US, "Action %d/%d HOVER %.1fs (left %.1fs)",
                currentMissionActionCursor + 1, pendingMissionActions.size(), step.hoverSeconds, remainMs / 1000.0));
        if (now >= currentActionWaitUntilMs) {
            advanceActionCursor();
        }
    }

    private void executeGimbalRotateStep(@NonNull MissionActionStep step, long now, double droneHeading) {
        callbacks.sendVirtualStickCommand(0.0, 0.0, droneHeading, 0.0);
        if (!currentGimbalActionTriggered) {
            currentGimbalActionTriggered = true;
            long waitMs = getGimbalActionWaitMs(step.gimbalRotateParam);
            currentActionWaitUntilMs = now + waitMs;
            callbacks.executeGimbalRotate(step.gimbalRotateParam);
            callbacks.onPlannerLog(String.format(Locale.US, "GIMBAL_ROTATE dispatched (wait %d ms): %s", waitMs, describeActionByParam(step.gimbalRotateParam)));
        }
        long remainMs = Math.max(0L, currentActionWaitUntilMs - now);
        callbacks.onPlannerStatus(String.format(Locale.US, "Action %d/%d %s (left %.1fs)",
                currentMissionActionCursor + 1,
                pendingMissionActions.size(),
                describeActionByParam(step.gimbalRotateParam),
                remainMs / 1000.0));
        if (now >= currentActionWaitUntilMs) {
            advanceActionCursor();
        }
    }

    private void executeTakePhotoStep(@NonNull MissionActionStep step, long now, double droneHeading) {
        callbacks.sendVirtualStickCommand(0.0, 0.0, droneHeading, 0.0);
        if (!currentGimbalActionTriggered) {
            currentGimbalActionTriggered = true;
            currentTakePhotoCompleted = false;
            currentTakePhotoSuccess = false;
            currentTakePhotoDetails = null;
            callbacks.executeTakePhoto(null, (success, details) -> {
                currentTakePhotoSuccess = success;
                currentTakePhotoDetails = details;
                currentTakePhotoCompleted = true;
            });
            callbacks.onPlannerLog("TAKE_PHOTO dispatched");
        }

        if (!currentTakePhotoCompleted) {
            callbacks.onPlannerStatus(String.format(Locale.US, "Action %d/%d TAKE_PHOTO waiting callback",
                    currentMissionActionCursor + 1, pendingMissionActions.size()));
            return;
        }

        if (currentActionWaitUntilMs == 0L) {
            currentActionWaitUntilMs = now + POST_PHOTO_SETTLE_MS;
            if (currentTakePhotoSuccess) {
                callbacks.onPlannerLog("TAKE_PHOTO completed, settling...");
            } else {
                callbacks.onPlannerLog("TAKE_PHOTO failed, settling... " + String.valueOf(currentTakePhotoDetails));
            }
        }
        long remainMs = Math.max(0L, currentActionWaitUntilMs - now);
        callbacks.onPlannerStatus(String.format(Locale.US, "Action %d/%d TAKE_PHOTO settle (left %.1fs)",
                currentMissionActionCursor + 1, pendingMissionActions.size(), remainMs / 1000.0));
        if (now >= currentActionWaitUntilMs) {
            advanceActionCursor();
        }
    }

    private long getGimbalActionWaitMs(@Nullable ActionGimbalRotateParam param) {
        if (param == null) return DEFAULT_GIMBAL_ACTION_DURATION_MS;
        if (Boolean.TRUE.equals(param.getEnableRotateTime()) && param.getRotateTime() != null) {
            return Math.max(400L, (long) (param.getRotateTime() * 1000.0) + 250L);
        }
        return DEFAULT_GIMBAL_ACTION_DURATION_MS;
    }

    private void advanceActionCursor() {
        currentMissionActionCursor++;
        currentActionReachedAtMs = 0L;
        currentActionWaitUntilMs = 0L;
        currentGimbalActionTriggered = false;
        currentTakePhotoCompleted = false;
        currentTakePhotoSuccess = false;
        currentTakePhotoDetails = null;
    }

    private void advanceToNextWaypoint(int waypointIndex, double droneHeading) {
        currentWaypointCursor++;
        if (currentWaypointCursor >= missionWaypoints.size()) {
            isMissionStarted = false;
            isMissionPaused = false;
            isExecutingWaypointActions = false;
            pendingMissionActions.clear();
            callbacks.sendVirtualStickCommand(0.0, 0.0, droneHeading, 0.0);
            callbacks.onPlannerStatus("VS mission complete");
            callbacks.onMissionComplete();
            return;
        }
        callbacks.onPlannerStatus(String.format(Locale.US, "Reached WP %d -> next %d",
                waypointIndex, missionWaypoints.get(currentWaypointCursor).getWaypointIndex()));
    }

    @Nullable
    private MissionActionStep buildMissionActionStep(@NonNull WaylineActionGroup actionGroup, int actionIndex, @Nullable WaylineActionInfo actionInfo) {
        if (actionInfo == null || actionInfo.getActionType() == null) {
            return null;
        }
        String actionType = actionInfo.getActionType().toString();
        MissionActionStep step = new MissionActionStep();
        step.groupId = actionGroup.getGroupId() != null ? actionGroup.getGroupId() : -1;
        step.actionIndex = actionIndex;
        switch (actionType) {
            case "ROTATE_YAW":
                if (actionInfo.getAircraftRotateYawParam() == null || actionInfo.getAircraftRotateYawParam().getHeading() == null) return null;
                step.type = MissionActionType.ROTATE_YAW;
                step.yawHeadingDeg = normalizeTo360(actionInfo.getAircraftRotateYawParam().getHeading());
                return step;
            case "HOVER":
                if (actionInfo.getAircraftHoverParam() == null || actionInfo.getAircraftHoverParam().getHoverTime() == null) return null;
                step.type = MissionActionType.HOVER;
                step.hoverSeconds = Math.max(0.1, actionInfo.getAircraftHoverParam().getHoverTime());
                return step;
            case "GIMBAL_ROTATE":
                if (actionInfo.getGimbalRotateParam() == null) return null;
                step.type = MissionActionType.GIMBAL_ROTATE;
                step.gimbalRotateParam = actionInfo.getGimbalRotateParam();
                return step;
            case "TAKE_PHOTO":
                step.type = MissionActionType.TAKE_PHOTO;
                step.takePhotoParam = actionInfo.getTakePhotoParam();
                return step;
            default:
                return null;
        }
    }

    public static String describeAction(@Nullable WaylineActionInfo actionInfo) {
        if (actionInfo == null || actionInfo.getActionType() == null) {
            return "UNKNOWN ACTION";
        }
        String actionType = actionInfo.getActionType().toString();
        try {
            switch (actionType) {
                case "ROTATE_YAW":
                    return actionType + " heading=" + actionInfo.getAircraftRotateYawParam().getHeading();
                case "HOVER":
                    return actionType + " time=" + actionInfo.getAircraftHoverParam().getHoverTime();
                case "GIMBAL_ROTATE":
                    return actionType + " " + describeActionByParam(actionInfo.getGimbalRotateParam());
                case "TAKE_PHOTO":
                    return actionType + " suffix=" + (actionInfo.getTakePhotoParam() != null ? actionInfo.getTakePhotoParam().getFileSuffix() : "");
                default:
                    return actionType;
            }
        } catch (Exception ignored) {
            return actionType;
        }
    }

    public static String describeActionByParam(@Nullable ActionGimbalRotateParam param) {
        if (param == null) return "GIMBAL_ROTATE null";
        return String.format(Locale.US,
                "mode=%s pitch(%s)=%.1f roll(%s)=%.1f yaw(%s)=%.1f",
                String.valueOf(param.getRotateMode()),
                String.valueOf(param.getEnablePitch()),
                param.getPitch() != null ? param.getPitch() : 0.0,
                String.valueOf(param.getEnableRoll()),
                param.getRoll() != null ? param.getRoll() : 0.0,
                String.valueOf(param.getEnableYaw()),
                param.getYaw() != null ? param.getYaw() : 0.0);
    }

    private static double[] calculateGroundVelocity(double targetHeadingDeg, double speedMetersPerSecond) {
        double targetRad = Math.toRadians(targetHeadingDeg);
        double northVelocity = speedMetersPerSecond * Math.cos(targetRad);
        double eastVelocity = speedMetersPerSecond * Math.sin(targetRad);
        return new double[]{clamp(northVelocity, -6.0, 6.0), clamp(eastVelocity, -6.0, 6.0)};
    }

    private static double calculateBearing(double lat1, double lon1, double lat2, double lon2) {
        double startLat = Math.toRadians(lat1);
        double startLng = Math.toRadians(lon1);
        double endLat = Math.toRadians(lat2);
        double endLng = Math.toRadians(lon2);
        double y = Math.sin(endLng - startLng) * Math.cos(endLat);
        double x = Math.cos(startLat) * Math.sin(endLat) - Math.sin(startLat) * Math.cos(endLat) * Math.cos(endLng - startLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeAngleDegrees(double angleDeg) {
        double out = angleDeg % 360.0;
        if (out > 180.0) out -= 360.0;
        if (out < -180.0) out += 360.0;
        return out;
    }

    private static double normalizeTo360(double angleDeg) {
        double out = angleDeg % 360.0;
        if (out < 0.0) out += 360.0;
        return out;
    }
}
