package it.unipg.gearlab;

import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineDasharray;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;

import android.content.res.Resources;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

import dji.sdk.wpmz.value.mission.WaylineExecuteWaypoint;

public class MissionMapRenderer {
    private final Resources resources;

    public MissionMapRenderer(@NonNull Resources resources) {
        this.resources = resources;
    }

    public void renderMission(
            @Nullable Style mapStyle,
            @NonNull List<WaylineExecuteWaypoint> waypoints,
            double homeLat,
            double homeLon
    ) {
        if (mapStyle == null || waypoints.isEmpty()) {
            return;
        }

        clearPreviousMission(mapStyle);

        List<Point> homeToFirstWaypointPoints = new ArrayList<>();
        homeToFirstWaypointPoints.add(Point.fromLngLat(homeLon, homeLat));
        WaylineExecuteWaypoint firstWaypoint = waypoints.get(0);
        homeToFirstWaypointPoints.add(Point.fromLngLat(
                firstWaypoint.getLocation().getLongitude(),
                firstWaypoint.getLocation().getLatitude()
        ));

        GeoJsonSource homeToFirstWaypointSource = new GeoJsonSource(
                "home-to-firstwaypoint-source",
                LineString.fromLngLats(homeToFirstWaypointPoints)
        );
        mapStyle.addSource(homeToFirstWaypointSource);

        LineLayer homeToFirstWaypointLayer = new LineLayer(
                "home-to-firstwaypoint-layer",
                "home-to-firstwaypoint-source"
        );
        homeToFirstWaypointLayer.setProperties(
                lineColor("magenta"),
                lineWidth(2.0f),
                lineDasharray(new Float[]{2f, 4f})
        );
        mapStyle.addLayer(homeToFirstWaypointLayer);

        List<Point> lastWaypointToHomePoints = new ArrayList<>();
        WaylineExecuteWaypoint lastWaypoint = waypoints.get(waypoints.size() - 1);
        lastWaypointToHomePoints.add(Point.fromLngLat(
                lastWaypoint.getLocation().getLongitude(),
                lastWaypoint.getLocation().getLatitude()
        ));
        lastWaypointToHomePoints.add(Point.fromLngLat(homeLon, homeLat));

        GeoJsonSource lastWaypointToHomeSource = new GeoJsonSource(
                "lastwaypoint-to-home-source",
                LineString.fromLngLats(lastWaypointToHomePoints)
        );
        mapStyle.addSource(lastWaypointToHomeSource);

        LineLayer lastWaypointToHomeLayer = new LineLayer(
                "lastwaypoint-to-home-layer",
                "lastwaypoint-to-home-source"
        );
        lastWaypointToHomeLayer.setProperties(
                lineColor("magenta"),
                lineWidth(2.0f),
                lineDasharray(new Float[]{2f, 4f})
        );
        mapStyle.addLayer(lastWaypointToHomeLayer);

        List<Point> waypointToWaypointPoints = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            WaylineExecuteWaypoint currentWaypoint = waypoints.get(i);
            WaylineExecuteWaypoint nextWaypoint = waypoints.get(i + 1);
            waypointToWaypointPoints.add(Point.fromLngLat(
                    currentWaypoint.getLocation().getLongitude(),
                    currentWaypoint.getLocation().getLatitude()
            ));
            waypointToWaypointPoints.add(Point.fromLngLat(
                    nextWaypoint.getLocation().getLongitude(),
                    nextWaypoint.getLocation().getLatitude()
            ));
        }

        GeoJsonSource waypointToWaypointSource = new GeoJsonSource(
                "waypoint-to-waypoint-source",
                LineString.fromLngLats(waypointToWaypointPoints)
        );
        mapStyle.addSource(waypointToWaypointSource);

        LineLayer waypointToWaypointLayer = new LineLayer(
                "waypoint-to-waypoint-layer",
                "waypoint-to-waypoint-source"
        );
        waypointToWaypointLayer.setProperties(
                lineColor("orange"),
                lineWidth(2.0f)
        );
        mapStyle.addLayer(waypointToWaypointLayer);

        mapStyle.addImage("home-marker-icon", BitmapFactory.decodeResource(resources, R.drawable.home_location));
        GeoJsonSource homeMarkerSource = new GeoJsonSource("mission-home-source");
        List<Feature> homeFeatures = new ArrayList<>();
        homeFeatures.add(Feature.fromGeometry(Point.fromLngLat(homeLon, homeLat)));
        homeMarkerSource.setGeoJson(FeatureCollection.fromFeatures(homeFeatures));
        mapStyle.addSource(homeMarkerSource);

        SymbolLayer homeMarkerLayer = new SymbolLayer("mission-home-layer", "mission-home-source");
        homeMarkerLayer.setProperties(
                iconImage("home-marker-icon"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
        );
        mapStyle.addLayer(homeMarkerLayer);

        mapStyle.addImage("waypoint-marker-icon", BitmapFactory.decodeResource(resources, R.drawable.marker_red));
        GeoJsonSource waypointMarkerSource = new GeoJsonSource("waypoint-marker-source");
        List<Feature> waypointFeatures = new ArrayList<>();
        for (WaylineExecuteWaypoint waypoint : waypoints) {
            waypointFeatures.add(Feature.fromGeometry(Point.fromLngLat(
                    waypoint.getLocation().getLongitude(),
                    waypoint.getLocation().getLatitude()
            )));
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

    private void clearPreviousMission(@NonNull Style mapStyle) {
        removeLayerIfPresent(mapStyle, "mission-home-layer");
        removeLayerIfPresent(mapStyle, "waypoint-marker-layer");
        removeLayerIfPresent(mapStyle, "home-to-firstwaypoint-layer");
        removeLayerIfPresent(mapStyle, "lastwaypoint-to-home-layer");
        removeLayerIfPresent(mapStyle, "waypoint-to-waypoint-layer");

        removeSourceIfPresent(mapStyle, "mission-home-source");
        removeSourceIfPresent(mapStyle, "waypoint-marker-source");
        removeSourceIfPresent(mapStyle, "home-to-firstwaypoint-source");
        removeSourceIfPresent(mapStyle, "lastwaypoint-to-home-source");
        removeSourceIfPresent(mapStyle, "waypoint-to-waypoint-source");
    }

    private void removeLayerIfPresent(@NonNull Style mapStyle, @NonNull String layerId) {
        if (mapStyle.getLayer(layerId) != null) {
            mapStyle.removeLayer(layerId);
        }
    }

    private void removeSourceIfPresent(@NonNull Style mapStyle, @NonNull String sourceId) {
        if (mapStyle.getSource(sourceId) != null) {
            mapStyle.removeSource(sourceId);
        }
    }
}
