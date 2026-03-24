package it.unipg.gearlab;

import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.Locale;

import dji.sdk.keyvalue.key.GimbalKey;
import dji.sdk.keyvalue.key.CameraKey;
import dji.sdk.keyvalue.key.DJIActionKeyInfo;
import dji.sdk.keyvalue.key.FlightControllerKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.sdk.keyvalue.value.common.DoubleMinMax;
import dji.sdk.keyvalue.value.common.EmptyMsg;
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem;
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode;
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode;
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam;
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode;
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation;
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode;
import dji.sdk.keyvalue.value.gimbal.GimbalAttitudeRange;
import dji.sdk.wpmz.value.mission.ActionGimbalRotateParam;
import dji.sdk.wpmz.value.mission.ActionTakePhotoParam;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.manager.KeyManager;
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager;

public class DroneCommander {
    private static final String TAG = DroneCommander.class.getSimpleName();
    private static final int DEFAULT_RTH_ALTITUDE_METERS = 30;
    private static final int RTH_SET_MAX_RETRIES = 5;
    private static final long RTH_SET_RETRY_DELAY_MS = 1000L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean hasGimbalYawCapabilityInfo = false;
    private boolean gimbalYawAdjustSupported = false;
    @Nullable
    private GimbalAttitudeRange gimbalAttitudeRange = null;
    private double latestGimbalPitchDeg = Double.NaN;

    public interface CommandCallback {
        void onSuccess();

        void onFailure(@NonNull IDJIError idjiError);
    }

    public interface PhotoCallback {
        void onComplete(boolean success, @Nullable String details);
    }

