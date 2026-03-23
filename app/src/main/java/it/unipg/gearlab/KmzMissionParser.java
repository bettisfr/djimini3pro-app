package it.unipg.gearlab;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dji.wpmzsdk.common.data.KMZInfo;
import com.dji.wpmzsdk.manager.WPMZManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dji.sdk.wpmz.value.mission.Wayline;
import dji.sdk.wpmz.value.mission.WaylineActionGroup;
import dji.sdk.wpmz.value.mission.WaylineExecuteWaypoint;

public final class KmzMissionParser {

    public static final class ParsedMission {
        @NonNull
        public final String missionPath;
        @NonNull
        public final KMZInfo kmzInfo;
        @NonNull
        public final List<WaylineExecuteWaypoint> waypoints;
        @NonNull
        public final List<WaylineActionGroup> actionGroups;

        private ParsedMission(
                @NonNull String missionPath,
                @NonNull KMZInfo kmzInfo,
                @NonNull List<WaylineExecuteWaypoint> waypoints,
                @NonNull List<WaylineActionGroup> actionGroups
        ) {
            this.missionPath = missionPath;
            this.kmzInfo = kmzInfo;
            this.waypoints = waypoints;
            this.actionGroups = actionGroups;
        }
    }

    public static final class Result {
        @Nullable
        public final ParsedMission mission;
        @Nullable
        public final String error;

        private Result(@Nullable ParsedMission mission, @Nullable String error) {
            this.mission = mission;
            this.error = error;
        }

        @NonNull
        public static Result success(@NonNull ParsedMission mission) {
            return new Result(mission, null);
        }

        @NonNull
        public static Result error(@NonNull String error) {
            return new Result(null, error);
        }

        public boolean isSuccess() {
            return mission != null;
        }
    }

    private KmzMissionParser() {
    }

    @NonNull
    public static Result parse(@NonNull File missionFile) {
        String missionPath = missionFile.getAbsolutePath();

        KMZInfo kmzInfo = WPMZManager.getInstance().getKMZInfo(missionPath);
        if (kmzInfo == null) {
            return Result.error("Invalid KMZ file");
        }

        List<Wayline> waylines;
        try {
            waylines = kmzInfo.getWaylineWaylinesParseInfo().getWaylines();
        } catch (Exception e) {
            return Result.error("KMZ parse error");
        }

        if (waylines == null || waylines.isEmpty()) {
            return Result.error("KMZ has no waylines");
        }

        Wayline firstWayline = waylines.get(0);
        List<WaylineExecuteWaypoint> waypoints = new ArrayList<>();
        List<WaylineActionGroup> actionGroups = new ArrayList<>();

        if (firstWayline.getWaypoints() != null) {
            waypoints.addAll(firstWayline.getWaypoints());
        }
        if (firstWayline.getActionGroups() != null) {
            actionGroups.addAll(firstWayline.getActionGroups());
        }

        if (waypoints.isEmpty()) {
            return Result.error("KMZ has no waypoints");
        }

        ParsedMission parsedMission = new ParsedMission(missionPath, kmzInfo, waypoints, actionGroups);
        return Result.success(parsedMission);
    }
}
