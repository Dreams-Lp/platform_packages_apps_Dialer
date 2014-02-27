
package com.android.dialer.dsds;

import java.util.ArrayList;
import java.util.List;

import com.android.internal.telephony.TelephonyIntents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.telephony.SubscriptionController;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionController.SubInfoRecord;
import android.util.Log;

/**
 * A Single Instance Class for catching Subscriptions. Generally,
 * {@link SimSubInfoWrapper#init(Context)} must be called before using it.
 */
public class SimSubInfoWrapper {
    private static final String TAG = "SimSubscription";

    private static final String ICC_ADN_URI = "content://icc/adn";

    private static SimSubInfoWrapper sInstance = new SimSubInfoWrapper();

    private Context mContext;
    private List<SubInfoRecord> mAllSubInfos = null;
    private List<SubInfoRecord> mActiveSubInfos = null;

    private int[] mSimIds = null;

    /**
     * Global listener for notify SimInfos status changed.
     */
    public interface SimListener {
        void onStateChange();
    }

    private List<SimListener> mListeners = null;
    private SimInfoReceiver mReceiver = null;

    private SimSubInfoWrapper() {
    }

    /**
     * For get the single instance SubscriptionWrapper.
     * 
     * @param context
     * @param isInsertSim
     * @return SIMInfoWrapper instance
     */
    public static SimSubInfoWrapper getInstance() {
        return sInstance;
    }

    /**
     *
     * @param context Application Context is prefer.
     */
    public void init(Context context) {
        if (mContext == null) {
            mContext = context.getApplicationContext();

            final int simCount = TelephonyManager.getDefault().getSimCount();
            mSimIds = new int[simCount];
            for (int i = 0; i < simCount; i++) {
                mSimIds[i] = i;
            }

            updateSubInfos();

            mListeners = new ArrayList<SimListener>();
            mReceiver = new SimInfoReceiver();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            mContext.registerReceiver(mReceiver, intentFilter);
        }
    }

    /**
     * Get the Count of SIM slots.
     * @return
     */
    public int getSimCount() {
        return mSimIds.length;
    }

    /**
     * Get an array of SIM IDs.
     * @return
     */
    public int[] getSimIds() {
        return mSimIds;
    }

    /**
     * Get cached SIM info list
     *
     * @return
     */
    public List<SubInfoRecord> getAllSubInfos() {
        return mAllSubInfos;
    }

    /**
     * Get SubInfoRecord according the subId. If no SIM inserted, it will return
     * null.
     * 
     * @param subId
     * @return
     */
    public SubInfoRecord getSubInfo(long subId) {
        if (mAllSubInfos != null && !mAllSubInfos.isEmpty()) {
            for (SubInfoRecord simInfo : mAllSubInfos) {
                if (subId == simInfo.mSubId) {
                    return simInfo;
                }
            }
        }
        return null;
    }

    /**
     * Get cached Subscription info list
     * 
     * @return
     */
    public List<SubInfoRecord> getActiveSubInfos() {
        return mActiveSubInfos;
    }

    /**
     * Get cached active Subscription info count.
     * 
     * @return
     */
    public int getActiveSubInfoCount() {
        return mActiveSubInfos == null ? 0 : mActiveSubInfos.size();
    }

    /**
     * Get the ADN Uri for different Subscription.
     *
     * @param subId
     * @return
     */
    public Uri getAdnUri(long subId) {
        if (mSimIds.length == 1 && getActiveSubInfoCount() == 1) {
            return Uri.parse(ICC_ADN_URI);
        }
        return Uri.parse(ICC_ADN_URI + subId);
    }

    public void addSimListener(SimListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeSimListener(SimListener listener) {
        if (mListeners != null) {
            mListeners.remove(listener);
        }
    }

    private void notifyListener() {
        if (mListeners != null && !mListeners.isEmpty()) {
            for (SimListener listener : mListeners) {
                listener.onStateChange();
            }
        }
    }

    private void updateSubInfos() {
        mAllSubInfos = SubscriptionController.getAllSubInfoList(mContext);
        mActiveSubInfos = SubscriptionController.getActivatedSubInfoList(mContext);

        if (mAllSubInfos == null || mActiveSubInfos == null) {
            log("[SIMInfoWrapper] mSimInfoList OR mInsertedSimInfoList is null");
            return;
        }
    }

    /**
     * Check the SIM slot whether has a ICC Card
     * 
     * @param simId
     * @return
     */
    public boolean hasIccCard(int simId) {
        return TelephonyManager.getDefault().hasIccCard(simId);
    }

    /**
     * When receive SIM status change action, update the subscription cache and
     * notify resisted SIMListeners.
     */
    private class SimInfoReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                updateSubInfos();
                notifyListener();
            }
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

}
