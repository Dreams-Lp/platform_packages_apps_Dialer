/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.dialer.dialpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.ContactsContract.Contacts;
import android.provider.Contacts.People;
import android.provider.Contacts.Phones;
import android.provider.Contacts.PhonesColumns;
import android.provider.ContactsContract.Intents;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TableRow;
import android.widget.TextView;

import com.android.contacts.common.CallUtil;
import com.android.contacts.common.GeoUtil;
import com.android.contacts.common.activity.TransactionSafeActivity;
import com.android.contacts.common.preference.ContactsPreferences;
import com.android.contacts.common.util.PhoneNumberFormatter;
import com.android.contacts.common.util.StopWatch;
import com.android.dialer.NeededForReflection;
import com.android.dialer.DialtactsActivity;
import com.android.dialer.R;
import com.android.dialer.SpecialCharSequenceMgr;
import com.android.dialer.database.DialerDatabaseHelper;
import com.android.dialer.interactions.PhoneNumberInteraction;
import com.android.dialer.util.OrientationUtil;
import com.android.internal.telephony.ITelephony;
import com.android.phone.common.CallLogAsync;
import com.android.phone.common.HapticFeedback;
import com.google.common.annotations.VisibleForTesting;

import java.util.HashSet;

import android.widget.Toast;
import com.android.internal.telephony.RILConstants.SimCardID;
import android.os.SystemClock;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyProperties;

import android.view.MotionEvent;
import android.view.View.OnTouchListener;

import com.android.contacts.common.list.ContactListItemView;
import com.android.contacts.common.list.DirectoryListLoader;
import com.android.contacts.common.list.DirectoryPartition;
//import com.android.contacts.list.LegacyPhoneNumberListAdapter;
import com.android.contacts.common.list.PhoneNumberListAdapter;
import com.android.contacts.common.preference.ContactsPreferences;

import android.widget.AbsListView.OnScrollListener;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.CursorLoader;
import android.content.Loader;
import android.os.Handler;
import android.os.Message;
import android.provider.ContactsContract.Directory;
import com.android.common.widget.CompositeCursorAdapter.Partition;
import android.widget.AbsListView;
import com.android.contacts.common.ContactPhotoManager;

/**
 * Fragment that displays a twelve-key phone dialpad.
 */
