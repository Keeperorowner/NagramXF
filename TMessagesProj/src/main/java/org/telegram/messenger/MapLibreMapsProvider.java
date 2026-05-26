package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableKt;
import androidx.core.util.Consumer;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class MapLibreMapsProvider implements IMapsProvider {

    static final String BRIGHT_STYLE = "https://tiles.openfreemap.org/styles/bright";
    static final String SATELLITE_STYLE_JSON = "{" +
        "\"version\": 8," +
        "\"sources\": {" +
            "\"esri\": {" +
                "\"type\": \"raster\"," +
                "\"tiles\": [\"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}\"]," +
                "\"tileSize\": 256," +
                "\"maxzoom\": 19" +
            "}" +
        "}," +
        "\"layers\": [{ \"id\": \"esri\", \"type\": \"raster\", \"source\": \"esri\" }]" +
        "}";
    static final String ATTRIBUTION_BRIGHT = "<a href=\"https://openfreemap.org/\">OpenFreeMap</a> <a href=\"https://www.openmaptiles.org/\">© OpenMapTiles</a> Data from <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>";
    static final String ATTRIBUTION_SATELLITE = "Powered by <a href=\"https://www.esri.com/\">Esri</a>";
    static final float MAX_ZOOM = 21f;

    static final String SRC_MARKERS = "inu_markers";
    static final String SRC_MARKERS_FLAT = "inu_markers_flat";
    static final String SRC_CIRCLES = "inu_circles";
    static final String LAYER_MARKERS = "inu_markers_layer";
    static final String LAYER_MARKERS_FLAT = "inu_markers_flat_layer";
    static final String LAYER_CIRCLES_FILL = "inu_circles_fill_layer";
    static final String LAYER_CIRCLES_STROKE = "inu_circles_stroke_layer";

    private static org.maplibre.android.geometry.LatLng ml(double lat, double lng) {
        return new org.maplibre.android.geometry.LatLng(lat, lng);
    }

    private static IMapsProvider.LatLng fromMl(org.maplibre.android.geometry.LatLng ml) {
        return new IMapsProvider.LatLng(ml.getLatitude(), ml.getLongitude());
    }

    static String colorToCss(int c) {
        return "rgba(" + Color.red(c) + "," + Color.green(c) + "," + Color.blue(c) + "," + (Color.alpha(c) / 255f) + ")";
    }

    static Polygon buildCirclePolygon(org.maplibre.android.geometry.LatLng center, double radiusM, int segments) {
        ArrayList<org.maplibre.geojson.Point> coords = new ArrayList<>(segments + 1);
        double lat = Math.toRadians(center.getLatitude());
        double lng = Math.toRadians(center.getLongitude());
        double ang = radiusM / 6378137.0;
        for (int i = 0; i <= segments; i++) {
            double brng = 2 * Math.PI * i / segments;
            double lat2 = Math.asin(Math.sin(lat) * Math.cos(ang) + Math.cos(lat) * Math.sin(ang) * Math.cos(brng));
            double lng2 = lng + Math.atan2(
                Math.sin(brng) * Math.sin(ang) * Math.cos(lat),
                Math.cos(ang) - Math.sin(lat) * Math.sin(lat2)
            );
            coords.add(org.maplibre.geojson.Point.fromLngLat(Math.toDegrees(lng2), Math.toDegrees(lat2)));
        }
        return Polygon.fromLngLats(List.of(coords));
    }

    static Bitmap blueDotBitmap() {
        int sizePx = Math.max(AndroidUtilities.dp(18), 8);
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float c = sizePx / 2f;
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(Color.WHITE);
        canvas.drawCircle(c, c, c, fillPaint);
        fillPaint.setColor(0xFF4285F4);
        canvas.drawCircle(c, c, c * 0.78f, fillPaint);
        return bmp;
    }

    static Bitmap headingArrowBitmap(Context ctx) {
        float triW = AndroidUtilities.dp(10f);
        float triH = triW / 2f;
        int w = (int) triW;
        int h = (int) (triH + AndroidUtilities.dp(9f));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Path path = new Path();
        path.moveTo(w / 2f, 0f);
        path.lineTo(w, triH);
        path.lineTo(0f, triH);
        path.close();
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(0xFF4285F4);
        canvas.drawPath(path, fillPaint);
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(0.75f * ctx.getResources().getDisplayMetrics().density);
        canvas.drawPath(path, strokePaint);
        return bmp;
    }

    static Bitmap padForCenterAnchor(Bitmap bmp, float anchorU, float anchorV) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        float cu = anchorU * w;
        float cv = anchorV * h;
        int newW = Math.max(2 * (int) Math.max(cu, w - cu), w);
        int newH = Math.max(2 * (int) Math.max(cv, h - cv), h);
        if (newW == w && newH == h) return bmp;
        Bitmap out = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(bmp, newW / 2f - cu, newH / 2f - cv, null);
        return out;
    }

    static Bitmap resToBitmap(Context ctx, int resId) {
        try {
            Drawable drawable = ContextCompat.getDrawable(ctx, resId);
            if (drawable == null) return null;
            if (drawable instanceof BitmapDrawable) return ((BitmapDrawable) drawable).getBitmap();
            return DrawableKt.toBitmap(drawable, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void initializeMaps(Context context) {
        MapLibre.getInstance(context);
    }

    @Override
    public IMapView onCreateMapView(Context context) {
        return new MlIMapView(context);
    }

    @Override
    public IMarkerOptions onCreateMarkerOptions(IMapView imapView) {
        return new MlMarkerOptionsImpl();
    }

    @Override
    public ICircleOptions onCreateCircleOptions() {
        return new MlCircleOptionsImpl();
    }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() {
        return new MlBoundsBuilderImpl();
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLng(IMapsProvider.LatLng latLng) {
        return new MlCameraUpdate(CameraUpdateFactory.newLatLng(ml(latLng.latitude, latLng.longitude)));
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(IMapsProvider.LatLng latLng, float zoom) {
        return new MlCameraUpdate(CameraUpdateFactory.newLatLngZoom(ml(latLng.latitude, latLng.longitude), zoom));
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) {
        MlLatLngBoundsImpl mlBounds = (MlLatLngBoundsImpl) bounds;
        return new MlCameraUpdate(CameraUpdateFactory.newLatLngBounds(mlBounds.bounds, padding));
    }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) {
        return null;
    }

    @Override
    public String getMapsAppPackageName() {
        return ApplicationLoader.applicationContext.getPackageName();
    }

    @Override
    public int getInstallMapsString() {
        return R.string.InstallGoogleMaps;
    }

    public final static class MlCameraUpdate implements ICameraUpdate {
        final org.maplibre.android.camera.CameraUpdate cameraUpdate;

        MlCameraUpdate(org.maplibre.android.camera.CameraUpdate cameraUpdate) {
            this.cameraUpdate = cameraUpdate;
        }
    }

    public final static class MlIMapView implements IMapView {
        final MapView mapView;
        private final FrameLayout viewWrapper;
        final TextView attribution;
        private ITouchInterceptor dispatchInterceptor;
        private ITouchInterceptor interceptInterceptor;
        private Runnable onLayoutListener;
        MlIMap imap;

        private MlIMapView(Context context) {
            MapLibre.getInstance(context);

            MapLibreMapOptions options = MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .attributionEnabled(false)
                .logoEnabled(false);

            mapView = new MapView(context, options) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (dispatchInterceptor != null) {
                        return dispatchInterceptor.onInterceptTouchEvent(ev, super::dispatchTouchEvent);
                    }
                    return super.dispatchTouchEvent(ev);
                }

                @Override
                public boolean onInterceptTouchEvent(MotionEvent ev) {
                    if (interceptInterceptor != null) {
                        return interceptInterceptor.onInterceptTouchEvent(ev, super::onInterceptTouchEvent);
                    }
                    return super.onInterceptTouchEvent(ev);
                }

                @Override
                protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                    super.onLayout(changed, left, top, right, bottom);
                    if (onLayoutListener != null) {
                        onLayoutListener.run();
                    }
                }
            };

            attribution = new TextView(context);
            attribution.setTextSize(10);
            attribution.setTextColor(0xFF000000);
            attribution.setLinkTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextLink));
            attribution.setBackground(new android.graphics.drawable.GradientDrawable() {{
                setColor(0xCCFFFFFF);
                setCornerRadius(AndroidUtilities.dp(100f));
            }});
            int pad = AndroidUtilities.dp(8);
            attribution.setPadding(pad, AndroidUtilities.dp(3), pad, AndroidUtilities.dp(3));
            attribution.setLinksClickable(true);
            attribution.setMovementMethod(LinkMovementMethod.getInstance());
            attribution.setText(Html.fromHtml(ATTRIBUTION_BRIGHT));

            viewWrapper = new FrameLayout(context) {
                @Override
                public void setTranslationY(float translationY) {
                    super.setTranslationY(translationY);
                    attribution.setTranslationY(-translationY);
                }
            };
            viewWrapper.addView(mapView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams attrParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            attrParams.gravity = Gravity.BOTTOM | Gravity.START;
            attrParams.leftMargin = AndroidUtilities.dp(6);
            attrParams.bottomMargin = AndroidUtilities.dp(6);
            viewWrapper.addView(attribution, attrParams);
        }

        @Override
        public View getView() {
            return viewWrapper;
        }

        @Override
        public void getMapAsync(Consumer<IMap> callback) {
            mapView.getMapAsync(mlMap -> {
                mlMap.setMaxZoomPreference(MAX_ZOOM);
                mlMap.setStyle(new Style.Builder().fromUri(BRIGHT_STYLE), style -> {
                    MlIMap map = new MlIMap(this, mlMap, style);
                    imap = map;
                    callback.accept(map);
                });
            });
        }

        @Override
        public void onResume() { mapView.onResume(); }

        @Override
        public void onPause() { mapView.onPause(); }

        @Override
        public void onCreate(Bundle savedInstance) {
            mapView.onCreate(savedInstance);
            mapView.onStart();
        }

        @Override
        public void onDestroy() {
            if (imap != null) {
                imap.onDestroy();
                imap = null;
            }
            mapView.onStop();
            mapView.onDestroy();
        }

        @Override
        public void onLowMemory() { mapView.onLowMemory(); }

        @Override
        public void setOnDispatchTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            dispatchInterceptor = touchInterceptor;
        }

        @Override
        public void setOnInterceptTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            interceptInterceptor = touchInterceptor;
        }

        @Override
        public void setOnLayoutListener(Runnable callback) {
            onLayoutListener = callback;
        }

        @Override
        public android.opengl.GLSurfaceView getGlSurfaceView() {
            return null;
        }
    }

    public final static class MlIMap implements IMap {
        private final MlIMapView viewWrapper;
        private final MapLibreMap mlMap;
        private Style currentStyle;
        private boolean destroyed;
        private OnMarkerClickListener markerClickListener;
        private Consumer<Location> locationConsumer;
        private boolean visualLocationEnabled;
        private boolean locationStarted;
        private MlLocationProvider locationProvider;
        private MlIMarker myLocationMarker;
        private MlIMarker myHeadingMarker;
        private MlICircle myAccuracyCircle;

        private final ArrayList<MlIMarker> markers = new ArrayList<>();
        private final ArrayList<MlICircle> circles = new ArrayList<>();

        private boolean refreshMarkersScheduled;
        private boolean refreshCirclesScheduled;

        private static final AtomicLong markerIdCounter = new AtomicLong(0);
        private static final AtomicLong circleIdCounter = new AtomicLong(0);

        MlIMap(MlIMapView viewWrapper, MapLibreMap mlMap, Style initialStyle) {
            this.viewWrapper = viewWrapper;
            this.mlMap = mlMap;
            bindToStyle(initialStyle);

            mlMap.addOnMapClickListener(latLng -> {
                PointF pt = mlMap.getProjection().toScreenLocation(latLng);
                List<Feature> features = mlMap.queryRenderedFeatures(new PointF(pt.x, pt.y), LAYER_MARKERS, LAYER_MARKERS_FLAT);
                String id = features.isEmpty() ? null : features.get(0).id();
                if (id == null) return false;
                MlIMarker marker = null;
                for (MlIMarker m : markers) {
                    if (m.featureId.equals(id)) { marker = m; break; }
                }
                if (marker == null) return false;
                return markerClickListener != null && markerClickListener.onClick(marker);
            });
        }

        void onDestroy() {
            destroyed = true;
            stopLocationIfIdle();
            currentStyle = null;
        }

        private void bindToStyle(Style style) {
            currentStyle = style;
            for (MlIMarker m : markers) {
                if (m.paddedBitmap != null) style.addImage(m.imageId, m.paddedBitmap);
            }

            try { style.addSource(new GeoJsonSource(SRC_CIRCLES)); } catch (Exception ignored) {}
            try { style.addSource(new GeoJsonSource(SRC_MARKERS)); } catch (Exception ignored) {}
            try { style.addSource(new GeoJsonSource(SRC_MARKERS_FLAT)); } catch (Exception ignored) {}

            try {
                style.addLayer(new FillLayer(LAYER_CIRCLES_FILL, SRC_CIRCLES).withProperties(
                    PropertyFactory.fillColor(Expression.toColor(Expression.get("fill")))
                ));
                style.addLayer(new LineLayer(LAYER_CIRCLES_STROKE, SRC_CIRCLES).withProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("stroke"))),
                    PropertyFactory.lineWidth(Expression.toNumber(Expression.get("strokeWidth")))
                ));
                style.addLayer(markerSymbolLayer(LAYER_MARKERS_FLAT, SRC_MARKERS_FLAT, Property.ICON_ROTATION_ALIGNMENT_MAP));
                style.addLayer(markerSymbolLayer(LAYER_MARKERS, SRC_MARKERS, Property.ICON_ROTATION_ALIGNMENT_VIEWPORT));
            } catch (Exception ignored) {}

            refreshMarkerSources();
            refreshCircleSource();
        }

        private SymbolLayer markerSymbolLayer(String id, String source, String alignment) {
            return new SymbolLayer(id, source).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get("rotation"))),
                PropertyFactory.iconRotationAlignment(alignment),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            );
        }

        private void applyToStyle(java.util.function.Consumer<Style> block) {
            Style style = currentStyle;
            if (style == null || destroyed || !style.isFullyLoaded()) return;
            try { block.accept(style); } catch (IllegalStateException ignored) {}
        }

        private void scheduleRefreshMarkers() {
            if (refreshMarkersScheduled || destroyed) return;
            refreshMarkersScheduled = true;
            viewWrapper.mapView.post(() -> {
                refreshMarkersScheduled = false;
                if (!destroyed) refreshMarkerSources();
            });
        }

        private void scheduleRefreshCircles() {
            if (refreshCirclesScheduled || destroyed) return;
            refreshCirclesScheduled = true;
            viewWrapper.mapView.post(() -> {
                refreshCirclesScheduled = false;
                if (!destroyed) refreshCircleSource();
            });
        }

        private void refreshMarkerSources() {
            applyToStyle(style -> {
                ArrayList<Feature> regular = new ArrayList<>();
                ArrayList<Feature> flat = new ArrayList<>();
                for (MlIMarker m : markers) {
                    (m.flat ? flat : regular).add(m.toFeature());
                }
                GeoJsonSource src = style.getSourceAs(SRC_MARKERS);
                GeoJsonSource srcFlat = style.getSourceAs(SRC_MARKERS_FLAT);
                if (src != null) src.setGeoJson(FeatureCollection.fromFeatures(regular));
                if (srcFlat != null) srcFlat.setGeoJson(FeatureCollection.fromFeatures(flat));
            });
        }

        private void refreshCircleSource() {
            applyToStyle(style -> {
                ArrayList<Feature> features = new ArrayList<>();
                for (MlICircle c : circles) features.add(c.toFeature());
                GeoJsonSource src = style.getSourceAs(SRC_CIRCLES);
                if (src != null) src.setGeoJson(FeatureCollection.fromFeatures(features));
            });
        }

        @Override
        public void setMapType(int mapType) {
            Style.Builder builder;
            String attr;
            if (mapType == MAP_TYPE_SATELLITE || mapType == MAP_TYPE_HYBRID) {
                builder = new Style.Builder().fromJson(SATELLITE_STYLE_JSON);
                attr = ATTRIBUTION_SATELLITE;
            } else {
                builder = new Style.Builder().fromUri(BRIGHT_STYLE);
                attr = ATTRIBUTION_BRIGHT;
            }
            viewWrapper.attribution.setText(Html.fromHtml(attr));
            currentStyle = null;
            mlMap.setStyle(builder, style -> bindToStyle(style));
        }

        @Override
        public void animateCamera(ICameraUpdate update) {
            mlMap.animateCamera(((MlCameraUpdate) update).cameraUpdate);
        }

        @Override
        public void animateCamera(ICameraUpdate update, ICancelableCallback callback) {
            mlMap.animateCamera(((MlCameraUpdate) update).cameraUpdate, callback == null ? null : new MapLibreMap.CancelableCallback() {
                @Override public void onCancel() { callback.onCancel(); }
                @Override public void onFinish() { callback.onFinish(); }
            });
        }

        @Override
        public void animateCamera(ICameraUpdate update, int duration, ICancelableCallback callback) {
            mlMap.animateCamera(((MlCameraUpdate) update).cameraUpdate, duration, callback == null ? null : new MapLibreMap.CancelableCallback() {
                @Override public void onCancel() { callback.onCancel(); }
                @Override public void onFinish() { callback.onFinish(); }
            });
        }

        @Override
        public void moveCamera(ICameraUpdate update) {
            mlMap.moveCamera(((MlCameraUpdate) update).cameraUpdate);
        }

        @Override
        public float getMaxZoomLevel() { return MAX_ZOOM; }

        @Override
        public float getMinZoomLevel() { return (float) mlMap.getMinZoomLevel(); }

        @Override
        public IUISettings getUiSettings() { return new MlUISettings(mlMap); }

        @Override
        public void setOnCameraIdleListener(Runnable callback) {
            if (callback == null) return;
            mlMap.addOnCameraIdleListener(callback::run);
        }

        @Override
        public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener listener) {
            mlMap.addOnCameraMoveStartedListener(reason -> {
                int outReason;
                switch (reason) {
                    case MapLibreMap.OnCameraMoveStartedListener.REASON_API_ANIMATION:
                        outReason = OnCameraMoveStartedListener.REASON_API_ANIMATION; break;
                    case MapLibreMap.OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION:
                        outReason = OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION; break;
                    default:
                        outReason = OnCameraMoveStartedListener.REASON_GESTURE; break;
                }
                listener.onCameraMoveStarted(outReason);
            });
        }

        @Override
        public IMapsProvider.CameraPosition getCameraPosition() {
            org.maplibre.android.camera.CameraPosition pos = mlMap.getCameraPosition();
            org.maplibre.android.geometry.LatLng target = pos.target != null ? pos.target : new org.maplibre.android.geometry.LatLng(0, 0);
            return new IMapsProvider.CameraPosition(fromMl(target), (float) pos.zoom);
        }

        @Override
        public void setOnMapLoadedCallback(Runnable callback) {
            if (callback == null) return;
            viewWrapper.mapView.addOnDidFinishLoadingMapListener(callback::run);
        }

        @Override
        public IProjection getProjection() {
            return new IProjection() {
                @Override
                public Point toScreenLocation(IMapsProvider.LatLng latLng) {
                    PointF pf = mlMap.getProjection().toScreenLocation(ml(latLng.latitude, latLng.longitude));
                    return new Point((int) pf.x, (int) pf.y);
                }
            };
        }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {
            mlMap.setPadding(left, top, right, bottom);
            TextView attr = viewWrapper.attribution;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) attr.getLayoutParams();
            lp.bottomMargin = bottom + AndroidUtilities.dp(16);
            attr.setLayoutParams(lp);
        }

        @Override
        public void setMapStyle(IMapStyleOptions style) {}

        @Override
        public IMarker addMarker(IMarkerOptions markerOptions) {
            MlMarkerOptionsImpl o = (MlMarkerOptionsImpl) markerOptions;
            Context ctx = viewWrapper.mapView.getContext();
            Bitmap bitmap = o.iconBitmap;
            if (bitmap == null && o.iconResId != 0) {
                bitmap = resToBitmap(ctx, o.iconResId);
            }
            MlIMarker marker = new MlIMarker();
            marker.position = o.position;
            marker.anchorU = o.anchorU;
            marker.anchorV = o.anchorV;
            marker.flat = o.isFlat;
            marker.updateBitmap(bitmap);
            markers.add(marker);
            scheduleRefreshMarkers();
            return marker;
        }

        @Override
        public ICircle addCircle(ICircleOptions circleOptions) {
            MlCircleOptionsImpl o = (MlCircleOptionsImpl) circleOptions;
            MlICircle circle = new MlICircle();
            circle.center = o.center;
            circle.radiusM = o.radiusMeters;
            circle.fill = o.fillColor;
            circle.stroke = o.strokeColor;
            circle.strokeWidthPx = o.strokeWidth;
            circles.add(circle);
            scheduleRefreshCircles();
            return circle;
        }

        @Override
        public void setOnMarkerClickListener(OnMarkerClickListener listener) {
            markerClickListener = listener;
        }

        @Override
        public void setOnCameraMoveListener(Runnable callback) {
            if (callback == null) return;
            mlMap.addOnCameraMoveListener(callback::run);
        }

        @Override
        public void setMyLocationEnabled(boolean enabled) {
            visualLocationEnabled = enabled;
            if (enabled) {
                ensureLocationStarted();
                return;
            }
            if (myLocationMarker != null) { myLocationMarker.remove(); myLocationMarker = null; }
            if (myHeadingMarker != null) { myHeadingMarker.remove(); myHeadingMarker = null; }
            if (myAccuracyCircle != null) { myAccuracyCircle.remove(); myAccuracyCircle = null; }
            stopLocationIfIdle();
        }

        @Override
        public void setOnMyLocationChangeListener(Consumer<Location> callback) {
            locationConsumer = callback;
            if (callback != null) ensureLocationStarted();
            else stopLocationIfIdle();
        }

        private void ensureLocationStarted() {
            if (locationStarted) return;
            locationStarted = true;
            Context ctx = viewWrapper.mapView.getContext();
            if (locationProvider == null) {
                locationProvider = createLocationProvider(ctx);
            }
            locationProvider.start(this::onLocation);
            locationProvider.requestLastLocation(loc -> { if (loc != null) onLocation(loc); });
        }

        private void stopLocationIfIdle() {
            if (visualLocationEnabled || locationConsumer != null || !locationStarted) return;
            if (locationProvider != null) {
                locationProvider.stop();
            }
            locationStarted = false;
        }

        private void onLocation(Location loc) {
            if (destroyed) return;
            if (locationConsumer != null) locationConsumer.accept(loc);
            if (visualLocationEnabled) updateMyLocationVisuals(loc);
        }

        private void updateMyLocationVisuals(Location loc) {
            Context ctx = viewWrapper.mapView.getContext();
            IMapsProvider.LatLng pos = new IMapsProvider.LatLng(loc.getLatitude(), loc.getLongitude());
            double accuracy = Math.max(loc.getAccuracy(), 1.0);

            if (myLocationMarker == null) {
                MlMarkerOptionsImpl opts = new MlMarkerOptionsImpl();
                opts.position = ml(pos.latitude, pos.longitude);
                opts.anchorU = 0.5f; opts.anchorV = 0.5f;
                opts.iconBitmap = blueDotBitmap();
                myLocationMarker = (MlIMarker) addMarker(opts);
            } else {
                myLocationMarker.setPosition(pos);
            }

            if (loc.hasBearing()) {
                if (myHeadingMarker == null) {
                    MlMarkerOptionsImpl opts = new MlMarkerOptionsImpl();
                    opts.position = ml(pos.latitude, pos.longitude);
                    opts.anchorU = 0.5f; opts.anchorV = 1f;
                    opts.isFlat = true;
                    opts.iconBitmap = headingArrowBitmap(ctx);
                    myHeadingMarker = (MlIMarker) addMarker(opts);
                    myHeadingMarker.setRotation((int) loc.getBearing());
                } else {
                    myHeadingMarker.setPosition(pos);
                    myHeadingMarker.setRotation((int) loc.getBearing());
                }
            } else {
                if (myHeadingMarker != null) { myHeadingMarker.remove(); myHeadingMarker = null; }
            }

            if (myAccuracyCircle == null) {
                MlCircleOptionsImpl opts = new MlCircleOptionsImpl();
                opts.center = ml(pos.latitude, pos.longitude);
                opts.radiusMeters = accuracy;
                opts.fillColor = 0x224285F4;
                opts.strokeColor = 0x554285F4;
                opts.strokeWidth = AndroidUtilities.dp(1);
                myAccuracyCircle = (MlICircle) addCircle(opts);
            } else {
                myAccuracyCircle.setCenter(pos);
                myAccuracyCircle.setRadius(accuracy);
            }
        }

        public class MlIMarker implements IMarker {
            final String featureId = "m" + markerIdCounter.incrementAndGet();
            final String imageId = "img_" + featureId;
            org.maplibre.android.geometry.LatLng position = new org.maplibre.android.geometry.LatLng(0, 0);
            float rotation;
            float anchorU = 0.5f;
            float anchorV = 1f;
            boolean flat;
            Bitmap paddedBitmap;
            private Object tag;

            void updateBitmap(Bitmap bmp) {
                if (paddedBitmap != null && currentStyle != null) {
                    try { currentStyle.removeImage(imageId); } catch (Exception ignored) {}
                }
                paddedBitmap = bmp != null ? padForCenterAnchor(bmp, anchorU, anchorV) : null;
                if (paddedBitmap != null && currentStyle != null) {
                    try { currentStyle.addImage(imageId, paddedBitmap); } catch (Exception ignored) {}
                }
            }

            Feature toFeature() {
                org.maplibre.geojson.Point pt = org.maplibre.geojson.Point.fromLngLat(position.getLongitude(), position.getLatitude());
                Feature f = Feature.fromGeometry(pt, null, featureId);
                f.addStringProperty("icon", imageId);
                f.addNumberProperty("rotation", rotation);
                return f;
            }

            @Override public Object getTag() { return tag; }
            @Override public void setTag(Object tag) { this.tag = tag; }

            @Override
            public IMapsProvider.LatLng getPosition() { return fromMl(position); }

            @Override
            public void setPosition(IMapsProvider.LatLng latLng) {
                position = ml(latLng.latitude, latLng.longitude);
                scheduleRefreshMarkers();
            }

            @Override
            public void setRotation(int rot) {
                rotation = rot;
                scheduleRefreshMarkers();
            }

            @Override
            public void setIcon(android.content.res.Resources resources, Bitmap bitmap) {
                updateBitmap(bitmap);
                scheduleRefreshMarkers();
            }

            @Override
            public void setIcon(android.content.res.Resources resources, int resId) {
                Context ctx = viewWrapper.mapView.getContext();
                Bitmap bmp = resToBitmap(ctx, resId);
                if (bmp != null) { updateBitmap(bmp); scheduleRefreshMarkers(); }
            }

            @Override
            public void remove() {
                if (currentStyle != null) {
                    try { currentStyle.removeImage(imageId); } catch (Exception ignored) {}
                }
                markers.remove(this);
                scheduleRefreshMarkers();
            }
        }

        public class MlICircle implements ICircle {
            final String featureId = "c" + circleIdCounter.incrementAndGet();
            org.maplibre.android.geometry.LatLng center = new org.maplibre.android.geometry.LatLng(0, 0);
            double radiusM;
            int fill = Color.TRANSPARENT;
            int stroke = Color.BLACK;
            float strokeWidthPx;

            Feature toFeature() {
                Polygon poly = buildCirclePolygon(center, radiusM, 64);
                Feature f = Feature.fromGeometry(poly, null, featureId);
                f.addStringProperty("fill", colorToCss(fill));
                f.addStringProperty("stroke", colorToCss(stroke));
                f.addNumberProperty("strokeWidth", strokeWidthPx);
                return f;
            }

            @Override public void setStrokeColor(int color) { stroke = color; scheduleRefreshCircles(); }
            @Override public void setFillColor(int color) { fill = color; scheduleRefreshCircles(); }
            @Override public void setRadius(double radius) { radiusM = radius; scheduleRefreshCircles(); }
            @Override public double getRadius() { return radiusM; }

            @Override
            public void setCenter(IMapsProvider.LatLng latLng) {
                center = ml(latLng.latitude, latLng.longitude);
                scheduleRefreshCircles();
            }

            @Override
            public void remove() {
                circles.remove(this);
                scheduleRefreshCircles();
            }
        }
    }

    public static class MlMarkerOptionsImpl implements IMarkerOptions {
        org.maplibre.android.geometry.LatLng position = new org.maplibre.android.geometry.LatLng(0, 0);
        Bitmap iconBitmap;
        int iconResId;
        float anchorU = 0.5f, anchorV = 1f;
        boolean isFlat;

        @Override public IMarkerOptions position(IMapsProvider.LatLng latLng) { position = ml(latLng.latitude, latLng.longitude); return this; }
        @Override public IMarkerOptions icon(android.content.res.Resources resources, Bitmap bitmap) { iconBitmap = bitmap; iconResId = 0; return this; }
        @Override public IMarkerOptions icon(android.content.res.Resources resources, int resId) { iconResId = resId; iconBitmap = null; return this; }
        @Override public IMarkerOptions anchor(float lat, float lng) { anchorU = lat; anchorV = lng; return this; }
        @Override public IMarkerOptions title(String title) { return this; }
        @Override public IMarkerOptions snippet(String snippet) { return this; }
        @Override public IMarkerOptions flat(boolean flat) { isFlat = flat; return this; }
    }

    public static class MlCircleOptionsImpl implements ICircleOptions {
        org.maplibre.android.geometry.LatLng center = new org.maplibre.android.geometry.LatLng(0, 0);
        double radiusMeters;
        int strokeColor = Color.BLACK;
        int fillColor = Color.TRANSPARENT;
        float strokeWidth;

        @Override public ICircleOptions center(IMapsProvider.LatLng latLng) { center = ml(latLng.latitude, latLng.longitude); return this; }
        @Override public ICircleOptions radius(double radius) { radiusMeters = radius; return this; }
        @Override public ICircleOptions strokeColor(int color) { strokeColor = color; return this; }
        @Override public ICircleOptions fillColor(int color) { fillColor = color; return this; }
        @Override public ICircleOptions strokePattern(List<PatternItem> items) { return this; }
        @Override public ICircleOptions strokeWidth(int width) { strokeWidth = width; return this; }
    }

    public final static class MlBoundsBuilderImpl implements ILatLngBoundsBuilder {
        private final ArrayList<org.maplibre.android.geometry.LatLng> points = new ArrayList<>();

        @Override
        public ILatLngBoundsBuilder include(IMapsProvider.LatLng latLng) {
            points.add(ml(latLng.latitude, latLng.longitude));
            return this;
        }

        @Override
        public ILatLngBounds build() {
            LatLngBounds.Builder builder = new LatLngBounds.Builder();
            for (org.maplibre.android.geometry.LatLng pt : points) {
                builder.include(pt);
            }
            return new MlLatLngBoundsImpl(builder.build());
        }
    }

    public final static class MlLatLngBoundsImpl implements ILatLngBounds {
        final LatLngBounds bounds;

        MlLatLngBoundsImpl(LatLngBounds bounds) { this.bounds = bounds; }

        @Override
        public IMapsProvider.LatLng getCenter() { return fromMl(bounds.getCenter()); }
    }

    public final static class MlUISettings implements IUISettings {
        private final MapLibreMap map;

        MlUISettings(MapLibreMap map) { this.map = map; }

        @Override public void setZoomControlsEnabled(boolean enabled) {}
        @Override public void setMyLocationButtonEnabled(boolean enabled) {}
        @Override public void setCompassEnabled(boolean enabled) { map.getUiSettings().setCompassEnabled(enabled); }
    }

    interface MlLocationProvider {
        void start(java.util.function.Consumer<Location> callback);
        void stop();
        void requestLastLocation(java.util.function.Consumer<Location> callback);
    }

    static MlLocationProvider createLocationProvider(Context ctx) {
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS) {
                return new FusedProvider(ctx);
            }
        } catch (Throwable ignored) {}
        return new NativeProvider(ctx);
    }

    static boolean hasLocationPermission(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static final class FusedProvider implements MlLocationProvider {
        private final Context ctx;
        private final FusedLocationProviderClient client;
        private LocationCallback callback;

        FusedProvider(Context ctx) {
            this.ctx = ctx;
            this.client = LocationServices.getFusedLocationProviderClient(ctx);
        }

        @Override
        public void start(java.util.function.Consumer<Location> callback) {
            if (this.callback != null || !hasLocationPermission(ctx)) return;
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L).build();
            LocationCallback cb = new LocationCallback() {
                @Override
                public void onLocationResult(@NonNull LocationResult result) {
                    Location loc = result.getLastLocation();
                    if (loc != null) callback.accept(loc);
                }
            };
            this.callback = cb;
            try {
                client.requestLocationUpdates(request, cb, Looper.getMainLooper());
            } catch (SecurityException ignored) {}
        }

        @Override
        public void stop() {
            if (callback != null) {
                try { client.removeLocationUpdates(callback); } catch (Exception ignored) {}
                callback = null;
            }
        }

        @Override
        public void requestLastLocation(java.util.function.Consumer<Location> callback) {
            if (!hasLocationPermission(ctx)) { callback.accept(null); return; }
            try {
                client.getLastLocation()
                    .addOnSuccessListener(callback::accept)
                    .addOnFailureListener(e -> callback.accept(null));
            } catch (SecurityException ignored) { callback.accept(null); }
        }
    }

    static final class NativeProvider implements MlLocationProvider {
        private final Context ctx;
        private final LocationManager manager;
        private LocationListener listener;
        private Location bestSoFar;
        private static final long FRESHNESS_NS = 30_000_000_000L;
        private static final float SIGNIFICANT_DROP_M = 200f;

        NativeProvider(Context ctx) {
            this.ctx = ctx;
            this.manager = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        }

        @Override
        public void start(java.util.function.Consumer<Location> callback) {
            if (listener != null || !hasLocationPermission(ctx)) return;
            bestSoFar = null;
            LocationListener l = loc -> {
                if (isBetterLocation(loc, bestSoFar)) {
                    bestSoFar = loc;
                    callback.accept(loc);
                }
            };
            listener = l;
            try {
                for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    if (manager.isProviderEnabled(provider)) {
                        manager.requestLocationUpdates(provider, 1000, 0f, l, Looper.getMainLooper());
                    }
                }
            } catch (SecurityException | IllegalArgumentException ignored) {}
        }

        @Override
        public void stop() {
            if (listener != null) {
                try { manager.removeUpdates(listener); } catch (Exception ignored) {}
                listener = null;
                bestSoFar = null;
            }
        }

        @Override
        public void requestLastLocation(java.util.function.Consumer<Location> callback) {
            if (!hasLocationPermission(ctx)) { callback.accept(null); return; }
            try {
                Location gps = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
                Location net = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) : null;
                Location last = null;
                if (gps != null && net != null) last = gps.getTime() >= net.getTime() ? gps : net;
                else if (gps != null) last = gps;
                else last = net;
                callback.accept(last);
            } catch (SecurityException ignored) { callback.accept(null); }
        }

        static boolean isBetterLocation(Location candidate, Location current) {
            if (current == null) return true;
            long timeDelta = candidate.getElapsedRealtimeNanos() - current.getElapsedRealtimeNanos();
            if (timeDelta > FRESHNESS_NS) return true;
            if (timeDelta < -FRESHNESS_NS) return false;
            float accuracyDelta = candidate.getAccuracy() - current.getAccuracy();
            boolean isNewer = timeDelta > 0;
            boolean sameProvider = candidate.getProvider() != null && candidate.getProvider().equals(current.getProvider());
            if (accuracyDelta < 0) return true;
            if (isNewer && accuracyDelta <= 0) return true;
            if (isNewer && accuracyDelta < SIGNIFICANT_DROP_M && sameProvider) return true;
            return false;
        }
    }
}
