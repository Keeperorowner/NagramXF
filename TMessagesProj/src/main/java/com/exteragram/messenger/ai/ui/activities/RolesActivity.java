package com.exteragram.messenger.ai.ui.activities;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Role;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

import androidx.recyclerview.widget.RecyclerView;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class RolesActivity extends BaseNekoSettingsActivity {

    private static final int MENU_ADD = 1;
    private static final int VIEW_TYPE_RADIO = 100;

    private int suggestedHeaderRow;
    private int suggestedStartRow;
    private int suggestedEndRow;
    private int customHeaderRow;
    private int customStartRow;
    private int customEndRow;
    private int rowCount;

    private List<Role> suggestedRoles = new ArrayList<>();
    private List<Role> customRoles = new ArrayList<>();

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_ADD, R.drawable.msg_add);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_ADD) {
                    presentFragment(new EditRoleActivity());
                }
            }
        });
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadRoles();
        updateRows();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private void reloadRoles() {
        AiController.getInstance().loadRoles();
        suggestedRoles = AiController.getInstance().getSuggestedRoles();
        customRoles = new ArrayList<>(AiController.getInstance().getRoles());
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position >= suggestedStartRow && position < suggestedEndRow) {
            int idx = position - suggestedStartRow;
            if (idx >= 0 && idx < suggestedRoles.size()) {
                Role role = suggestedRoles.get(idx);
                if (!role.isSelected()) {
                    AiConfig.setSelectedRole(role);
                    updateRadioCells(position);
                }
            }
        } else if (customStartRow >= 0 && position >= customStartRow && position < customEndRow) {
            int idx = position - customStartRow;
            if (idx >= 0 && idx < customRoles.size()) {
                Role role = customRoles.get(idx);
                if (!role.isSelected()) {
                    AiConfig.setSelectedRole(role);
                    updateRadioCells(position);
                }
            }
        }
    }

    private void updateRadioCells(int selectedPosition) {
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            int pos = listView.getChildAdapterPosition(child);
            if (child instanceof RadioColorCell) {
                ((RadioColorCell) child).setChecked(pos == selectedPosition, true);
            }
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (customStartRow >= 0 && position >= customStartRow && position < customEndRow) {
            int idx = position - customStartRow;
            if (idx >= 0 && idx < customRoles.size()) {
                Role role = customRoles.get(idx);
                ItemOptions.makeOptions(this, view)
                        .add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> presentFragment(new EditRoleActivity(role)))
                        .add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
                            AndroidUtilities.addToClipboard(role.getName() + "\n" + role.getPrompt());
                            BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                        })
                        .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> {
                            boolean wasSelected = role.isSelected();
                            if (AiController.getInstance().removeRole(role)) {
                                if (wasSelected) {
                                    AiConfig.setSelectedRole(AiController.getInstance().getSuggestedRoles().get(0));
                                }
                                reloadRoles();
                                updateRows();
                                if (listAdapter != null) listAdapter.notifyDataSetChanged();
                            }
                        })
                        .setScrimViewBackground(listView.getClipBackground(view))
                        .setGravity(LocaleController.isRTL ? 3 : 5)
                        .show();
                return true;
            }
        }
        return false;
    }

    @Override
    protected BaseNekoSettingsActivity.BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.AIChatRoles);
    }

    @Override
    protected void updateRows() {
        reloadRoles();
        rowCount = 0;
        suggestedHeaderRow = rowCount++;
        suggestedStartRow = rowCount;
        rowCount += suggestedRoles.size();
        suggestedEndRow = rowCount;
        if (!customRoles.isEmpty()) {
            customHeaderRow = rowCount++;
            customStartRow = rowCount;
            rowCount += customRoles.size();
            customEndRow = rowCount;
        } else {
            customHeaderRow = -1;
            customStartRow = -1;
            customEndRow = -1;
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_TYPE_RADIO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_RADIO) {
                RadioColorCell cell = new RadioColorCell(mContext);
                cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
                cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(cell);
            }
            return super.onCreateViewHolder(parent, viewType);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == VIEW_TYPE_RADIO) {
                RadioColorCell cell = (RadioColorCell) holder.itemView;
                if (position >= suggestedStartRow && position < suggestedEndRow) {
                    int idx = position - suggestedStartRow;
                    Role role = suggestedRoles.get(idx);
                    cell.setTextAndText2AndValue(role.getName(), role.getPrompt(), role.isSelected());
                } else if (customStartRow >= 0 && position >= customStartRow && position < customEndRow) {
                    int idx = position - customStartRow;
                    Role role = customRoles.get(idx);
                    cell.setTextAndText2AndValue(role.getName(), role.getPrompt(), role.isSelected());
                }
            } else if (type == TYPE_HEADER) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                if (position == suggestedHeaderRow) {
                    cell.setText(LocaleController.getString(R.string.AIChatSuggestedRoles));
                } else if (position == customHeaderRow) {
                    cell.setText(LocaleController.getString(R.string.AIChatCustomRoles));
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == suggestedHeaderRow || position == customHeaderRow) return TYPE_HEADER;
            return VIEW_TYPE_RADIO;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }
    }
}
