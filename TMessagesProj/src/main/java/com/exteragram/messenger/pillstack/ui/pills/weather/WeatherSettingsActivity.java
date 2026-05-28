package com.exteragram.messenger.pillstack.ui.pills.weather;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.google.gson.Gson;

import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextRadioCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LocationActivity;
import org.telegram.ui.Stories.recorder.Weather;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class WeatherSettingsActivity extends BaseNekoSettingsActivity {

    private static final Gson GSON = new Gson();

    private int topViewRow;
    private int pillStackRow;

    private int locationHeaderRow;
    private int useCurrentRow;
    private int pickLocationRow;
    private int locationNoticeRow;
    private int locationActionRow;
    private int locationActionNoticeRow;

    private TLRPC.GeoPoint currentGeo;

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.WeatherPill);
    }

    @Override
    public boolean onFragmentCreate() {
        if (PillStackConfig.customWeatherLocation != null) {
            try {
                currentGeo = GSON.fromJson(PillStackConfig.customWeatherLocation, TLRPC.TL_geoPoint.class);
            } catch (Exception ignored) {
            }
        }
        return super.onFragmentCreate();
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected void updateRows() {
        super.updateRows();
        topViewRow = -1;
        pillStackRow = addRow();

        locationHeaderRow = -1;
        useCurrentRow = -1;
        pickLocationRow = -1;
        locationNoticeRow = -1;
        locationActionRow = -1;
        locationActionNoticeRow = -1;

        if (PillStackConfig.activePills.contains(PillStackConfig.PillType.WEATHER.id)) {
            locationHeaderRow = addRow();
            useCurrentRow = addRow();
            pickLocationRow = addRow();
            locationNoticeRow = addRow();

            if (PillStackConfig.useCurrentLocation) {
                if (!Weather.isLocationPermissionGranted()) {
                    locationActionRow = addRow("grantPermission");
                    locationActionNoticeRow = addRow();
                } else if (!Weather.isLocationEnabled()) {
                    locationActionRow = addRow("enableServices");
                    locationActionNoticeRow = addRow();
                }
            }
        }

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == pillStackRow) {
            presentFragment(new PillStackPreferencesActivity());
            return;
        }
        if (position == useCurrentRow) {
            if (PillStackConfig.useCurrentLocation && Weather.isLocationPermissionGranted()) {
                return;
            }
            Weather.getUserLocation(true, (Location loc) -> {
                if (loc != null) {
                    setUseCurrentLocation(true);
                }
            });
            return;
        }
        if (position == pickLocationRow) {
            if (PillStackConfig.useCurrentLocation) {
                if (currentGeo == null) {
                    TLRPC.TL_geoPoint point = new TLRPC.TL_geoPoint();
                    point.lat = 55.7558;
                    point._long = 37.6173;
                    currentGeo = point;
                    SharedPreferences.Editor editor = PillStackConfig.editor;
                    PillStackConfig.customWeatherLocation = GSON.toJson(point);
                    editor.putString("customWeatherLocation", PillStackConfig.customWeatherLocation).apply();
                }
                setUseCurrentLocation(false);
            }
            openMapPicker();
            return;
        }
        if (position == locationActionRow) {
            Weather.getUserLocation(true, (Location loc) -> {
                if (loc != null) {
                    updateRows();
                }
            });
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void setUseCurrentLocation(boolean useCurrent) {
        PillStackConfig.useCurrentLocation = useCurrent;
        PillStackConfig.editor.putBoolean("useCurrentLocation", useCurrent).apply();
        PillStackConfig.notifySettingsChanged(PillStackConfig.PillType.WEATHER.id);
        updateRows();
    }

    private void openMapPicker() {
        final LocationActivity activity = new LocationActivity(8);
        if (currentGeo != null) {
            TLRPC.TL_channelLocation channelLoc = new TLRPC.TL_channelLocation();
            channelLoc.geo_point = currentGeo;
            channelLoc.address = PillStackConfig.customWeatherAddress != null ? PillStackConfig.customWeatherAddress : "";
            activity.setInitialLocation(channelLoc);
        }
        activity.setDelegate((media, live, notify, scheduleDate, payStars) -> {
            currentGeo = media.geo;
            String address = activity.getAddressName();
            if (address == null) address = "";
            PillStackConfig.customWeatherLocation = GSON.toJson(currentGeo);
            PillStackConfig.customWeatherAddress = address;
            PillStackConfig.editor
                    .putString("customWeatherLocation", PillStackConfig.customWeatherLocation)
                    .putString("customWeatherAddress", PillStackConfig.customWeatherAddress)
                    .apply();
            setUseCurrentLocation(false);
        });
        presentFragment(activity);
    }

    private class ListAdapter extends BaseListAdapter {
        ListAdapter(Context context) {
            super(context);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == locationHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == pillStackRow || position == locationActionRow) {
                return TYPE_TEXT;
            }
            if (position == useCurrentRow || position == pickLocationRow) {
                return TYPE_RADIO;
            }
            if (position == locationNoticeRow || position == locationActionNoticeRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_SHADOW;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_TEXT || type == TYPE_RADIO;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            int viewType = holder.getItemViewType();
            switch (viewType) {
                case TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText(getString(R.string.WeatherLocation));
                    break;
                }
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == pillStackRow) {
                        cell.setTextAndIcon(getString(R.string.PillStackPills), R.drawable.msg_settings_old, false);
                    } else if (position == locationActionRow) {
                        if (!Weather.isLocationPermissionGranted()) {
                            cell.setTextAndIcon(getString(R.string.WeatherLocationPermissionGrant),
                                    R.drawable.msg_report, false);
                            cell.setColors(Theme.key_text_RedBold, Theme.key_text_RedRegular);
                        } else {
                            cell.setTextAndIcon(getString(R.string.WeatherLocationServicesEnable),
                                    R.drawable.filled_location, false);
                            cell.setColors(Theme.key_windowBackgroundWhiteBlueText4,
                                    Theme.key_windowBackgroundWhiteBlueText4);
                        }
                    }
                    break;
                }
                case TYPE_RADIO: {
                    TextRadioCell cell = (TextRadioCell) holder.itemView;
                    if (position == useCurrentRow) {
                        cell.setTextAndCheck(getString(R.string.CurrentLocation),
                                PillStackConfig.useCurrentLocation, true);
                    } else if (position == pickLocationRow) {
                        String address = PillStackConfig.customWeatherAddress;
                        if (!PillStackConfig.useCurrentLocation && !TextUtils.isEmpty(address)) {
                            cell.setTextAndValueAndCheck(getString(R.string.SelectLocation),
                                    address, !PillStackConfig.useCurrentLocation, false, false);
                        } else {
                            cell.setTextAndCheck(getString(R.string.SelectLocation),
                                    !PillStackConfig.useCurrentLocation, false);
                        }
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == locationNoticeRow) {
                        cell.setText(getString(R.string.WeatherSettingsInfo));
                    } else if (position == locationActionNoticeRow) {
                        if (!Weather.isLocationPermissionGranted()) {
                            cell.setText(getString(R.string.WeatherLocationPermissionNo));
                        } else {
                            cell.setText(getString(R.string.GpsDisabledAlertText));
                        }
                    }
                    cell.setFixedSize(0);
                    cell.setBackground(null);
                    break;
                }
            }
        }
    }
}