    public void enableVirtualStick(@NonNull CommandCallback callback) {
        VirtualStickManager.getInstance().enableVirtualStick(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true);
                callback.onSuccess();
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                callback.onFailure(idjiError);
            }
        });
    }

    public void disableVirtualStick(@NonNull CommandCallback callback) {
        VirtualStickManager.getInstance().disableVirtualStick(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                callback.onSuccess();
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                callback.onFailure(idjiError);
            }
        });
    }

    public void holdPosition(double currentHeadingDeg) {
        sendVirtualStickCommand(0.0, 0.0, currentHeadingDeg, 0.0, currentHeadingDeg);
    }

    public void sendVirtualStickCommand(
            double pitch,
            double roll,
            double yawAngleDeg,
            double verticalVelocity,
            double currentHeadingDeg
    ) {
        VirtualStickFlightControlParam param = new VirtualStickFlightControlParam();
        param.setRollPitchCoordinateSystem(FlightCoordinateSystem.GROUND);
        param.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        param.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        param.setVerticalControlMode(VerticalControlMode.VELOCITY);
        param.setPitch(clamp(roll, -6.0, 6.0));
        param.setRoll(clamp(pitch, -6.0, 6.0));

        double yawError = normalizeAngleDegrees(yawAngleDeg - currentHeadingDeg);
        double yawRate = clamp(yawError * 1.2, -45.0, 45.0);
        param.setYaw(yawRate);
        param.setVerticalThrottle(clamp(verticalVelocity, -2.0, 2.0));

        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param);
    }

    public void executeGimbalRotate(@Nullable ActionGimbalRotateParam param) {
        if (param == null) {
            return;
        }

        boolean enablePitch = Boolean.TRUE.equals(param.getEnablePitch());
        boolean enableRoll = Boolean.TRUE.equals(param.getEnableRoll());
        boolean enableYaw = Boolean.TRUE.equals(param.getEnableYaw());
        double pitch = param.getPitch() != null ? param.getPitch() : 0.0;
        double roll = param.getRoll() != null ? param.getRoll() : 0.0;
        double yaw = param.getYaw() != null ? param.getYaw() : 0.0;

        if (enableYaw && hasGimbalYawCapabilityInfo && !gimbalYawAdjustSupported) {
            Log.w(TAG, "Skipping gimbal yaw action: yaw adjust not supported on this product.");
            enableYaw = false;
        }

        DoubleMinMax pitchRange = gimbalAttitudeRange != null ? gimbalAttitudeRange.getPitch() : null;
        DoubleMinMax rollRange = gimbalAttitudeRange != null ? gimbalAttitudeRange.getRoll() : null;
        DoubleMinMax yawRange = gimbalAttitudeRange != null ? gimbalAttitudeRange.getYaw() : null;

        if (enablePitch) {
            double clamped = clampToRange(pitch, pitchRange);
            if (clamped != pitch) {
                Log.w(TAG, String.format(Locale.US, "Clamp gimbal pitch %.1f -> %.1f", pitch, clamped));
            }
            pitch = clamped;
        }
        if (enableRoll) {
            double clamped = clampToRange(roll, rollRange);
            if (clamped != roll) {
                Log.w(TAG, String.format(Locale.US, "Clamp gimbal roll %.1f -> %.1f", roll, clamped));
            }
            roll = clamped;
        }
        if (enableYaw) {
            double clamped = clampToRange(yaw, yawRange);
            if (clamped != yaw) {
                Log.w(TAG, String.format(Locale.US, "Clamp gimbal yaw %.1f -> %.1f", yaw, clamped));
            }
            yaw = clamped;
        }

        if (!enablePitch && !enableRoll && !enableYaw) {
            Log.w(TAG, "Skipping gimbal action: no enabled axis after capability/range filtering.");
            return;
        }

        GimbalAngleRotation rotation = new GimbalAngleRotation();
        String rotateMode = param.getRotateMode() != null ? param.getRotateMode().toString() : "";
        rotation.setMode(rotateMode.contains("RELATIVE")
                ? GimbalAngleRotationMode.RELATIVE_ANGLE
                : GimbalAngleRotationMode.ABSOLUTE_ANGLE);
        rotation.setPitch(pitch);
        rotation.setRoll(roll);
        rotation.setYaw(yaw);
        rotation.setPitchIgnored(!enablePitch);
        rotation.setRollIgnored(!enableRoll);
        rotation.setYawIgnored(!enableYaw);
        rotation.setDuration(Boolean.TRUE.equals(param.getEnableRotateTime()) && param.getRotateTime() != null
                ? param.getRotateTime()
                : 1.0);
        rotation.setJointReferenceUsed(false);
        rotation.setTimeout(3);

        KeyManager.getInstance().performAction(
                KeyTools.createKey(GimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN),
                rotation,
                new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                    @Override
                    public void onSuccess(EmptyMsg emptyMsg) {
                        Log.i(TAG, "GIMBAL_ROTATE onSuccess");
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError idjiError) {
                        Log.e(TAG, "GIMBAL_ROTATE onFailure: " + idjiError);
                    }
                }
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void executeTakePhoto(@Nullable ActionTakePhotoParam param, @Nullable PhotoCallback callback) {
        try {
            Field keyField = CameraKey.class.getField("KeyStartShootPhoto");
            Object keyInfoObj = keyField.get(null);
            if (!(keyInfoObj instanceof DJIActionKeyInfo)) {
                Log.w(TAG, "TAKE_PHOTO not supported: KeyStartShootPhoto is not an action key");
                if (callback != null) {
                    callback.onComplete(false, "KeyStartShootPhoto not action key");
                }
                return;
            }
            DJIActionKeyInfo actionKeyInfo = (DJIActionKeyInfo) keyInfoObj;
            KeyManager.getInstance().performAction(
                    KeyTools.createKey(actionKeyInfo, ComponentIndexType.LEFT_OR_MAIN),
                    new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                    @Override
                    public void onSuccess(EmptyMsg emptyMsg) {
                        Log.i(TAG, "TAKE_PHOTO onSuccess");
                        if (callback != null) {
                            callback.onComplete(true, null);
                        }
                    }

                        @Override
                        public void onFailure(@NonNull IDJIError idjiError) {
                            Log.e(TAG, "TAKE_PHOTO onFailure: " + idjiError);
                            if (callback != null) {
                                callback.onComplete(false, idjiError.toString());
                            }
                        }
                    }
            );
        } catch (NoSuchFieldException e) {
            Log.w(TAG, "TAKE_PHOTO not supported in this SDK build (KeyStartShootPhoto missing)");
            if (callback != null) {
                callback.onComplete(false, "KeyStartShootPhoto missing");
            }
        } catch (Exception e) {
            Log.e(TAG, "TAKE_PHOTO failed", e);
            if (callback != null) {
                callback.onComplete(false, e.getMessage());
            }
        }
    }

    public void applyDefaultRthAltitude() {
        setGoHomeHeightWithRetry(DEFAULT_RTH_ALTITUDE_METERS, RTH_SET_MAX_RETRIES);
    }

    public void setGoHomeHeight(int meters) {
        setGoHomeHeightInternal(meters, null);
    }

    private void setGoHomeHeightWithRetry(int meters, int retriesLeft) {
        setGoHomeHeightInternal(meters, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                // No-op
            }

            @Override
            public void onFailure(@NonNull IDJIError idjiError) {
                if (retriesLeft <= 0) {
                    Log.e(TAG, "KeyGoHomeHeight failed after retries: " + idjiError);
                    return;
                }
                Log.w(TAG, "KeyGoHomeHeight retry in " + RTH_SET_RETRY_DELAY_MS + "ms, left=" + retriesLeft + " error=" + idjiError);
                mainHandler.postDelayed(() -> setGoHomeHeightWithRetry(meters, retriesLeft - 1), RTH_SET_RETRY_DELAY_MS);
            }
        });
    }

    private void setGoHomeHeightInternal(int meters, @Nullable CommonCallbacks.CompletionCallback callback) {
        KeyManager.getInstance().setValue(
                KeyTools.createKey(FlightControllerKey.KeyGoHomeHeight),
                meters,
                new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(TAG, "KeyGoHomeHeight set to " + meters + "m");
                        if (callback != null) {
                            callback.onSuccess();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError idjiError) {
                        Log.e(TAG, "KeyGoHomeHeight set failure: " + idjiError);
                        if (callback != null) {
                            callback.onFailure(idjiError);
                        }
                    }
                }
        );
    }

    public void updateGimbalYawAdjustCapability(boolean isSupported) {
        hasGimbalYawCapabilityInfo = true;
        gimbalYawAdjustSupported = isSupported;
    }

    public void updateCurrentGimbalPitch(double pitchDeg) {
        latestGimbalPitchDeg = pitchDeg;
    }

    public void updateGimbalAttitudeRange(@Nullable GimbalAttitudeRange range) {
        gimbalAttitudeRange = range;
    }

    private static double clampToRange(double value, @Nullable DoubleMinMax range) {
        if (range == null || range.getMin() == null || range.getMax() == null) {
            return value;
        }
        return clamp(value, range.getMin(), range.getMax());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeAngleDegrees(double angleDeg) {
        double out = angleDeg % 360.0;
        if (out > 180.0) {
            out -= 360.0;
        }
        if (out < -180.0) {
            out += 360.0;
        }
        return out;
    }
}