public class DialpadFragment extends Fragment
        implements View.OnClickListener,
        View.OnLongClickListener, View.OnKeyListener,
        AdapterView.OnItemClickListener, TextWatcher,
        PopupMenu.OnMenuItemClickListener,
        DialpadKeyButton.OnPressedListener,
        OnTouchListener,
        OnScrollListener,
        LoaderCallbacks<Cursor> {
    private static final String TAG = DialpadFragment.class.getSimpleName();

    public interface OnDialpadFragmentStartedListener {
        public void onDialpadFragmentStarted();
    }

    /**
     * LinearLayout with getter and setter methods for the translationY property using floats,
     * for animation purposes.
     */
    public static class DialpadSlidingLinearLayout extends LinearLayout {

        public DialpadSlidingLinearLayout(Context context) {
            super(context);
        }

        public DialpadSlidingLinearLayout(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public DialpadSlidingLinearLayout(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        @NeededForReflection
        public float getYFraction() {
            final int height = getHeight();
            if (height == 0) return 0;
            return getTranslationY() / height;
        }

        @NeededForReflection
        public void setYFraction(float yFraction) {
            setTranslationY(yFraction * getHeight());
        }
    }

    /**
     * LinearLayout that always returns true for onHoverEvent callbacks, to fix
     * problems with accessibility due to the dialpad overlaying other fragments.
     */
    public static class HoverIgnoringLinearLayout extends LinearLayout {

        public HoverIgnoringLinearLayout(Context context) {
            super(context);
        }

        public HoverIgnoringLinearLayout(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public HoverIgnoringLinearLayout(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            return true;
        }
    }

    public interface OnDialpadQueryChangedListener {
        void onDialpadQueryChanged(String query);
    }

    private static final boolean DEBUG = DialtactsActivity.DEBUG;

    // This is the amount of screen the dialpad fragment takes up when fully displayed
    private static final float DIALPAD_SLIDE_FRACTION = 0.67f;

    private static final String EMPTY_NUMBER = "";
    private static final char PAUSE = ',';
    private static final char WAIT = ';';

    /** The length of DTMF tones in milliseconds */
    private static final int TONE_LENGTH_MS = 150;
    private static final int TONE_LENGTH_INFINITE = -1;

    /** The DTMF tone volume relative to other sounds in the stream */
    private static final int TONE_RELATIVE_VOLUME = 80;

    /** Stream type used to play the DTMF tones off call, and mapped to the volume control keys */
    private static final int DIAL_TONE_STREAM_TYPE = AudioManager.STREAM_DTMF;

    public interface Listener {
        public void onSearchButtonPressed(String searchText);
    }

    private ContactsPreferences mContactsPrefs;

    private OnDialpadQueryChangedListener mDialpadQueryListener;

    /**
     * View (usually FrameLayout) containing mDigits field. This can be null, in which mDigits
     * isn't enclosed by the container.
     */
    private View mDigitsContainer;
    private EditText mDigits;

    /** Remembers if we need to clear digits field when the screen is completely gone. */
    private boolean mClearDigitsOnStop;

    private View mDelete;
    private ToneGenerator mToneGenerator;
    private final Object mToneGeneratorLock = new Object();
    private View mDialpad;
    private View mSpacer;

    /**
     * Set of dialpad keys that are currently being pressed
     */
    private final HashSet<View> mPressedDialpadKeys = new HashSet<View>(12);

    private Listener mListener;

    private View mDialButtonContainer;
    private View mDialButton;
    private View mDialButtonVT;
    private View mDialButtonSim1;
    private View mDialButtonSim2;
    private static final int STATUS_NOT_LOADED = 0;
    private static final int STATUS_LOADING = 1;
    private static final int STATUS_LOADED = 2;

    private static final int DIRECTORY_LOADER_ID = -1;

    private int mDirectoryListStatus = STATUS_NOT_LOADED;
    private static final int DEFAULT_DIRECTORY_RESULT_LIMIT = 20;
    private static final int DIRECTORY_SEARCH_MESSAGE = 1;
    private static final String DIRECTORY_ID_ARG_KEY = "directoryId";
    private static final int DIRECTORY_SEARCH_DELAY_MILLIS = 300;

    private ListView mDialSearchList;
    private ContactPhotoManager mPhotoManager;
    private PhoneNumberListAdapter mAdapter;
    //private ContactsPreferences mContactsPrefs;
    private int mDisplayOrder;
    private int mSortOrder;
    private boolean mForceLoad;

    /**
     * Indicates whether we are doing the initial complete load of data (false) or
     * a refresh caused by a change notification (true)
     */
    private boolean mLoadPriorityDirectoriesOnly;
    private String mQueryString;
    private boolean mSearchMode;
    private int mDirectorySearchMode = DirectoryListLoader.SEARCH_MODE_NONE;
    private ListView mDialpadChooser;
    private DialpadChooserAdapter mDialpadChooserAdapter;

    /**
     * Regular expression prohibiting manual phone call. Can be empty, which means "no rule".
     */
    private String mProhibitedPhoneNumberRegexp;

    //BCM215x SIM VM status flag
    private static final int BCM_SIM_VM_NONE = 0;
    private static final int BCM_SIM_VM_1 = 1;
    private static final int BCM_SIM_VM_2 = 2;
    private static final int BCM_SIM_VM_BOTH = 3;
    private static final int BCM_AIRPLANE_MODE_ON_WAITTIME = 3500;
    private static final String BCM_VM1PWR_SAVING_PATH = "gsm.vm1pwrsaving";
    private static final String BCM_VM2PWR_SAVING_PATH = "gsm.vm2pwrsaving";
    private static final String BCM_VM1PWR_SAVING_PRE_PATH = "gsm.vm1pwrsaving_pre";
    private static final String BCM_VM2PWR_SAVING_PRE_PATH = "gsm.vm2pwrsaving_pre";

    // Last number dialed, retrieved asynchronously from the call DB
    // in onCreate. This number is displayed when the user hits the
    // send key and cleared in onPause.
    private final CallLogAsync mCallLog = new CallLogAsync();
    private String mLastNumberDialed = EMPTY_NUMBER;

    // determines if we want to playback local DTMF tones.
    private boolean mDTMFToneEnabled;

    // Vibration (haptic feedback) for dialer key presses.
    private final HapticFeedback mHaptic = new HapticFeedback();

    /** Identifier for the "Add Call" intent extra. */
    private static final String ADD_CALL_MODE_KEY = "add_call_mode";

    /**
     * Identifier for intent extra for sending an empty Flash message for
     * CDMA networks. This message is used by the network to simulate a
     * press/depress of the "hookswitch" of a landline phone. Aka "empty flash".
     *
     * TODO: Using an intent extra to tell the phone to send this flash is a
     * temporary measure. To be replaced with an ITelephony call in the future.
     * TODO: Keep in sync with the string defined in OutgoingCallBroadcaster.java
     * in Phone app until this is replaced with the ITelephony API.
     */
    private static final String EXTRA_SEND_EMPTY_FLASH
            = "com.android.phone.extra.SEND_EMPTY_FLASH";

    private String mCurrentCountryIso;

    private static final int REQUEST_EXIT_NOTIFY = 8001;

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        /**
         * Listen for phone state changes so that we can take down the
         * "dialpad chooser" if the phone becomes idle while the
         * chooser UI is visible.
         */
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            // Log.i(TAG, "PhoneStateListener.onCallStateChanged: "
            //       + state + ", '" + incomingNumber + "'");
            if ((state == TelephonyManager.CALL_STATE_IDLE) && dialpadChooserVisible()) {
                // Log.i(TAG, "Call ended with dialpad chooser visible!  Taking it down...");
                // Note there's a race condition in the UI here: the
                // dialpad chooser could conceivably disappear (on its
                // own) at the exact moment the user was trying to select
                // one of the choices, which would be confusing.  (But at
                // least that's better than leaving the dialpad chooser
                // onscreen, but useless...)
                showDialpadChooser(false);
            }
        }
    };

    private boolean mWasEmptyBeforeTextChange;

    /**
     * This field is set to true while processing an incoming DIAL intent, in order to make sure
     * that SpecialCharSequenceMgr actions can be triggered by user input but *not* by a
     * tel: URI passed by some other app.  It will be set to false when all digits are cleared.
     */
    private boolean mDigitsFilledByIntent;

    private boolean mStartedFromNewIntent = false;
    private boolean mFirstLaunch = false;
    private boolean mAdjustTranslationForAnimation = false;

    private static final String PREF_DIGITS_FILLED_BY_INTENT = "pref_digits_filled_by_intent";

    /**
     * Return an Intent for launching voicemail screen.
     */
    private static Intent getVoicemailIntent() {

        Intent intent;
        if(SystemProperties.getInt("ro.dual.sim.phone", 0) == 1) {
            intent = new Intent();
            intent.setClassName("com.android.contacts", "com.android.contacts.PhoneSelect");
            intent.setData(Uri.fromParts("voicemail", "", null));
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        }else {
            intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                                             Uri.fromParts("voicemail", "", null));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        return intent;
    }

    private TelephonyManager getTelephonyManager() {
        return (TelephonyManager) getActivity().getSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        mWasEmptyBeforeTextChange = TextUtils.isEmpty(s);
    }

    @Override
    public void onTextChanged(CharSequence input, int start, int before, int changeCount) {
        if (mWasEmptyBeforeTextChange != TextUtils.isEmpty(input)) {
            final Activity activity = getActivity();
            if (activity != null) {
                activity.invalidateOptionsMenu();
            }
        }

        // DTMF Tones do not need to be played here any longer -
        // the DTMF dialer handles that functionality now.
    }

    @Override
    public void afterTextChanged(Editable input) {
        // When DTMF dialpad buttons are being pressed, we delay SpecialCharSequencMgr sequence,
        // since some of SpecialCharSequenceMgr's behavior is too abrupt for the "touch-down"
        // behavior.
        if (!mDigitsFilledByIntent &&
                SpecialCharSequenceMgr.handleChars(getActivity(), input.toString(), mDigits)) {
            // A special sequence was entered, clear the digits
            mDigits.getText().clear();
        } else if (SpecialCharSequenceMgr.handleSimRelatedChars(getActivity(), input.toString(), mDigits)) {
            // A special sim related sequence was entered, clear the digits
            mDigits.getText().clear();
        }

        if (isDigitsEmpty()) {
            mDigitsFilledByIntent = false;
            mDigits.setCursorVisible(false);
        }

        if (mDialpadQueryListener != null) {
            mDialpadQueryListener.onDialpadQueryChanged(mDigits.getText().toString());
        }
        updateDialAndDeleteButtonEnabledState();

        if (null != mDialSearchList) {
            setQueryString(mDigits.getText().toString(), true);
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        mFirstLaunch = true;
        mContactsPrefs = new ContactsPreferences(getActivity());
        mCurrentCountryIso = GeoUtil.getCurrentCountryIso(getActivity());

        try {
            mHaptic.init(getActivity(),
                         getResources().getBoolean(R.bool.config_enable_dialer_key_vibration));
        } catch (Resources.NotFoundException nfe) {
             Log.e(TAG, "Vibrate control bool missing.", nfe);
        }

        mProhibitedPhoneNumberRegexp = getResources().getString(
                R.string.config_prohibited_phone_number_regexp);

        if (state != null) {
            mDigitsFilledByIntent = state.getBoolean(PREF_DIGITS_FILLED_BY_INTENT);
        }
        mContactsPrefs = new ContactsPreferences(getActivity());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        final View fragmentView = inflater.inflate(R.layout.dialpad_fragment, container,
                false);
        fragmentView.buildLayer();

        final ViewTreeObserver vto = fragmentView.getViewTreeObserver();
        // Adjust the translation of the DialpadFragment in a preDrawListener instead of in
        // DialtactsActivity, because at the point in time when the DialpadFragment is added,
        // its views have not been laid out yet.
        final OnPreDrawListener preDrawListener = new OnPreDrawListener() {

            @Override
            public boolean onPreDraw() {

                if (isHidden()) return true;
                if (mAdjustTranslationForAnimation && fragmentView.getTranslationY() == 0) {
                    ((DialpadSlidingLinearLayout) fragmentView).setYFraction(
                            DIALPAD_SLIDE_FRACTION);
                }
                final ViewTreeObserver vto = fragmentView.getViewTreeObserver();
                vto.removeOnPreDrawListener(this);
                return true;
            }

        };

        vto.addOnPreDrawListener(preDrawListener);

        // Load up the resources for the text field.
        Resources r = getResources();

        mDialButtonContainer = fragmentView.findViewById(R.id.dialButtonContainer);
        mDigitsContainer = fragmentView.findViewById(R.id.digits_container);
        mDigits = (EditText) fragmentView.findViewById(R.id.digits);
        mDigits.setKeyListener(UnicodeDialerKeyListener.INSTANCE);
        mDigits.setOnClickListener(this);
        mDigits.setOnKeyListener(this);
        mDigits.setOnLongClickListener(this);
        mDigits.addTextChangedListener(this);
        PhoneNumberFormatter.setPhoneNumberFormattingTextWatcher(getActivity(), mDigits);
        // Check for the presence of the keypad
        View oneButton = fragmentView.findViewById(R.id.one);
        if (oneButton != null) {
            setupKeypad(fragmentView);
        }
        mDialButton = fragmentView.findViewById(R.id.dialButton);
        //mDialButtonVT = fragmentView.findViewById(R.id.dialButtonVT);
        mDialButtonSim1 = fragmentView.findViewById(R.id.dialButtonSIM1);
        mDialButtonSim2 = fragmentView.findViewById(R.id.dialButtonSIM2);
        if (r.getBoolean(R.bool.config_show_onscreen_dial_button)) {

            if(SystemProperties.getInt("ro.dual.sim.phone", 0) == 1) {
                mDialButtonSim1.setOnLongClickListener(this);
                mDialButtonSim1.setOnClickListener(this);
                mDialButtonSim2.setOnLongClickListener(this);
                mDialButtonSim2.setOnClickListener(this);
                mDialButton.setVisibility(View.GONE); // It's VISIBLE by default
                mDialButton = null;
            }else {
                mDialButton.setOnClickListener(this);
                mDialButton.setOnLongClickListener(this);
                mDialButtonSim1.setVisibility(View.GONE); // It's VISIBLE by default
                mDialButtonSim1 = null;
                mDialButtonSim2.setVisibility(View.GONE); // It's VISIBLE by default
                mDialButtonSim2 = null;
            }
        } else {
            mDialButton.setVisibility(View.GONE); // It's VISIBLE by default
            mDialButton = null;
            mDialButtonVT.setVisibility(View.GONE); // It's VISIBLE by default
            mDialButtonVT = null;
            mDialButtonSim1.setVisibility(View.GONE); // It's VISIBLE by default
            mDialButtonSim1 = null;
            mDialButtonSim2.setVisibility(View.GONE); // It's VISIBLE by default
            mDialButtonSim2 = null;
        }
        mDialSearchList = (ListView) fragmentView.findViewById(R.id.dial_search);
        int dial_search_weight = getActivity().getResources().getInteger(R.integer.dialpad_layout_weight_dial_search);
        if (0 >= dial_search_weight) {
            mDialSearchList.setVisibility(View.GONE);
            mDialSearchList = null;
        }

        mDelete = fragmentView.findViewById(R.id.deleteButton);
        if (mDelete != null) {
            mDelete.setOnClickListener(this);
            mDelete.setOnLongClickListener(this);
        }

        mSpacer = fragmentView.findViewById(R.id.spacer);
        mSpacer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isDigitsEmpty()) {
                    hideAndClearDialpad();
                    return true;
                }
                return false;
            }
        });

        mDialpad = fragmentView.findViewById(R.id.dialpad);  // This is null in landscape mode.

        // In landscape we put the keyboard in phone mode.
        if (null == mDialpad) {
            mDigits.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        } else {
            mDigits.setCursorVisible(false);
        }

        // Set up the "dialpad chooser" UI; see showDialpadChooser().
        mDialpadChooser = (ListView) fragmentView.findViewById(R.id.dialpadChooser);
        mDialpadChooser.setOnItemClickListener(this);

        if (null != mDialSearchList) {
            configDialSearchList();
        }

        return fragmentView;
    }

    @Override
    public void onStart() {
        super.onStart();

        final Activity activity = getActivity();

        try {
            ((OnDialpadFragmentStartedListener) activity).onDialpadFragmentStarted();
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnDialpadFragmentStartedListener");
        }

        final View overflowButton = getView().findViewById(R.id.overflow_menu_on_dialpad);
        overflowButton.setOnClickListener(this);
    }

    private boolean isLayoutReady() {
        return mDigits != null;
    }

    public EditText getDigitsWidget() {
        return mDigits;
    }

    /**
     * @return true when {@link #mDigits} is actually filled by the Intent.
     */
    private boolean fillDigitsIfNecessary(Intent intent) {
        // Only fills digits from an intent if it is a new intent.
        // Otherwise falls back to the previously used number.
        if (!mFirstLaunch && !mStartedFromNewIntent) {
            return false;
        }

        final String action = intent.getAction();
        if (Intent.ACTION_DIAL.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            if (uri != null) {
                if (CallUtil.SCHEME_TEL.equals(uri.getScheme())) {
                    // Put the requested number into the input area
                    String data = uri.getSchemeSpecificPart();
                    // Remember it is filled via Intent.
                    mDigitsFilledByIntent = true;
                    final String converted = PhoneNumberUtils.convertKeypadLettersToDigits(
                            PhoneNumberUtils.replaceUnicodeDigits(data));
                    setFormattedDigits(converted, null);
                    return true;
                } else {
                    String type = intent.getType();
                    if (People.CONTENT_ITEM_TYPE.equals(type)
                            || Phones.CONTENT_ITEM_TYPE.equals(type)) {
                        // Query the phone number
                        Cursor c = getActivity().getContentResolver().query(intent.getData(),
                                new String[] {PhonesColumns.NUMBER, PhonesColumns.NUMBER_KEY},
                                null, null, null);
                        if (c != null) {
                            try {
                                if (c.moveToFirst()) {
                                    // Remember it is filled via Intent.
                                    mDigitsFilledByIntent = true;
                                    // Put the number into the input area
                                    setFormattedDigits(c.getString(0), c.getString(1));
                                    return true;
                                }
                            } finally {
                                c.close();
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Determines whether an add call operation is requested.
     *
     * @param intent The intent.
     * @return {@literal true} if add call operation was requested.  {@literal false} otherwise.
     */
    private static boolean isAddCallMode(Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_DIAL.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            // see if we are "adding a call" from the InCallScreen; false by default.
            return intent.getBooleanExtra(ADD_CALL_MODE_KEY, false);
        } else {
            return false;
        }
    }

    /**
     * Checks the given Intent and changes dialpad's UI state. For example, if the Intent requires
     * the screen to enter "Add Call" mode, this method will show correct UI for the mode.
     */
    private void configureScreenFromIntent(Activity parent) {
        // If we were not invoked with a DIAL intent,
        if (!(parent instanceof DialtactsActivity)) {
            setStartedFromNewIntent(false);
            return;
        }
        // See if we were invoked with a DIAL intent. If we were, fill in the appropriate
        // digits in the dialer field.
        Intent intent = parent.getIntent();

        if (!isLayoutReady()) {
            // This happens typically when parent's Activity#onNewIntent() is called while
            // Fragment#onCreateView() isn't called yet, and thus we cannot configure Views at
            // this point. onViewCreate() should call this method after preparing layouts, so
            // just ignore this call now.
            Log.i(TAG,
                    "Screen configuration is requested before onCreateView() is called. Ignored");
            return;
        }

        boolean needToShowDialpadChooser = false;

        // Be sure *not* to show the dialpad chooser if this is an
        // explicit "Add call" action, though.
        final boolean isAddCallMode = isAddCallMode(intent);
        if (!isAddCallMode) {

            // Don't show the chooser when called via onNewIntent() and phone number is present.
            // i.e. User clicks a telephone link from gmail for example.
            // In this case, we want to show the dialpad with the phone number.
            final boolean digitsFilled = fillDigitsIfNecessary(intent);
            if (!(mStartedFromNewIntent && digitsFilled)) {

                final String action = intent.getAction();
                if (Intent.ACTION_DIAL.equals(action) || Intent.ACTION_VIEW.equals(action)
                        || Intent.ACTION_MAIN.equals(action)) {
                    // If there's already an active call, bring up an intermediate UI to
                    // make the user confirm what they really want to do.
                    if (phoneIsInUse()) {
                        needToShowDialpadChooser = true;
                    }
                }

            }
        }
        showDialpadChooser(needToShowDialpadChooser);
        setStartedFromNewIntent(false);
    }

    public void setStartedFromNewIntent(boolean value) {
        mStartedFromNewIntent = value;
    }

    /**
     * Sets formatted digits to digits field.
     */
    private void setFormattedDigits(String data, String normalizedNumber) {
        // strip the non-dialable numbers out of the data string.
        String dialString = PhoneNumberUtils.extractNetworkPortion(data);
        dialString =
                PhoneNumberUtils.formatNumber(dialString, normalizedNumber, mCurrentCountryIso);
        if (!TextUtils.isEmpty(dialString)) {
            Editable digits = mDigits.getText();
            digits.replace(0, digits.length(), dialString);
            // for some reason this isn't getting called in the digits.replace call above..
            // but in any case, this will make sure the background drawable looks right
            afterTextChanged(digits);
        }
    }

    private void setupKeypad(View fragmentView) {
        final int[] buttonIds = new int[] {R.id.zero, R.id.one, R.id.two, R.id.three, R.id.four,
                R.id.five, R.id.six, R.id.seven, R.id.eight, R.id.nine, R.id.star, R.id.pound};

        final int[] numberIds = new int[] {R.string.dialpad_0_number, R.string.dialpad_1_number,
                R.string.dialpad_2_number, R.string.dialpad_3_number, R.string.dialpad_4_number,
                R.string.dialpad_5_number, R.string.dialpad_6_number, R.string.dialpad_7_number,
                R.string.dialpad_8_number, R.string.dialpad_9_number, R.string.dialpad_star_number,
                R.string.dialpad_pound_number};

        final int[] letterIds = new int[] {R.string.dialpad_0_letters, R.string.dialpad_1_letters,
                R.string.dialpad_2_letters, R.string.dialpad_3_letters, R.string.dialpad_4_letters,
                R.string.dialpad_5_letters, R.string.dialpad_6_letters, R.string.dialpad_7_letters,
                R.string.dialpad_8_letters, R.string.dialpad_9_letters,
                R.string.dialpad_star_letters, R.string.dialpad_pound_letters};

        final Resources resources = getResources();

        DialpadKeyButton dialpadKey;
        TextView numberView;
        TextView lettersView;

        for (int i = 0; i < buttonIds.length; i++) {
            dialpadKey = (DialpadKeyButton) fragmentView.findViewById(buttonIds[i]);
            dialpadKey.setLayoutParams(new TableRow.LayoutParams(
                    TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.MATCH_PARENT));
            dialpadKey.setOnPressedListener(this);
            numberView = (TextView) dialpadKey.findViewById(R.id.dialpad_key_number);
            lettersView = (TextView) dialpadKey.findViewById(R.id.dialpad_key_letters);
            final String numberString = resources.getString(numberIds[i]);
            numberView.setText(numberString);
            dialpadKey.setContentDescription(numberString);
            if (lettersView != null) {
                lettersView.setText(resources.getString(letterIds[i]));
                if (buttonIds[i] == R.id.zero) {
                    lettersView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(
                            R.dimen.dialpad_key_plus_size));
                }
            }
        }

        // Long-pressing one button will initiate Voicemail.
        fragmentView.findViewById(R.id.one).setOnLongClickListener(this);

        // Long-pressing zero button will enter '+' instead.
        fragmentView.findViewById(R.id.zero).setOnLongClickListener(this);

    }

    @Override
    public void onResume() {
        super.onResume();

        final DialtactsActivity activity = (DialtactsActivity) getActivity();
        mDialpadQueryListener = activity;

        final StopWatch stopWatch = StopWatch.start("Dialpad.onResume");

        // Query the last dialed number. Do it first because hitting
        // the DB is 'slow'. This call is asynchronous.
        queryLastOutgoingCall();

        stopWatch.lap("qloc");

        final ContentResolver contentResolver = activity.getContentResolver();

        // retrieve the DTMF tone play back setting.
        mDTMFToneEnabled = Settings.System.getInt(contentResolver,
                Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1;

        stopWatch.lap("dtwd");

        // Retrieve the haptic feedback setting.
        mHaptic.checkSystemSetting();

        stopWatch.lap("hptc");

        // if the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new ToneGenerator(DIAL_TONE_STREAM_TYPE, TONE_RELATIVE_VOLUME);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }
        stopWatch.lap("tg");

        mPressedDialpadKeys.clear();

        configureScreenFromIntent(getActivity());

        stopWatch.lap("fdin");

        // While we're in the foreground, listen for phone state changes,
        // purely so that we can take down the "dialpad chooser" if the
        // phone becomes idle while the chooser UI is visible.
        getTelephonyManager().listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        stopWatch.lap("tm");

        // Potentially show hint text in the mDigits field when the user
        // hasn't typed any digits yet.  (If there's already an active call,
        // this hint text will remind the user that he's about to add a new
        // call.)
        //
        // TODO: consider adding better UI for the case where *both* lines
        // are currently in use.  (Right now we let the user try to add
        // another call, but that call is guaranteed to fail.  Perhaps the
        // entire dialer UI should be disabled instead.)
        if (phoneIsInUse()) {
            final SpannableString hint = new SpannableString(
                    getActivity().getString(R.string.dialerDialpadHintText));
            hint.setSpan(new RelativeSizeSpan(0.8f), 0, hint.length(), 0);
            mDigits.setHint(hint);
        } else {
            // Common case; no hint necessary.
            mDigits.setHint(null);

            // Also, a sanity-check: the "dialpad chooser" UI should NEVER
            // be visible if the phone is idle!
            showDialpadChooser(false);
        }

        mFirstLaunch = false;

        stopWatch.lap("hnt");

        updateDialAndDeleteButtonEnabledState();

        stopWatch.lap("bes");

        stopWatch.stopAndLog(TAG, 50);
    }

    @Override
    public void onPause() {
        super.onPause();

        // Stop listening for phone state changes.
        getTelephonyManager().listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);

        // Make sure we don't leave this activity with a tone still playing.
        stopTone();
        mPressedDialpadKeys.clear();

        synchronized (mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
        // TODO: I wonder if we should not check if the AsyncTask that
        // lookup the last dialed number has completed.
        mLastNumberDialed = EMPTY_NUMBER;  // Since we are going to query again, free stale number.
        if (null != mDialSearchList) {
            removePendingDirectorySearchRequests();
        }

        SpecialCharSequenceMgr.cleanup();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(PREF_DIGITS_FILLED_BY_INTENT, mDigitsFilledByIntent);
    }

    private void setupMenuItems(Menu menu) {
        final MenuItem addToContactMenuItem = menu.findItem(R.id.menu_add_contacts);
        final MenuItem callSettingsMenuItem1 = menu.findItem(R.id.menu_call_settings_dialpad1);
        final MenuItem callSettingsMenuItem2 = menu.findItem(R.id.menu_call_settings_dialpad2);

        // Check if all the menu items are inflated correctly. As a shortcut, we assume all menu
        // items are ready if the first item is non-null.
        if (callSettingsMenuItem1 == null) {
            return;
        }

        final Activity activity = getActivity();
        if (activity != null && ViewConfiguration.get(activity).hasPermanentMenuKey()) {
            // Call settings should be available via its parent Activity.
            callSettingsMenuItem1.setVisible(false);
            callSettingsMenuItem2.setVisible(false);
        } else {
            if(SystemProperties.getInt("ro.dual.sim.phone", 0) == 1) {
                if (TelephonyManager.getDefault(SimCardID.ID_ZERO).hasIccCard()) {
                    callSettingsMenuItem1.setVisible(true);
                    callSettingsMenuItem1.setIntent(DialtactsActivity.getCallSettingsIntent(SimCardID.ID_ZERO));
                } else {
                    callSettingsMenuItem1.setVisible(false);
                }

                if (TelephonyManager.getDefault(SimCardID.ID_ONE).hasIccCard()) {
                    callSettingsMenuItem2.setVisible(true);
                    callSettingsMenuItem2.setIntent(DialtactsActivity.getCallSettingsIntent(SimCardID.ID_ONE));
                } else {
                    callSettingsMenuItem2.setVisible(false);
                }
            }else {
                callSettingsMenuItem1.setVisible(true);
                callSettingsMenuItem1.setIntent(DialtactsActivity.getCallSettingsIntent(SimCardID.ID_ZERO));
                callSettingsMenuItem2.setVisible(false);
            }
        }

        // We show "add to contacts" menu only when the user is
        // seeing usual dialpad and has typed at least one digit.
        // We never show a menu if the "choose dialpad" UI is up.
        if (dialpadChooserVisible() || isDigitsEmpty()) {
            addToContactMenuItem.setVisible(false);
        } else {
            final CharSequence digits = mDigits.getText();
            // Put the current digits string into an intent
            addToContactMenuItem.setIntent(DialtactsActivity.getAddNumberToContactIntent(digits));
            addToContactMenuItem.setVisible(true);
        }
    }

    private void keyPressed(int keyCode) {
        if (getView().getTranslationY() != 0) {
            return;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_1:
                playTone(ToneGenerator.TONE_DTMF_1, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_2:
                playTone(ToneGenerator.TONE_DTMF_2, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_3:
                playTone(ToneGenerator.TONE_DTMF_3, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_4:
                playTone(ToneGenerator.TONE_DTMF_4, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_5:
                playTone(ToneGenerator.TONE_DTMF_5, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_6:
                playTone(ToneGenerator.TONE_DTMF_6, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_7:
                playTone(ToneGenerator.TONE_DTMF_7, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_8:
                playTone(ToneGenerator.TONE_DTMF_8, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_9:
                playTone(ToneGenerator.TONE_DTMF_9, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_0:
                playTone(ToneGenerator.TONE_DTMF_0, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_POUND:
                playTone(ToneGenerator.TONE_DTMF_P, TONE_LENGTH_INFINITE);
                break;
            case KeyEvent.KEYCODE_STAR:
                playTone(ToneGenerator.TONE_DTMF_S, TONE_LENGTH_INFINITE);
                break;
            default:
                break;
        }

        mHaptic.vibrate();
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        mDigits.onKeyDown(keyCode, event);

        // If the cursor is at the end of the text we hide it.
        final int length = mDigits.length();
        if (length == mDigits.getSelectionStart() && length == mDigits.getSelectionEnd()) {
            mDigits.setCursorVisible(false);
        }
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent event) {
        switch (view.getId()) {
            case R.id.digits:
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    dialButtonPressed();
                    return true;
                }
                break;
        }
        return false;
    }

    /**
     * When a key is pressed, we start playing DTMF tone, do vibration, and enter the digit
     * immediately. When a key is released, we stop the tone. Note that the "key press" event will
     * be delivered by the system with certain amount of delay, it won't be synced with user's
     * actual "touch-down" behavior.
     */
    @Override
    public void onPressed(View view, boolean pressed) {
        if (DEBUG) Log.d(TAG, "onPressed(). view: " + view + ", pressed: " + pressed);
        if (pressed) {
            switch (view.getId()) {
                case R.id.one: {
                    keyPressed(KeyEvent.KEYCODE_1);
                    break;
                }
                case R.id.two: {
                    keyPressed(KeyEvent.KEYCODE_2);
                    break;
                }
                case R.id.three: {
                    keyPressed(KeyEvent.KEYCODE_3);
                    break;
                }
                case R.id.four: {
                    keyPressed(KeyEvent.KEYCODE_4);
                    break;
                }
                case R.id.five: {
                    keyPressed(KeyEvent.KEYCODE_5);
                    break;
                }
                case R.id.six: {
                    keyPressed(KeyEvent.KEYCODE_6);
                    break;
                }
                case R.id.seven: {
                    keyPressed(KeyEvent.KEYCODE_7);
                    break;
                }
                case R.id.eight: {
                    keyPressed(KeyEvent.KEYCODE_8);
                    break;
                }
                case R.id.nine: {
                    keyPressed(KeyEvent.KEYCODE_9);
                    break;
                }
                case R.id.zero: {
                    keyPressed(KeyEvent.KEYCODE_0);
                    break;
                }
                case R.id.pound: {
                    keyPressed(KeyEvent.KEYCODE_POUND);
                    break;
                }
                case R.id.star: {
                    keyPressed(KeyEvent.KEYCODE_STAR);
                    break;
                }
                default: {
                    Log.wtf(TAG, "Unexpected onTouch(ACTION_DOWN) event from: " + view);
                    break;
                }
            }
            mPressedDialpadKeys.add(view);
        } else {
            view.jumpDrawablesToCurrentState();
            mPressedDialpadKeys.remove(view);
            if (mPressedDialpadKeys.isEmpty()) {
                stopTone();
            }
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.overflow_menu_on_dialpad: {
                final PopupMenu popupMenu = new PopupMenu(getActivity(), view);
                final Menu menu = popupMenu.getMenu();
                popupMenu.inflate(R.menu.dialpad_options);
                popupMenu.setOnMenuItemClickListener(this);
                setupMenuItems(menu);
                popupMenu.show();
                break;
            }
            case R.id.deleteButton: {
                keyPressed(KeyEvent.KEYCODE_DEL);
                return;
            }
            case R.id.dialButton:
            case R.id.dialButtonSIM1: {
                mHaptic.vibrate();  // Vibrate here too, just like we do for the regular keys
                dialButtonPressed(SimCardID.ID_ZERO, false);
                return;
            }

            case R.id.dialButtonSIM2: {
                mHaptic.vibrate();  // Vibrate here too, just like we do for the regular keys
                dialButtonPressed(SimCardID.ID_ONE, false);
                return;
            }
            case R.id.digits: {
                if (!isDigitsEmpty()) {
                    mDigits.setCursorVisible(true);
                }
                return;
            }
            default: {
                Log.wtf(TAG, "Unexpected onClick() event from: " + view);
                return;
            }
        }
    }

    @Override
    public boolean onLongClick(View view) {
        final Editable digits = mDigits.getText();
        final int id = view.getId();
        switch (id) {
            case R.id.deleteButton: {
                digits.clear();
                // TODO: The framework forgets to clear the pressed
                // status of disabled button. Until this is fixed,
                // clear manually the pressed status. b/2133127
                mDelete.setPressed(false);
                return true;
            }
            case R.id.one: {
                // '1' may be already entered since we rely on onTouch() event for numeric buttons.
                // Just for safety we also check if the digits field is empty or not.
                if (isDigitsEmpty() || TextUtils.equals(mDigits.getText(), "1")) {
                    // We'll try to initiate voicemail and thus we want to remove irrelevant string.
                    removePreviousDigitIfPossible();

                    if (isVoicemailAvailable()) {
                        callVoicemail();
                    } else if (getActivity() != null) {
                        // Voicemail is unavailable maybe because Airplane mode is turned on.
                        // Check the current status and show the most appropriate error message.
                        final boolean isAirplaneModeOn =
                                Settings.System.getInt(getActivity().getContentResolver(),
                                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
                        if (isAirplaneModeOn) {
                            DialogFragment dialogFragment = ErrorDialogFragment.newInstance(
                                    R.string.dialog_voicemail_airplane_mode_message);
                            dialogFragment.show(getFragmentManager(),
                                    "voicemail_request_during_airplane_mode");
                        } else {
                            DialogFragment dialogFragment = ErrorDialogFragment.newInstance(
                                    R.string.dialog_voicemail_not_ready_message);
                            dialogFragment.show(getFragmentManager(), "voicemail_not_ready");
                        }
                    }
                    return true;
                }
                return false;
            }
            case R.id.zero: {
                // Remove tentative input ('0') done by onTouch().
                removePreviousDigitIfPossible();
                keyPressed(KeyEvent.KEYCODE_PLUS);

                // Stop tone immediately
                stopTone();
                mPressedDialpadKeys.remove(view);

                return true;
            }
            case R.id.digits: {
                // Right now EditText does not show the "paste" option when cursor is not visible.
                // To show that, make the cursor visible, and return false, letting the EditText
                // show the option by itself.
                mDigits.setCursorVisible(true);
                return false;
            }
            case R.id.dialButton: {
                if (isDigitsEmpty()) {
                    handleDialButtonClickWithEmptyDigits();
                    // This event should be consumed so that onClick() won't do the exactly same
                    // thing.
                    return true;
                } else {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Remove the digit just before the current position. This can be used if we want to replace
     * the previous digit or cancel previously entered character.
     */
    private void removePreviousDigitIfPossible() {
        final Editable editable = mDigits.getText();
        final int currentPosition = mDigits.getSelectionStart();
        if (currentPosition > 0) {
            mDigits.setSelection(currentPosition);
            mDigits.getText().delete(currentPosition - 1, currentPosition);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_EXIT_NOTIFY: {
                if (resultCode == Activity.RESULT_OK){
                    //clear the input and finish
                    mClearDigitsOnStop = true;
                    getActivity().finish();
                }
                break;
            }
        }
    }

    public void callVoicemail() {
        startActivityForResult(getVoicemailIntent(), REQUEST_EXIT_NOTIFY);
    }

    private void hideAndClearDialpad() {
        ((DialtactsActivity) getActivity()).hideDialpadFragment(false, true);
    }

    public static class ErrorDialogFragment extends DialogFragment {
        private int mTitleResId;
        private int mMessageResId;

        private static final String ARG_TITLE_RES_ID = "argTitleResId";
        private static final String ARG_MESSAGE_RES_ID = "argMessageResId";

        public static ErrorDialogFragment newInstance(int messageResId) {
            return newInstance(0, messageResId);
        }

        public static ErrorDialogFragment newInstance(int titleResId, int messageResId) {
            final ErrorDialogFragment fragment = new ErrorDialogFragment();
            final Bundle args = new Bundle();
            args.putInt(ARG_TITLE_RES_ID, titleResId);
            args.putInt(ARG_MESSAGE_RES_ID, messageResId);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mTitleResId = getArguments().getInt(ARG_TITLE_RES_ID);
            mMessageResId = getArguments().getInt(ARG_MESSAGE_RES_ID);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            if (mTitleResId != 0) {
                builder.setTitle(mTitleResId);
            }
            if (mMessageResId != 0) {
                builder.setMessage(mMessageResId);
            }
            builder.setPositiveButton(android.R.string.ok,
                    new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                            }
                    });
            return builder.create();
        }
    }

    private boolean isSimStateReady(SimCardID simId) {
        return (TelephonyManager.SIM_STATE_READY == TelephonyManager.getDefault(simId).getSimState());
    }

    private boolean isRegisterdToNetwork(SimCardID simId) {
        return (0 < TelephonyManager.getDefault(simId).getNetworkOperator().length());
    }

    private int getCurrentVMStatus() {
        int vmOneStatus = SystemProperties.getInt(BCM_VM1PWR_SAVING_PATH, 0);
        int vmTwoStatus = SystemProperties.getInt(BCM_VM2PWR_SAVING_PATH, 0);
        final int settingSIMOneStatus = Settings.System.getInt(getActivity().getContentResolver(), Settings.Global.PHONE1_ON, 0);
        final int settingSIMTwoStatus = Settings.System.getInt(getActivity().getContentResolver(), Settings.Global.PHONE2_ON, 0);
        int rs = BCM_SIM_VM_NONE;

        if ((1 == settingSIMOneStatus) && (1 == settingSIMTwoStatus)) {
            if (0 != Settings.System.getInt(getActivity().getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0)) {
                vmOneStatus = SystemProperties.getInt(BCM_VM1PWR_SAVING_PRE_PATH, -1);
                vmTwoStatus = SystemProperties.getInt(BCM_VM2PWR_SAVING_PRE_PATH, -1);
                if (-1 == vmOneStatus || -1 == vmTwoStatus) { // Turn off flymode in case it's not set by user (flymode already when boot)
                    // Change the system setting
                    Settings.System.putInt(getActivity().getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0);
                    // Post the intent
                    Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                    intent.putExtra("state", false);
                    getActivity().sendBroadcast(intent);
                    SystemClock.sleep(BCM_AIRPLANE_MODE_ON_WAITTIME); // allow some time for SIM to set RF power
                    return getCurrentVMStatus();
                }
            }

            if ((0 == vmOneStatus) && (0 == vmTwoStatus)) {
                rs = BCM_SIM_VM_BOTH;
            } else if (0 == vmOneStatus) {
                rs = BCM_SIM_VM_1;
            } else if (0 == vmTwoStatus) {
                rs = BCM_SIM_VM_2;
            }
        } else if (1 == settingSIMTwoStatus) {
            rs = BCM_SIM_VM_2;
        } else  rs = BCM_SIM_VM_1;

        return rs;
    }

    private SimCardID getSimCardIdForVT() {
        String propNetworkTypeSim1 =
                SystemProperties.get(TelephonyProperties.PROPERTY_NETWORK_TYPE,
                String.valueOf(Phone.NT_MODE_WCDMA_PREF));
        String propNetworkTypeSim2 =
                SystemProperties.get(TelephonyProperties.PROPERTY_NETWORK_TYPE + "_"
                + String.valueOf(SimCardID.ID_ONE.toInt()), String.valueOf(Phone.NT_MODE_GSM_ONLY));

        if ((propNetworkTypeSim1.equals(String.valueOf(Phone.NT_MODE_WCDMA_PREF))
                || propNetworkTypeSim1.equals(String.valueOf(Phone.NT_MODE_WCDMA_ONLY)))
                && propNetworkTypeSim2.equals(String.valueOf(Phone.NT_MODE_GSM_ONLY))) {
            // SIM1 3G prefer or 3G only
            // SIM2 2G only
            return SimCardID.ID_ZERO;
        } else if (propNetworkTypeSim1.equals(String.valueOf(Phone.NT_MODE_GSM_ONLY))
                && (propNetworkTypeSim2.equals(String.valueOf(Phone.NT_MODE_WCDMA_PREF))
                || propNetworkTypeSim2.equals(String.valueOf(Phone.NT_MODE_WCDMA_ONLY)))) {
            // SIM1 2G only
            // SIM2 3G prefer or 3G only
            return SimCardID.ID_ONE;
        } else if (propNetworkTypeSim1.equals(String.valueOf(Phone.NT_MODE_GSM_ONLY))
                && propNetworkTypeSim2.equals(String.valueOf(Phone.NT_MODE_GSM_ONLY))) {
            // SIM1 2G only
            // SIM2 2G only
            Log.d(TAG, "getSimCardIdForVT(): SIM1 is 2G and SIM2 is 2G");
            return null;
        } else if ((propNetworkTypeSim1.equals(String.valueOf(Phone.NT_MODE_WCDMA_PREF))
                || propNetworkTypeSim1.equals(String.valueOf(Phone.NT_MODE_WCDMA_ONLY)))
                && (propNetworkTypeSim2.equals(String.valueOf(Phone.NT_MODE_WCDMA_PREF))
                || propNetworkTypeSim2.equals(String.valueOf(Phone.NT_MODE_WCDMA_ONLY)))) {
            // SIM1 3G prefer or 3G only
            // SIM2 3G prefer or 3G only
            // both 3G property enabled
            Log.d(TAG, "getSimCardIdForVT(): SIM1 is 3G and SIM2 is 3G");
            return SimCardID.ID_ZERO;
        } else {
            Log.e(TAG, "getSimCardIdForVT(): SIM1: " + propNetworkTypeSim1 + ", SIM2: " + propNetworkTypeSim2);
            return null;
        }
    }

    public void dialButtonPressed(SimCardID simId, boolean isVT) {
        if (isDigitsEmpty()) { // No number entered.
            if (phoneIsCdma() && phoneIsOffhook()) {
                // This is really CDMA specific. On GSM is it possible
                // to be off hook and wanted to add a 3rd party using
                // the redial feature.
                startActivity(newFlashIntent());
            } else {
                if (!TextUtils.isEmpty(mLastNumberDialed)) {
                    // Recall the last number dialed.
                    mDigits.setText(mLastNumberDialed);

                    // ...and move the cursor to the end of the digits string,
                    // so you'll be able to delete digits using the Delete
                    // button (just as if you had typed the number manually.)
                    //
                    // Note we use mDigits.getText().length() here, not
                    // mLastNumberDialed.length(), since the EditText widget now
                    // contains a *formatted* version of mLastNumberDialed (due to
                    // mTextWatcher) and its length may have changed.
                    mDigits.setSelection(mDigits.getText().length());
                } else {
                    // There's no "last number dialed" or the
                    // background query is still running. There's
                    // nothing useful for the Dial button to do in
                    // this case.  Note: with a soft dial button, this
                    // can never happens since the dial button is
                    // disabled under these conditons.
                    playTone(ToneGenerator.TONE_PROP_NACK);
                }
            }
        } else {
            final String number = mDigits.getText().toString();

            // "persist.radio.otaspdial" is a temporary hack needed for one carrier's automated
            // test equipment.
            // TODO: clean it up.
            if (number != null
                    && !TextUtils.isEmpty(mProhibitedPhoneNumberRegexp)
                    && number.matches(mProhibitedPhoneNumberRegexp)
                    && (SystemProperties.getInt("persist.radio.otaspdial", 0) != 1)) {
                Log.i(TAG, "The phone number is prohibited explicitly by a rule.");
                if (getActivity() != null) {
                    DialogFragment dialogFragment = ErrorDialogFragment.newInstance(
                                    R.string.dialog_phone_call_prohibited_message);
                    dialogFragment.show(getFragmentManager(), "phone_prohibited_dialog");
                }

                // Clear the digits just in case.
                mDigits.getText().clear();
            } else {
                if (isVT) {
                    simId = getSimCardIdForVT();
                    if (null == simId) {
                        //simId = SimCardID.ID_ZERO;
                        Toast.makeText(getActivity(), R.string.vt_not_available, Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                final Intent intent = newDialNumberIntent(number);
                if (getActivity() instanceof DialtactsActivity) {
                    intent.putExtra(DialtactsActivity.EXTRA_CALL_ORIGIN,
                            DialtactsActivity.CALL_ORIGIN_DIALTACTS);
                }

                intent.putExtra("simId", simId);
                //intent.putExtra("operation", isVT ? PREFERRED_DIAL_VIDEO : PREFERRED_DIAL_VOICE);
                startActivity(intent);
                mDigits.getText().clear();  // TODO: Fix bug 1745781
                getActivity().finish();
            }
        }
    }

    private Intent newDialNumberIntent(String number) {
        final Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                                         Uri.fromParts("tel", number, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }


    private Intent newEmergencyCallIntent(String number) {
        final Intent intent = new Intent(Intent.ACTION_CALL_EMERGENCY,
                                         Uri.fromParts("tel", number, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * In most cases, when the dial button is pressed, there is a
     * number in digits area. Pack it in the intent, start the
     * outgoing call broadcast as a separate task and finish this
     * activity.
     *
     * When there is no digit and the phone is CDMA and off hook,
     * we're sending a blank flash for CDMA. CDMA networks use Flash
     * messages when special processing needs to be done, mainly for
     * 3-way or call waiting scenarios. Presumably, here we're in a
     * special 3-way scenario where the network needs a blank flash
     * before being able to add the new participant.  (This is not the
     * case with all 3-way calls, just certain CDMA infrastructures.)
     *
     * Otherwise, there is no digit, display the last dialed
     * number. Don't finish since the user may want to edit it. The
     * user needs to press the dial button again, to dial it (general
     * case described above).
     */
    public void dialButtonPressed() {
        if (isDigitsEmpty()) { // No number entered.
            handleDialButtonClickWithEmptyDigits();
        } else {
            final String number = mDigits.getText().toString();

            // "persist.radio.otaspdial" is a temporary hack needed for one carrier's automated
            // test equipment.
            // TODO: clean it up.
            if (number != null
                    && !TextUtils.isEmpty(mProhibitedPhoneNumberRegexp)
                    && number.matches(mProhibitedPhoneNumberRegexp)
                    && (SystemProperties.getInt("persist.radio.otaspdial", 0) != 1)) {
                Log.i(TAG, "The phone number is prohibited explicitly by a rule.");
                if (getActivity() != null) {
                    DialogFragment dialogFragment = ErrorDialogFragment.newInstance(
                            R.string.dialog_phone_call_prohibited_message);
                    dialogFragment.show(getFragmentManager(), "phone_prohibited_dialog");
                }

                // Clear the digits just in case.
                mDigits.getText().clear();
            } else {
                SimCardID simCardId = SimCardID.ID_ZERO;
                int defalut_simCardId ;
                boolean popUserMenu = false;
                final boolean isEmergencySim1 = PhoneNumberUtils.isEmergencyNumber(number, SimCardID.ID_ZERO);
                final boolean isEmergencySim2 = PhoneNumberUtils.isEmergencyNumber(number, SimCardID.ID_ONE);

                defalut_simCardId = getDefaultSIMId();

                //dual sim
                if (isEmergencySim1 || isEmergencySim2) {
                    switch (getCurrentVMStatus()) {
                        case BCM_SIM_VM_BOTH: {
                            final boolean isRegistedToNetworkSim1 = isRegisterdToNetwork(SimCardID.ID_ZERO);
                            final boolean isRegistedToNetworkSim2 = isRegisterdToNetwork(SimCardID.ID_ONE);

                            if (isRegistedToNetworkSim1 && isRegistedToNetworkSim2) {
                                if(defalut_simCardId == SimCardID.ID_PROMPT.toInt())    {
                                    popUserMenu = true;
                                }
                                else if (defalut_simCardId == SimCardID.ID_ZERO.toInt()){
                                    simCardId = SimCardID.ID_ZERO;
                                }
                                else{
                                    simCardId = SimCardID.ID_ONE;
                                }
                            } else if (isRegistedToNetworkSim1) {
                                simCardId = SimCardID.ID_ZERO;
                            } else if (isRegistedToNetworkSim2) {
                                simCardId = SimCardID.ID_ONE;
                            } else popUserMenu = true;
                            break;
                        }
                        case BCM_SIM_VM_1: {
                            simCardId = SimCardID.ID_ZERO;
                            break;
                        }
                        case BCM_SIM_VM_2: {
                            simCardId = SimCardID.ID_ONE;
                            break;
                        }
                        case BCM_SIM_VM_NONE:
                        default: {
                            popUserMenu = true;
                            break;
                        }
                    }
                } else {
                    if(isSimStateReady(SimCardID.ID_ZERO) && isSimStateReady(SimCardID.ID_ONE)) {
                        if(defalut_simCardId == SimCardID.ID_PROMPT.toInt()) {
                            popUserMenu = true;
                        }
                        else if (defalut_simCardId == 0) {
                            simCardId = SimCardID.ID_ZERO;
                        }
                        else {
                            simCardId = SimCardID.ID_ONE;
                        }
                    } else if(isSimStateReady(SimCardID.ID_ZERO)) {
                        simCardId = SimCardID.ID_ZERO;
                    } else if(isSimStateReady(SimCardID.ID_ONE)) {
                        simCardId = SimCardID.ID_ONE;
                    } else {
                        simCardId = SimCardID.ID_ZERO;
                    }
                }

                final Intent intent ;

                if (isEmergencySim1 || isEmergencySim2) {
                    intent = newEmergencyCallIntent(number);
                } else {
                    intent = newDialNumberIntent(number);
                }

                if (getActivity() instanceof DialtactsActivity) {
                    String callOrigin=((DialtactsActivity)getActivity()).getCallOrigin();
                    if(callOrigin!=null) {
                        intent.putExtra(DialtactsActivity.EXTRA_CALL_ORIGIN,
                                ((DialtactsActivity)getActivity()).getCallOrigin());
                    }
                }

                if (popUserMenu) {
                    intent.setClassName("com.android.dialer", "com.android.dialer.PhoneSelect");
                    intent.setFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    startActivityForResult(intent, REQUEST_EXIT_NOTIFY);
                } else {
                    intent.putExtra("simId", simCardId);
                    startActivity(intent);
                    mClearDigitsOnStop = true;
                    getActivity().finish();
                }
            }
        }
    }

    public void clearDialpad() {
        mDigits.getText().clear();
    }

    private String getCallOrigin() {
        return (getActivity() instanceof DialtactsActivity) ?
                ((DialtactsActivity) getActivity()).getCallOrigin() : null;
    }

    private void handleDialButtonClickWithEmptyDigits() {
        if (phoneIsCdma() && phoneIsOffhook()) {
            // This is really CDMA specific. On GSM is it possible
            // to be off hook and wanted to add a 3rd party using
            // the redial feature.
            startActivity(newFlashIntent());
        } else {
            if (!TextUtils.isEmpty(mLastNumberDialed)) {
                // Recall the last number dialed.
                mDigits.setText(mLastNumberDialed);

                // ...and move the cursor to the end of the digits string,
                // so you'll be able to delete digits using the Delete
                // button (just as if you had typed the number manually.)
                //
                // Note we use mDigits.getText().length() here, not
                // mLastNumberDialed.length(), since the EditText widget now
                // contains a *formatted* version of mLastNumberDialed (due to
                // mTextWatcher) and its length may have changed.
                mDigits.setSelection(mDigits.getText().length());
            } else {
                // There's no "last number dialed" or the
                // background query is still running. There's
                // nothing useful for the Dial button to do in
                // this case.  Note: with a soft dial button, this
                // can never happens since the dial button is
                // disabled under these conditons.
                playTone(ToneGenerator.TONE_PROP_NACK);
            }
        }
    }

    /**
     * Plays the specified tone for TONE_LENGTH_MS milliseconds.
     */
    private void playTone(int tone) {
        playTone(tone, TONE_LENGTH_MS);
    }

    /**
     * Play the specified tone for the specified milliseconds
     *
     * The tone is played locally, using the audio stream for phone calls.
     * Tones are played only if the "Audible touch tones" user preference
     * is checked, and are NOT played if the device is in silent mode.
     *
     * The tone length can be -1, meaning "keep playing the tone." If the caller does so, it should
     * call stopTone() afterward.
     *
     * @param tone a tone code from {@link ToneGenerator}
     * @param durationMs tone length.
     */
    private void playTone(int tone, int durationMs) {
        // if local tone playback is disabled, just return.
        if (!mDTMFToneEnabled) {
            return;
        }

        // Also do nothing if the phone is in silent mode.
        // We need to re-check the ringer mode for *every* playTone()
        // call, rather than keeping a local flag that's updated in
        // onResume(), since it's possible to toggle silent mode without
        // leaving the current activity (via the ENDCALL-longpress menu.)
        AudioManager audioManager =
                (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if ((ringerMode == AudioManager.RINGER_MODE_SILENT)
            || (ringerMode == AudioManager.RINGER_MODE_VIBRATE)) {
            return;
        }

        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                Log.w(TAG, "playTone: mToneGenerator == null, tone: " + tone);
                return;
            }

            // Start the new tone (will stop any playing tone)
            mToneGenerator.startTone(tone, durationMs);
        }
    }

    /**
     * Stop the tone if it is played.
     */
    private void stopTone() {
        // if local tone playback is disabled, just return.
        if (!mDTMFToneEnabled) {
            return;
        }
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                Log.w(TAG, "stopTone: mToneGenerator == null");
                return;
            }
            mToneGenerator.stopTone();
        }
    }

    /**
     * Brings up the "dialpad chooser" UI in place of the usual Dialer
     * elements (the textfield/button and the dialpad underneath).
     *
     * We show this UI if the user brings up the Dialer while a call is
     * already in progress, since there's a good chance we got here
     * accidentally (and the user really wanted the in-call dialpad instead).
     * So in this situation we display an intermediate UI that lets the user
     * explicitly choose between the in-call dialpad ("Use touch tone
     * keypad") and the regular Dialer ("Add call").  (Or, the option "Return
     * to call in progress" just goes back to the in-call UI with no dialpad
     * at all.)
     *
     * @param enabled If true, show the "dialpad chooser" instead
     *                of the regular Dialer UI
     */
    private void showDialpadChooser(boolean enabled) {
        // Check if onCreateView() is already called by checking one of View objects.
        if (!isLayoutReady()) {
            return;
        }

        if (enabled) {
            // Log.i(TAG, "Showing dialpad chooser!");
            if (mDigitsContainer != null) {
                mDigitsContainer.setVisibility(View.GONE);
            } else {
                // mDigits is not enclosed by the container. Make the digits field itself gone.
                mDigits.setVisibility(View.GONE);
            }
            if (mDialpad != null) mDialpad.setVisibility(View.GONE);
            if (mDialButtonContainer != null) mDialButtonContainer.setVisibility(View.GONE);

            if (null != mDialSearchList) {
                mDialSearchList.setVisibility(View.GONE);
            }
            mDialpadChooser.setVisibility(View.VISIBLE);

            // Instantiate the DialpadChooserAdapter and hook it up to the
            // ListView.  We do this only once.
            if (mDialpadChooserAdapter == null) {
                mDialpadChooserAdapter = new DialpadChooserAdapter(getActivity());
            }
            mDialpadChooser.setAdapter(mDialpadChooserAdapter);
        } else {
            // Log.i(TAG, "Displaying normal Dialer UI.");
            if (mDigitsContainer != null) {
                mDigitsContainer.setVisibility(View.VISIBLE);
            } else {
                mDigits.setVisibility(View.VISIBLE);
            }
            if (mDialpad != null) mDialpad.setVisibility(View.VISIBLE);
            if (mDialButtonContainer != null) mDialButtonContainer.setVisibility(View.VISIBLE);

            if (null != mDialSearchList) {
                mDialSearchList.setVisibility(View.VISIBLE);
            }

            mDialpadChooser.setVisibility(View.GONE);
        }
    }

    /**
     * @return true if we're currently showing the "dialpad chooser" UI.
     */
    private boolean dialpadChooserVisible() {
        return mDialpadChooser.getVisibility() == View.VISIBLE;
    }

    /**
     * Simple list adapter, binding to an icon + text label
     * for each item in the "dialpad chooser" list.
     */
    private static class DialpadChooserAdapter extends BaseAdapter {
        private LayoutInflater mInflater;

        // Simple struct for a single "choice" item.
        static class ChoiceItem {
            String text;
            Bitmap icon;
            int id;

            public ChoiceItem(String s, Bitmap b, int i) {
                text = s;
                icon = b;
                id = i;
            }
        }

        // IDs for the possible "choices":
        static final int DIALPAD_CHOICE_USE_DTMF_DIALPAD = 101;
        static final int DIALPAD_CHOICE_RETURN_TO_CALL = 102;
        static final int DIALPAD_CHOICE_ADD_NEW_CALL = 103;

        private static final int NUM_ITEMS = 3;
        private ChoiceItem mChoiceItems[] = new ChoiceItem[NUM_ITEMS];

        public DialpadChooserAdapter(Context context) {
            // Cache the LayoutInflate to avoid asking for a new one each time.
            mInflater = LayoutInflater.from(context);

            // Initialize the possible choices.
            // TODO: could this be specified entirely in XML?

            // - "Use touch tone keypad"
            mChoiceItems[0] = new ChoiceItem(
                    context.getString(R.string.dialer_useDtmfDialpad),
                    BitmapFactory.decodeResource(context.getResources(),
                                                 R.drawable.ic_dialer_fork_tt_keypad),
                    DIALPAD_CHOICE_USE_DTMF_DIALPAD);

            // - "Return to call in progress"
            mChoiceItems[1] = new ChoiceItem(
                    context.getString(R.string.dialer_returnToInCallScreen),
                    BitmapFactory.decodeResource(context.getResources(),
                                                 R.drawable.ic_dialer_fork_current_call),
                    DIALPAD_CHOICE_RETURN_TO_CALL);

            // - "Add call"
            mChoiceItems[2] = new ChoiceItem(
                    context.getString(R.string.dialer_addAnotherCall),
                    BitmapFactory.decodeResource(context.getResources(),
                                                 R.drawable.ic_dialer_fork_add_call),
                    DIALPAD_CHOICE_ADD_NEW_CALL);
        }

        @Override
        public int getCount() {
            return NUM_ITEMS;
        }

        /**
         * Return the ChoiceItem for a given position.
         */
        @Override
        public Object getItem(int position) {
            return mChoiceItems[position];
        }

        /**
         * Return a unique ID for each possible choice.
         */
        @Override
        public long getItemId(int position) {
            return position;
        }

        /**
         * Make a view for each row.
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // When convertView is non-null, we can reuse it (there's no need
            // to reinflate it.)
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.dialpad_chooser_list_item, null);
            }

            TextView text = (TextView) convertView.findViewById(R.id.text);
            text.setText(mChoiceItems[position].text);

            ImageView icon = (ImageView) convertView.findViewById(R.id.icon);
            icon.setImageBitmap(mChoiceItems[position].icon);

            return convertView;
        }
    }

    /**
     * Handle clicks from the dialpad chooser.
     */
    public void onItemClick(AdapterView parent, View v, int position, long id) {
        if (parent == mDialSearchList) {
            entryDetailSearch();
            return;
        }
        DialpadChooserAdapter.ChoiceItem item =
                (DialpadChooserAdapter.ChoiceItem) parent.getItemAtPosition(position);
        int itemId = item.id;
        switch (itemId) {
            case DialpadChooserAdapter.DIALPAD_CHOICE_USE_DTMF_DIALPAD:
                // Log.i(TAG, "DIALPAD_CHOICE_USE_DTMF_DIALPAD");
                // Fire off an intent to go back to the in-call UI
                // with the dialpad visible.
                returnToInCallScreen(true);
                break;

            case DialpadChooserAdapter.DIALPAD_CHOICE_RETURN_TO_CALL:
                // Log.i(TAG, "DIALPAD_CHOICE_RETURN_TO_CALL");
                // Fire off an intent to go back to the in-call UI
                // (with the dialpad hidden).
                returnToInCallScreen(false);
                break;

            case DialpadChooserAdapter.DIALPAD_CHOICE_ADD_NEW_CALL:
                // Log.i(TAG, "DIALPAD_CHOICE_ADD_NEW_CALL");
                // Ok, guess the user really did want to be here (in the
                // regular Dialer) after all.  Bring back the normal Dialer UI.
                showDialpadChooser(false);
                break;

            default:
                Log.w(TAG, "onItemClick: unexpected itemId: " + itemId);
                break;
        }
    }

    /**
     * Returns to the in-call UI (where there's presumably a call in
     * progress) in response to the user selecting "use touch tone keypad"
     * or "return to call" from the dialpad chooser.
     */
    private void returnToInCallScreen(boolean showDialpad) {
        try {
            ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone1"));
            if (phone != null) phone.showCallScreenWithDialpad(showDialpad);
        } catch (RemoteException e) {
            Log.w(TAG, "phone.showCallScreenWithDialpad() failed", e);
        }

        // Finally, finish() ourselves so that we don't stay on the
        // activity stack.
        // Note that we do this whether or not the showCallScreenWithDialpad()
        // call above had any effect or not!  (That call is a no-op if the
        // phone is idle, which can happen if the current call ends while
        // the dialpad chooser is up.  In this case we can't show the
        // InCallScreen, and there's no point staying here in the Dialer,
        // so we just take the user back where he came from...)
        getActivity().finish();
    }

    /**
     * @return true if the phone is "in use", meaning that at least one line
     *              is active (ie. off hook or ringing or dialing).
     */
    public boolean phoneIsInUse() {
        return getTelephonyManager().getCallState() != TelephonyManager.CALL_STATE_IDLE;
    }

    /**
     * @return default sim id
     */
    private int getDefaultSIMId() {
        int defSimCardId = SimCardID.ID_ZERO.toInt();
        try {
            ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone1"));
            defSimCardId = phone.getDefaultSimForVoiceCalls();
        } catch (RemoteException e) {
            Log.w(TAG, "get DefaultSIMId failed", e);
        }

        return defSimCardId;
    }

    /**
     * @return true if the phone is a CDMA phone type
     */
    private boolean phoneIsCdma() {
        return getTelephonyManager().getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA;
    }

    /**
     * @return true if the phone state is OFFHOOK
     */
    private boolean phoneIsOffhook() {
        return getTelephonyManager().getCallState() == TelephonyManager.CALL_STATE_OFFHOOK;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        // R.id.menu_add_contacts already has an add to contact intent populated by setupMenuItems
        switch (item.getItemId()) {
            case R.id.menu_2s_pause:
                updateDialString(PAUSE);
                return true;
            case R.id.menu_add_wait:
                updateDialString(WAIT);
                return true;
            default:
                return false;
        }
    }

    /**
     * Updates the dial string (mDigits) after inserting a Pause character (,)
     * or Wait character (;).
     */
    private void updateDialString(char newDigit) {
        if(newDigit != WAIT && newDigit != PAUSE) {
            Log.wtf(TAG, "Not expected for anything other than PAUSE & WAIT");
            return;
        }

        int selectionStart;
        int selectionEnd;

        // SpannableStringBuilder editable_text = new SpannableStringBuilder(mDigits.getText());
        int anchor = mDigits.getSelectionStart();
        int point = mDigits.getSelectionEnd();

        selectionStart = Math.min(anchor, point);
        selectionEnd = Math.max(anchor, point);

        if (selectionStart == -1) {
            selectionStart = selectionEnd = mDigits.length();
        }

        Editable digits = mDigits.getText();

        if (canAddDigit(digits, selectionStart, selectionEnd, newDigit)) {
            digits.replace(selectionStart, selectionEnd, Character.toString(newDigit));

            if (selectionStart != selectionEnd) {
              // Unselect: back to a regular cursor, just pass the character inserted.
              mDigits.setSelection(selectionStart + 1);
            }
        }
    }

    /**
     * Update the enabledness of the "Dial" and "Backspace" buttons if applicable.
     */
    private void updateDialAndDeleteButtonEnabledState() {
        final boolean digitsNotEmpty = !isDigitsEmpty();

        if ((mDialButtonVT != null && mDialButtonSim1 != null && mDialButtonSim2 != null)||
            (mDialButtonVT != null && mDialButton != null)) {
            // On CDMA phones, if we're already on a call, we *always*
            // enable the Dial button (since you can press it without
            // entering any digits to send an empty flash.)
            if (phoneIsCdma() && phoneIsOffhook()) {
                mDialButtonVT.setEnabled(true);
                if(SystemProperties.getInt("ro.dual.sim.phone", 0) == 1) {
                    mDialButtonSim1.setEnabled(true);
                    mDialButtonSim2.setEnabled(true);
                }else {
                    mDialButton.setEnabled(true);
                }
            } else {
                // Common case: GSM, or CDMA but not on a call.
                // Enable the Dial button if some digits have
                // been entered, or if there is a last dialed number
                // that could be redialed.
                boolean enable = digitsNotEmpty || !TextUtils.isEmpty(mLastNumberDialed);
                mDialButtonVT.setEnabled(enable);
                if(SystemProperties.getInt("ro.dual.sim.phone", 0) == 1) {
                    mDialButtonSim1.setEnabled(enable);
                    mDialButtonSim2.setEnabled(enable);
                }else {
                    mDialButton.setEnabled(enable);
                }
            }
        }
        mDelete.setEnabled(digitsNotEmpty);
    }

    /**
     * Check if voicemail is enabled/accessible.
     *
     * @return true if voicemail is enabled and accessibly. Note that this can be false
     * "temporarily" after the app boot.
     * @see TelephonyManager#getVoiceMailNumber()
     */
    private boolean isVoicemailAvailable() {
        try {
            return ((TelephonyManager.getDefault(SimCardID.ID_ZERO).getVoiceMailNumber() != null)
                 || (TelephonyManager.getDefault(SimCardID.ID_ONE).getVoiceMailNumber() != null));
        } catch (SecurityException se) {
            // Possibly no READ_PHONE_STATE privilege.
            Log.w(TAG, "SecurityException is thrown. Maybe privilege isn't sufficient.");
        }
        return false;
    }

    /**
     * Returns true of the newDigit parameter can be added at the current selection
     * point, otherwise returns false.
     * Only prevents input of WAIT and PAUSE digits at an unsupported position.
     * Fails early if start == -1 or start is larger than end.
     */
    @VisibleForTesting
    /* package */ static boolean canAddDigit(CharSequence digits, int start, int end,
                                             char newDigit) {
        if(newDigit != WAIT && newDigit != PAUSE) {
            Log.wtf(TAG, "Should not be called for anything other than PAUSE & WAIT");
            return false;
        }

        // False if no selection, or selection is reversed (end < start)
        if (start == -1 || end < start) {
            return false;
        }

        // unsupported selection-out-of-bounds state
        if (start > digits.length() || end > digits.length()) return false;

        // Special digit cannot be the first digit
        if (start == 0) return false;

        if (newDigit == WAIT) {
            // preceding char is ';' (WAIT)
            if (digits.charAt(start - 1) == WAIT) return false;

            // next char is ';' (WAIT)
            if ((digits.length() > end) && (digits.charAt(end) == WAIT)) return false;
        }

        return true;
    }

    /**
     * @return true if the widget with the phone number digits is empty.
     */
    private boolean isDigitsEmpty() {
        return mDigits.length() == 0;
    }

    /**
     * Starts the asyn query to get the last dialed/outgoing
     * number. When the background query finishes, mLastNumberDialed
     * is set to the last dialed number or an empty string if none
     * exists yet.
     */
    private void queryLastOutgoingCall() {
        mLastNumberDialed = EMPTY_NUMBER;
        CallLogAsync.GetLastOutgoingCallArgs lastCallArgs =
                new CallLogAsync.GetLastOutgoingCallArgs(
                    getActivity(),
                    new CallLogAsync.OnLastOutgoingCallComplete() {
                        @Override
                        public void lastOutgoingCall(String number) {
                            // TODO: Filter out emergency numbers if
                            // the carrier does not want redial for
                            // these.
                            // If the fragment has already been detached since the last time
                            // we called queryLastOutgoingCall in onResume there is no point
                            // doing anything here.
                            if (getActivity() == null) return;
                            mLastNumberDialed = number;
                            updateDialAndDeleteButtonEnabledState();
                        }
                    });
        mCallLog.getLastOutgoingCall(lastCallArgs);
    }

    private Intent newFlashIntent() {
        final Intent intent = CallUtil.getCallIntent(EMPTY_NUMBER);
        intent.putExtra(EXTRA_SEND_EMPTY_FLASH, true);
        return intent;
    }

    private void configDialSearchList() {
        mDialSearchList.setOnItemClickListener(this);
        mDialSearchList.setOnTouchListener(this);
        mDialSearchList.setFastScrollEnabled(false);

        // Tell list view to not show dividers. We'll do it ourself so that we can *not* show
        // them when an A-Z headers is visible.
        mDialSearchList.setDividerHeight(0);

        // We manually save/restore the listview state
        mDialSearchList.setSaveEnabled(false);

        // configureVerticalScrollbar();
        configurePhotoLoader();

        mAdapter = createListAdapter();
        mAdapter.setSearchMode(true);
        mAdapter.configureDefaultPartition(false, false);
        mAdapter.setPhotoLoader(mPhotoManager);

        mDialSearchList.setAdapter(mAdapter);

        mDialSearchList.setFocusableInTouchMode(false);
    }

    private void configurePhotoLoader() {
        if (mPhotoManager == null) {
            mPhotoManager = ContactPhotoManager.getInstance(getActivity());
        }
        if (mDialSearchList != null) {
            mDialSearchList.setOnScrollListener(this);
        }
        if (mAdapter != null) {
            mAdapter.setPhotoLoader(mPhotoManager);
        }
    }

    private void entryDetailSearch() {
        if ((mListener != null) && (null != mDigits)) {
            mListener.onSearchButtonPressed(mDigits.getText().toString());
        }
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
            int totalItemCount) {
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
    }

    /**
     * Dismisses the soft keyboard when the list is touched.
     */
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        return false;
    }

    private PhoneNumberListAdapter createListAdapter() {
        PhoneNumberListAdapter adapter = new PhoneNumberListAdapter(getActivity());
        adapter.setDisplayPhotos(true);
        return adapter;
    }

    private ContactsPreferences.ChangeListener mPreferencesChangeListener =
            new ContactsPreferences.ChangeListener() {
        @Override
        public void onChange() {
            loadPreferences();
            reloadData();
        }
    };
/*
    @Override
    public void onStart() {
        super.onStart();

        if (null != mDialSearchList) {
            setDirectorySearchMode(DirectoryListLoader.SEARCH_MODE_DATA_SHORTCUT);
            mContactsPrefs.registerChangeListener(mPreferencesChangeListener);

            mForceLoad = loadPreferences();

            mDirectoryListStatus = STATUS_NOT_LOADED;
            mLoadPriorityDirectoriesOnly = true;

            startLoading();
        }
    }
*/
    @Override
    public void onStop() {
        super.onStop();
        if (mClearDigitsOnStop) {
            mClearDigitsOnStop = false;
            mDigits.getText().clear();
        }
        if (null != mDialSearchList) {
            mContactsPrefs.unregisterChangeListener();
            mAdapter.clearPartitions();
        }
    }

    private boolean loadPreferences() {
        boolean changed = false;
        if (getContactNameDisplayOrder() != mContactsPrefs.getDisplayOrder()) {
            setContactNameDisplayOrder(mContactsPrefs.getDisplayOrder());
            changed = true;
        }

        if (getSortOrder() != mContactsPrefs.getSortOrder()) {
            setSortOrder(mContactsPrefs.getSortOrder());
            changed = true;
        }

        return changed;
    }

    private int getContactNameDisplayOrder() {
        return mDisplayOrder;
    }

    private void setContactNameDisplayOrder(int displayOrder) {
        mDisplayOrder = displayOrder;
        if (mAdapter != null) {
            mAdapter.setContactNameDisplayOrder(displayOrder);
        }
    }

    private int getSortOrder() {
        return mSortOrder;
    }

    private void setSortOrder(int sortOrder) {
        mSortOrder = sortOrder;
        if (mAdapter != null) {
            mAdapter.setSortOrder(sortOrder);
        }
    }

    private void reloadData() {
        removePendingDirectorySearchRequests();
        mAdapter.onDataReload();
        mLoadPriorityDirectoriesOnly = true;
        mForceLoad = true;
        startLoading();
    }

    private void startLoading() {

        if (mAdapter == null) {
            // The method was called before the fragment was started
            return;
        }

        configureAdapter();
        int partitionCount = mAdapter.getPartitionCount();
        for (int i = 0; i < partitionCount; i++) {
            Partition partition = mAdapter.getPartition(i);
            if (partition instanceof DirectoryPartition) {
                DirectoryPartition directoryPartition = (DirectoryPartition)partition;
                if (directoryPartition.getStatus() == DirectoryPartition.STATUS_NOT_LOADED) {
                    if (directoryPartition.isPriorityDirectory() || !mLoadPriorityDirectoriesOnly) {
                        startLoadingDirectoryPartition(i);
                    }
                }
            } else {
                getLoaderManager().initLoader(i, null, this);
            }
        }

        // Next time this method is called, we should start loading non-priority directories
        mLoadPriorityDirectoriesOnly = false;
    }

    private void configureAdapter() {
        if (mAdapter == null) {
            return;
        }

        mAdapter.setQuickContactEnabled(true);
        mAdapter.setIncludeProfile(false);
        mAdapter.setQueryString(mQueryString);
        mAdapter.setDirectorySearchMode(mDirectorySearchMode);
        mAdapter.setPinnedPartitionHeadersEnabled(false);
        mAdapter.setContactNameDisplayOrder(mDisplayOrder);
        mAdapter.setSortOrder(mSortOrder);
        mAdapter.setSectionHeaderDisplayEnabled(false);
        mAdapter.setSelectionVisible(false);
        mAdapter.setDirectoryResultLimit(DEFAULT_DIRECTORY_RESULT_LIMIT);
        mAdapter.setDarkTheme(true);
        mAdapter.setPhotoPosition(ContactListItemView.PhotoPosition.LEFT);
    }

    private final String getQueryString() {
        return mQueryString;
    }

    private void setQueryString(String queryString, boolean delaySelection) {
        // Normalize the empty query.
        if (TextUtils.isEmpty(queryString)) queryString = null;

        if (!TextUtils.equals(mQueryString, queryString)) {
            mQueryString = queryString;
            setSearchMode(!TextUtils.isEmpty(mQueryString));

            if (mAdapter != null) {
                mAdapter.setQueryString(queryString);
                reloadData();
            }
        }
    }

    private final boolean isSearchMode() {
        return mSearchMode;
    }

    private void setSearchMode(boolean flag) {
        if (mSearchMode != flag) {
            mSearchMode = flag;
        }

        if (!flag) {
            mDirectoryListStatus = STATUS_NOT_LOADED;
            getLoaderManager().destroyLoader(DIRECTORY_LOADER_ID);
        }

        if (mAdapter != null) {
            mAdapter.setPinnedPartitionHeadersEnabled(false);
            mAdapter.setSearchMode(true);

            mAdapter.clearPartitions();
            if (!flag) {
                // If we are switching from search to regular display,
                // remove all directory partitions (except the default one).
                int count = mAdapter.getPartitionCount();
                for (int i = count; --i >= 1;) {
                    mAdapter.removePartition(i);
                }
            }
            mAdapter.configureDefaultPartition(false, false);
        }
    }

    private Handler mDelayedDirectorySearchHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == DIRECTORY_SEARCH_MESSAGE) {
                loadDirectoryPartition(msg.arg1, (DirectoryPartition) msg.obj);
            }
        }
    };

    private void startLoadingDirectoryPartition(int partitionIndex) {
        DirectoryPartition partition = (DirectoryPartition)mAdapter.getPartition(partitionIndex);
        partition.setStatus(DirectoryPartition.STATUS_LOADING);
        long directoryId = partition.getDirectoryId();
        if (mForceLoad) {
            if (directoryId == Directory.DEFAULT) {
                loadDirectoryPartition(partitionIndex, partition);
            } else {
                loadDirectoryPartitionDelayed(partitionIndex, partition);
            }
        } else {
            Bundle args = new Bundle();
            args.putLong(DIRECTORY_ID_ARG_KEY, directoryId);
            getLoaderManager().initLoader(partitionIndex, args, this);
        }
    }

    /**
     * Queues up a delayed request to search the specified directory. Since
     * directory search will likely introduce a lot of network traffic, we want
     * to wait for a pause in the user's typing before sending a directory request.
     */
    private void loadDirectoryPartitionDelayed(int partitionIndex, DirectoryPartition partition) {
        mDelayedDirectorySearchHandler.removeMessages(DIRECTORY_SEARCH_MESSAGE, partition);
        Message msg = mDelayedDirectorySearchHandler.obtainMessage(
                DIRECTORY_SEARCH_MESSAGE, partitionIndex, 0, partition);
        mDelayedDirectorySearchHandler.sendMessageDelayed(msg, DIRECTORY_SEARCH_DELAY_MILLIS);
    }

    /**
     * Cancels all queued directory loading requests.
     */
    private void removePendingDirectorySearchRequests() {
        mDelayedDirectorySearchHandler.removeMessages(DIRECTORY_SEARCH_MESSAGE);
    }

    /**
     * Loads the directory partition.
     */
    private void loadDirectoryPartition(int partitionIndex, DirectoryPartition partition) {
        Bundle args = new Bundle();
        args.putLong(DIRECTORY_ID_ARG_KEY, partition.getDirectoryId());
        getLoaderManager().restartLoader(partitionIndex, args, this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        if (id == DIRECTORY_LOADER_ID) {
            DirectoryListLoader loader = new DirectoryListLoader(getActivity());
            //mAdapter.configureDirectoryLoader(loader);
            return loader;
        } else {
            CursorLoader loader = createCursorLoader();
            long directoryId = args != null && args.containsKey(DIRECTORY_ID_ARG_KEY)
                    ? args.getLong(DIRECTORY_ID_ARG_KEY)
                    : Directory.DEFAULT;
            mAdapter.configureLoader(loader, directoryId);
            return loader;
        }
    }

    private CursorLoader createCursorLoader() {
        return new CursorLoader(getActivity(), null, null, null, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        int loaderId = loader.getId();
        if (loaderId == DIRECTORY_LOADER_ID) {
            mDirectoryListStatus = STATUS_LOADED;
            mAdapter.changeDirectories(data);
            startLoading();
        } else {
            onPartitionLoaded(loaderId, data);
            if (isSearchMode()) {
                int directorySearchMode = getDirectorySearchMode();
                if (directorySearchMode != DirectoryListLoader.SEARCH_MODE_NONE) {
                    if (mDirectoryListStatus == STATUS_NOT_LOADED) {
                        mDirectoryListStatus = STATUS_LOADING;
                        getLoaderManager().initLoader(DIRECTORY_LOADER_ID, null, this);
                    } else {
                        startLoading();
                    }
                }
            } else {
                mDirectoryListStatus = STATUS_NOT_LOADED;
                getLoaderManager().destroyLoader(DIRECTORY_LOADER_ID);
            }
        }
    }

    public void onLoaderReset(Loader<Cursor> loader) {
    }

    private int getDirectorySearchMode() {
        return mDirectorySearchMode;
    }

    private void setDirectorySearchMode(int mode) {
        mDirectorySearchMode = mode;
    }

    private void onPartitionLoaded(int partitionIndex, Cursor data) {
        if (partitionIndex >= mAdapter.getPartitionCount()) {
            // When we get unsolicited data, ignore it.  This could happen
            // when we are switching from search mode to the default mode.
            return;
        }

        mAdapter.changeCursor(partitionIndex, data);

        if (!isLoading()) {
            //completeRestoreInstanceState();
        }
    }

    private boolean isLoading() {
        if (mAdapter != null && mAdapter.isLoading()) {
            return true;
        }

        if (isLoadingDirectoryList()) {
            return true;
        }

        return false;
    }

    private boolean isLoadingDirectoryList() {
        return isSearchMode() && getDirectorySearchMode() != DirectoryListLoader.SEARCH_MODE_NONE
                && (mDirectoryListStatus == STATUS_NOT_LOADED
                        || mDirectoryListStatus == STATUS_LOADING);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        final DialtactsActivity activity = (DialtactsActivity) getActivity();
        if (activity == null) return;
        if (hidden) {
            activity.showSearchBar();
        } else {
            activity.hideSearchBar();
            mDigits.requestFocus();
        }
    }

    public void setAdjustTranslationForAnimation(boolean value) {
        mAdjustTranslationForAnimation = value;
    }

    public void setYFraction(float yFraction) {
        ((DialpadSlidingLinearLayout) getView()).setYFraction(yFraction);
    }
}
