// Copyright 2013 Google Inc. All Rights Reserved.

package com.android.dialer;

import android.app.Application;

import com.android.contacts.common.extensions.ExtensionsFactory;
import com.android.dialer.dsds.SimSubInfoWrapper;

public class DialerApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ExtensionsFactory.init(getApplicationContext());
        SimSubInfoWrapper.getInstance().init(this);
    }
}
