package com.exteragram.messenger.pillstack.ui.pills.crypto;

import android.content.Context;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.ColoredBackground;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

public class TonPill extends RatePill {

    private static final RateCache CACHE = new RateCache();

    public TonPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, CACHE, "TON", 3, R.drawable.ton_16,
                new ColoredBackground(0xFF28A2ED, 0xFF1873E1));
    }

    @Override
    public int getPillId() {
        return PillStackConfig.PillType.TON.id;
    }

    @Override
    protected String getTargetSelection() {
        return PillStackConfig.tonTargetCurrency;
    }

    @Override
    protected void setTargetSelection(String value) {
        PillStackConfig.tonTargetCurrency = value;
        PillStackConfig.editor.putString("tonTargetCurrency", value).apply();
    }
}
