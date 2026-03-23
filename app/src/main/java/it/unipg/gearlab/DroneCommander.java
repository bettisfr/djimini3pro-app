package it.unipg.gearlab;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import dji.sdk.keyvalue.key.GimbalKey;
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
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.manager.KeyManager;
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager;

public class DroneCommander {
    private static final String TAG = DroneCommander.class.getSimpleName();

    private boolean hasGimbalYawCapabilityInfo = false;
    private boolean gimbalYawAdjustSupported = false;
    @Nullable
    private GimbalAttitudeRange gimbalAttitudeRange = null;

    public interface CommandCallback {
        void onSuccess();

        void onFailure(@NonNull IDJIError idjiError);
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

    public void updateGimbalYawAdjustCapability(boolean isSupported) {
        hasGimbalYawCapabilityInfo = true;
        gimbalYawAdjustSupported = isSupported;
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
