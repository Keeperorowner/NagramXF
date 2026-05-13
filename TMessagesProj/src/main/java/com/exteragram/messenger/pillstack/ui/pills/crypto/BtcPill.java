package com.exteragram.messenger.pillstack.ui.pills.crypto;

import android.content.Context;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.ColoredBackground;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

public class BtcPill extends RatePill {

    private static final RateCache CACHE = new RateCache();

    public BtcPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, CACHE, "BTC", 2, R.drawable.pillstack_btc,
                new ColoredBackground(0xFFEFA812, 0xFFE7A012));
    }

    @Override
    public int getPillId() {
        return PillStackConfig.PillType.BTC.id;
    }

    @Override
    protected String getTargetSelection() {
        return PillStackConfig.btcTargetCurrency;
    }

    @Override
    protected void setTargetSelection(String value) {
        PillStackConfig.btcTargetCurrency = value;
        PillStackConfig.editor.putString("btcTargetCurrency", value).apply();
    }
}
