package it.unipg.gearlab;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dji.sdk.keyvalue.key.FlightControllerKey;
import dji.sdk.keyvalue.key.GimbalKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.sdk.keyvalue.value.gimbal.GimbalAttitudeRange;
import dji.v5.manager.KeyManager;

public class DroneStateRepository {

    public interface Callbacks {
        void onConnectionChanged(@Nullable Boolean connected);

        void onBatteryPercentChanged(@Nullable Integer percent);

        void onAircraftAttitudeChanged(double yawDeg);

        void onHomeLocationChanged(double latitude, double longitude);

        void onAircraftLocation3DChanged(double latitude, double longitude, double altitude);

        void onAircraftVelocityChanged(double speedX, double speedY, double speedZ);

        void onGimbalYawAdjustSupported(boolean isSupported);

        void onGimbalAttitudeRangeChanged(@NonNull GimbalAttitudeRange range);

        void onGimbalAttitudeChanged(double pitchDeg, double rollDeg, double yawDeg);
    }

    public void start(@NonNull Object owner, @NonNull Callbacks callbacks) {
        listenConnection(owner, callbacks);
        listenBatteryPercent(owner, callbacks);
        listenAircraftAttitude(owner, callbacks);
        listenHomeLocation(owner, callbacks);
        listenAircraftLocation3D(owner, callbacks);
        listenAircraftVelocity(owner, callbacks);
        listenGimbalYawCapability(owner, callbacks);
        listenGimbalAttitudeRange(owner, callbacks);
        listenGimbalAttitude(owner, callbacks);
        fetchInitialGimbalAttitude(callbacks);
    }

    private void listenConnection(@NonNull Object owner, @NonNull Callbacks callbacks) {
        KeyManager.getInstance().listen(
                KeyTools.createKey(FlightControllerKey.KeyConnection),
                owner,
                (oldValue, newValue) -> callbacks.onConnectionChanged(newValue)
        );
    }

    private void listenBatteryPercent(@NonNull Object owner, @NonNull Callbacks callbacks) {
        KeyManager.getInstance().listen(
                KeyTools.createKey(FlightControllerKey.KeyBatteryPowerPercent),
                owner,
                (oldValue, newValue) -> callbacks.onBatteryPercentChanged(newValue)
        );
    }

    private void listenAircraftAttitude(@NonNull Object owner, @NonNull Callbacks callbacks) {
        KeyManager.getInstance().listen(
                KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude),
                owner,
                (attitude, t1) -> {
                    if (attitude != null) {
                        callbacks.onAircraftAttitudeChanged(attitude.getYaw());
                    }
                }
        );
    }

    private void listenHomeLocation(@NonNull Object owner, @NonNull Callbacks callbacks) {
        KeyManager.getInstance().listen(
                KeyTools.createKey(FlightControllerKey.KeyHomeLocation),
                owner,
                (locationCoordinate2D, t1) -> {
                    if (locationCoordinate2D != null) {
                        callbacks.onHomeLocationChanged(
                                locationCoordinate2D.getLatitude(),
                                locationCoordinate2D.getLongitude()
                        );
                    }
                }
        );
    }

    private void listenAircraftLocation3D(@NonNull Object owner, @NonNull Callbacks callbacks) {
        KeyManager.getInstance().listen(
                KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D),
                owner,
                (locationCoordinate3D, t1) -> {
                    if (locationCoordinate3D != null) {
                        callbacks.onAircraftLocation3DChanged(
                                locationCoordinate3D.getLatitude(),
                                locationCoordinate3D.getLongitude(),
                                locationCoordinate3D.getAltitude()
                        );
                    }
                }
        );
    }

    private void listenAircraftVelocity(@NonNull Object owner, @NonNull Callbacks callbacks) {
        KeyManager.getInstance().listen(
                KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity),
                owner,
                (velocity3D, t1) -> {
                    if (velocity3D != null) {
                        callbacks.onAircraftVelocityChanged(
                                velocity3D.getX(),
                                velocity3D.getY(),
                                velocity3D.getZ()
                        );
                    }
                }
        );
    }

    private void listenGimbalYawCapability(@NonNull Object owner, @NonNull Callbacks callbacks) {
        KeyManager.getInstance().listen(
                KeyTools.createKey(GimbalKey.KeyYawAdjustSupported, ComponentIndexType.LEFT_OR_MAIN),
                owner,
                (isSupported, t1) -> {
                    if (isSupported != null) {
                        callbacks.onGimbalYawAdjustSupported(isSupported);
                    }
                }
        );
    }

    private void listenGimbalAttitudeRange(@NonNull Object owner, @NonNull Callbacks callbacks) {
        KeyManager.getInstance().listen(
                KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, ComponentIndexType.LEFT_OR_MAIN),
                owner,
                (range, t1) -> {
                    if (range != null) {
                        callbacks.onGimbalAttitudeRangeChanged(range);
                    }
                }
        );
    }

    private void listenGimbalAttitude(@NonNull Object owner, @NonNull Callbacks callbacks) {
        KeyManager.getInstance().listen(
                KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.LEFT_OR_MAIN),
                owner,
                (attitude, t1) -> {
                    if (attitude != null) {
                        callbacks.onGimbalAttitudeChanged(
                                attitude.getPitch(),
                                attitude.getRoll(),
                                attitude.getYaw()
                        );
                    }
                }
        );
    }

    private void fetchInitialGimbalAttitude(@NonNull Callbacks callbacks) {
        Object attitude = KeyManager.getInstance().getValue(
                KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.LEFT_OR_MAIN)
        );
        if (attitude == null) {
            return;
        }
        Double pitch = readAttitudeComponent(attitude, "getPitch");
        Double roll = readAttitudeComponent(attitude, "getRoll");
        Double yaw = readAttitudeComponent(attitude, "getYaw");
        if (pitch != null && roll != null && yaw != null) {
            callbacks.onGimbalAttitudeChanged(pitch, roll, yaw);
        }
    }

    @Nullable
    private Double readAttitudeComponent(@NonNull Object attitude, @NonNull String getterName) {
        try {
            Object value = attitude.getClass().getMethod(getterName).invoke(attitude);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } catch (Exception ignored) {
            // no-op: this is just a best-effort initial snapshot
        }
        return null;
    }
}
