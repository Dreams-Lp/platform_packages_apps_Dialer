
package com.android.dialer.dsds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.graphics.Color;
import android.telephony.SubInfoRecord;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.dialer.R;
import com.android.dialer.dsds.SimSubInfoWrapper.SimListener;
import com.android.internal.widget.SubscriptionView;

/**
 * Used for create a SIM Picker Dialog.
 */
public class SimPickerDialog {

    /**
     * Create a SIM Picker Dialog.
     * 
     * @param context
     * @param titleId
     * @return
     */
    public static AlertDialog create(Context context,
            DialogInterface.OnClickListener listener) {
        return create(context, R.string.sim_select_title, listener);
    }

    /**
     * Create a SIM Picker Dialog with assign title.
     * 
     * @param context
     * @param titleId
     * @param listener
     * @return
     */
    public static AlertDialog create(Context context, int titleId,
            DialogInterface.OnClickListener listener) {
        final SimPickerAdapter adapter = new SimPickerAdapter(context);
        SimSubInfoWrapper.getInstance().addSimListener(adapter);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(titleId)
                .setAdapter(adapter, listener);

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                SimSubInfoWrapper.getInstance().removeSimListener(adapter);
            }
        });
        return dialog;
    }

    /**
     * Get the selected SubInfoRecord from SIM Picker Dialog.
     *
     * @param dialog
     * @param which
     * @return
     */
    public static SubInfoRecord getSelectedItem(DialogInterface dialog, int which) {
        final AlertDialog alert = (AlertDialog) dialog;
        final SimPickerAdapter simPickerAdapter = (SimPickerAdapter) alert.getListView()
                .getAdapter();

        SubInfoRecord simInfo = simPickerAdapter.getItem(which);
        return simInfo;
    }

    private static class SimPickerAdapter extends BaseAdapter implements SimListener{
        private Context mContext;
        private List<SimPickerItem> mData;

        public SimPickerAdapter(Context context) {
            mContext = context;
            updateData();
        }

        /**
         * Update SubInfoRecord. Once the method is called, do not forget to
         * call {@link #notifyDataSetChanged()} for updating view.
         */
        private void updateData() {
            if (mData == null) {
                mData = new ArrayList<SimPickerItem>();
            } else {
                mData.clear();
            }

            SimSubInfoWrapper subscriptionWrapper = SimSubInfoWrapper.getInstance();
            List<SubInfoRecord> subInfos = subscriptionWrapper.getActiveSubInfos();
            for (SubInfoRecord subInfo : subInfos) {
                mData.add(new SimPickerItem(subInfo));
            }

            final int[] simIds = subscriptionWrapper.getSimIds();
            for (int simId : simIds) {
                if (!subscriptionWrapper.hasIccCard(simId)) {
                    mData.add(new SimPickerItem(simId));
                }
            }
        }

        @Override
        public int getCount() {
            return mData.size();
        }

        @Override
        public SubInfoRecord getItem(int position) {
            return mData.get(position).mSubInfo;
        }

        @Override
        public long getItemId(int position) {
            return mData.get(position).mSimId;
        }

        @Override
        public boolean isEnabled(int position) {
            return mData.get(position).mSubInfo != null;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SubscriptionView simItemView;
            if (mData.get(position).mSubInfo == null) {
                simItemView = (SubscriptionView) new EmptySimView(mContext, mData.get(position).mSimId);
            } else {
                simItemView = new SubscriptionView(mContext);
                simItemView.setThemeType(SubscriptionView.LIGHT_THEME);
                simItemView.setSubInfo(mData.get(position).mSubInfo);
            }

            return simItemView;
        }

        private class SimPickerItem {
            final int mSimId;
            final SubInfoRecord mSubInfo;

            SimPickerItem(int simId) {
                mSimId = simId;
                mSubInfo = null;
            }

            SimPickerItem(SubInfoRecord subInfo) {
                mSimId = subInfo.mSimId;
                mSubInfo = subInfo;
            }

        }

        @Override
        public void onStateChange() {
            updateData();
            notifyDataSetChanged();
        }
    }

    private static class EmptySimView extends SubscriptionView {
        public EmptySimView(Context context, int simId) {
            this(context, null, simId);
        }

        public EmptySimView(Context context, AttributeSet attrs, int simId) {
            super(context, null);
            initSimViews(context, simId);
        }

        private void initSimViews(Context context, int simId) {
            final String simEmptyText = context.getResources().getString(R.string.sim_empty_text, (simId + 1));
            setSubName(simEmptyText);
            setSubNum(null);
            ((TextView) findViewById(com.android.internal.R.id.sub_name)).setTextColor(Color.GRAY);
            findViewById(com.android.internal.R.id.sub_color).setVisibility(View.GONE);
        }
    }
}
