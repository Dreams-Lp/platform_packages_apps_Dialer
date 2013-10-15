/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.os.Bundle;
import android.telephony.TelephonyManager;

import com.android.dialer.R;
import com.android.dialer.DialtactsActivity;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.RILConstants.SimCardID;

public class PhoneSelect extends Activity {
    private static final String TAG = "PhoneSelect";

    private Button mGsm1Button;
    private Button mGsm2Button;
    private Uri mData;

    private boolean mVoiceMail = false;
    private boolean mAdn = false;
    private String mCallOrigin = null;

    private TextView mOperatorName1, mOperatorName2;
    private static final Uri SIM_NAMES_CONTENT_URI = Uri.parse("content://com.broadcom.simname/simnames");
    private static final String[] PROJECTION = {"_id", "sim_imsi", "sim_name", "sim_name_enabled"};
    private TelephonyManager tm1, tm2;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Intent intent = getIntent();

        Log.d(TAG, "onCreate(): intent = " + intent);

        mData = intent.getData();
        if (intent.hasExtra(DialtactsActivity.EXTRA_CALL_ORIGIN)) {
            mCallOrigin = intent.getStringExtra(DialtactsActivity.EXTRA_CALL_ORIGIN);
        }
        String scheme = mData.getScheme();
        if (scheme.equals("voicemail")) {
            mVoiceMail = true;
        }else if (scheme.equals("adn")){
            mAdn = true;
        }

