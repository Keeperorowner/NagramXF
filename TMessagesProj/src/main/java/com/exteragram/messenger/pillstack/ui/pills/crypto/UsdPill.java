package com.exteragram.messenger.pillstack.ui.pills.crypto;

import android.content.Context;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.ColoredBackground;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.PillStackCurrencies;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

public class UsdPill extends RatePill {

    private static final RateCache CACHE = new RateCache();

    public UsdPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, CACHE, "USD", 2, R.drawable.pillstack_usd,
                new ColoredBackground(0xFF2DA35D, 0xFF287B49));
    }

    @Override
    public int getPillId() {
        return PillStackConfig.PillType.USD.id;
    }

    @Override
    protected String getTargetSelection() {
        if ("USD".equalsIgnoreCase(PillStackConfig.usdTargetCurrency)) {
            return "AUTO";
        }
        return PillStackConfig.usdTargetCurrency;
    }

    @Override
    protected void setTargetSelection(String value) {
        PillStackConfig.usdTargetCurrency = value;
        PillStackConfig.editor.putString("usdTargetCurrency", value).apply();
    }

    @Override
    protected String[] getTargetCurrencies() {
        return PillStackCurrencies.getTargetCurrencies("USD");
    }
}