        if( TelephonyManager.CALL_STATE_IDLE != TelephonyManager.getDefault(SimCardID.ID_ZERO).getCallState()){
            // SIM 1 is active
            setContentView(R.layout.phone_sim1);
            mGsm1Button = (Button)findViewById(R.id.gsm1Button);
            if (mAdn){
                mGsm1Button.setText(R.string.SIM1);
            }
            mGsm1Button.setOnClickListener(new Gsm1ButtonListener());
        }
        else if( TelephonyManager.CALL_STATE_IDLE != TelephonyManager.getDefault(SimCardID.ID_ONE).getCallState()){
            // SIM 2 is active
            setContentView(R.layout.phone_sim2);
            mGsm2Button = (Button)findViewById(R.id.gsm2Button);
            if (mAdn){
                mGsm2Button.setText(R.string.SIM2);
            }
            mGsm2Button.setOnClickListener(new Gsm2ButtonListener());
        }
        else{
            setContentView(R.layout.phone_select);

            mGsm1Button = (Button) findViewById(R.id.gsm1Button);
            mGsm2Button = (Button) findViewById(R.id.gsm2Button);

            tm1 = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE1);
            tm2 = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE2);

            mOperatorName1 = (TextView) findViewById(R.id.sim1Name);
            String simSelectName1 = getEnabledSimSelectName(tm1.getSubscriberId());

            if (simSelectName1.length() == 0) {  // no customized name, use operator name
                simSelectName1 = tm1.getNetworkOperatorName();
            }

            if (simSelectName1.length() == 0) {
                simSelectName1 = "SIM 1";  // neither customized name nor operator name found
            }

            String phoneNumber1 = tm1.getLine1Number();
            if (phoneNumber1 == null) {
                phoneNumber1 = "";
            }
            mOperatorName1.setText(simSelectName1+ "\n" + phoneNumber1);

            mOperatorName2 = (TextView) findViewById(R.id.sim2Name);
            String simSelectName2 = getEnabledSimSelectName(tm2.getSubscriberId());

            if (simSelectName2.length() == 0) {  // no customized name, use operator name
                simSelectName2 = tm2.getNetworkOperatorName();
            }

            if (simSelectName2.length() == 0) {
                simSelectName2 = "SIM 2";  // neither customized name nor operator name found
            }

            String phoneNumber2 = tm2.getLine1Number();
            if (phoneNumber2 == null) {
                phoneNumber2 = "";
            }

            mOperatorName2.setText(simSelectName2+ "\n" + phoneNumber2);

            mOperatorName1.setOnClickListener(new Gsm1ButtonListener());
            mOperatorName2.setOnClickListener(new Gsm2ButtonListener());

            if (mAdn){
                mGsm1Button.setText(R.string.SIM1);
                mGsm2Button.setText(R.string.SIM2);
            }
            mGsm1Button.setOnClickListener(new Gsm1ButtonListener());
            mGsm2Button.setOnClickListener(new Gsm2ButtonListener());
        }

    }

    @Override
    protected void onPause() {
        super.onPause();

        setResult(RESULT_CANCELED);
    }

    @Override
    protected void onResume() {
        super.onResume();

        SimCardID simCardId = SimCardID.ID_ZERO;
        boolean popUserMenu = false;
        int defalut_simCardId = getDefaultSIMId();

        if(isSimStateReady(SimCardID.ID_ZERO) && isSimStateReady(SimCardID.ID_ONE)) {
            if(defalut_simCardId == SimCardID.ID_PROMPT.toInt()) {
                popUserMenu = true;
            } else if (defalut_simCardId == 0) {
                simCardId = SimCardID.ID_ZERO;
            } else {
                simCardId = SimCardID.ID_ONE;
            }
        } else if(isSimStateReady(SimCardID.ID_ZERO)) {
            simCardId = SimCardID.ID_ZERO;
        } else if(isSimStateReady(SimCardID.ID_ONE)) {
            simCardId = SimCardID.ID_ONE;
        } else {
            simCardId = SimCardID.ID_ZERO;
        }

        if (!popUserMenu) {
            Log.d(TAG, "no selection needed");
            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED, mData);
            if (mVoiceMail){
                intent.setData(Uri.parse("voicemail://PHONE1"));
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra("simId", simCardId);
            if (null != mCallOrigin) {
                intent.putExtra(DialtactsActivity.EXTRA_CALL_ORIGIN, mCallOrigin);
            }
            startActivity(intent);
            setResult(RESULT_OK);
            finish();
        }
    }

    private class Gsm1ButtonListener implements OnClickListener {
        public void onClick(View v) {
            if (mAdn){
                Intent intent = new Intent();
                intent.putExtra("simId", SimCardID.ID_ZERO);
                setResult(RESULT_OK, intent);
                finish();
                return;
            }

            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED, mData);
            if (mVoiceMail){
                intent.setData(Uri.parse("voicemail://PHONE1"));
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra("simId", SimCardID.ID_ZERO);
            if (null != mCallOrigin) {
                intent.putExtra(DialtactsActivity.EXTRA_CALL_ORIGIN, mCallOrigin);
            }
            startActivity(intent);
            setResult(RESULT_OK);
            finish();
        }
    };

    private class Gsm2ButtonListener implements OnClickListener {
        public void onClick(View v) {
            if (mAdn){
                Intent intent = new Intent();
                intent.putExtra("simId", SimCardID.ID_ONE);
                setResult(RESULT_OK, intent);
                finish();
                return;
            }

            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED, mData);
            if (mVoiceMail){
                intent.setData(Uri.parse("voicemail://PHONE2"));
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra("simId", SimCardID.ID_ONE);
            if (null != mCallOrigin) {
                intent.putExtra(DialtactsActivity.EXTRA_CALL_ORIGIN, mCallOrigin);
            }
            startActivity(intent);
            setResult(RESULT_OK);
            finish();
        }
    }

    private String getEnabledSimSelectName(String imsi) {
        String simSelectName = "";

        Cursor cursor = getContentResolver().query(SIM_NAMES_CONTENT_URI, PROJECTION, "sim_imsi="
        + imsi + " AND sim_name_enabled=1", null,"_id DESC");

    if (null != cursor) {
        if (cursor.moveToFirst())
            simSelectName = cursor.getString(2);

        cursor.close();
    }
        return simSelectName;
    }

    /**
     * @return true if SIM state ready
     */
    private static boolean isSimStateReady(SimCardID simId) {
        return (TelephonyManager.SIM_STATE_READY == TelephonyManager.getDefault(simId).getSimState());
    }

    /**
     * @return default sim id
     */
    private static int getDefaultSIMId() {
        int defSimCardId = SimCardID.ID_ZERO.toInt();
        try {
            ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone1"));
            defSimCardId = phone.getDefaultSimForVoiceCalls();
        } catch (RemoteException e) {
            Log.w(TAG, "get DefaultSIMId failed", e);
        }

        return defSimCardId;
    }
}
