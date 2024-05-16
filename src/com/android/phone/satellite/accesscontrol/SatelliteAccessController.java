/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.phone.satellite.accesscontrol;

import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_COMMUNICATION_ALLOWED;
import static android.telephony.satellite.SatelliteManager.KEY_SATELLITE_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
import static android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_SUCCESS;

import static com.android.internal.telephony.satellite.SatelliteController.SATELLITE_SHARED_PREF;

import android.annotation.ArrayRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.telecom.TelecomManager;
import android.telephony.AnomalyReporter;
import android.telephony.Rlog;
import android.telephony.satellite.ISatelliteCommunicationAllowedStateCallback;
import android.telephony.satellite.SatelliteManager;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyCountryDetector;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.satellite.SatelliteConfig;
import com.android.internal.telephony.satellite.SatelliteConstants;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.satellite.metrics.ConfigUpdaterMetricsStats;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.phone.PhoneGlobals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * This module is responsible for making sure that satellite communication can be used by devices
 * in only regions allowed by OEMs.
 */
public class SatelliteAccessController extends Handler {
    private static final String TAG = "SatelliteAccessController";
    /**
     * UUID to report an anomaly when getting an exception in looking up on-device data for the
     * current location.
     */
    private static final String UUID_ON_DEVICE_LOOKUP_EXCEPTION =
            "dbea1641-630e-4780-9f25-8337ba6c3563";
    /**
     * UUID to report an anomaly when getting an exception in creating the on-device access
     * controller.
     */
    private static final String UUID_CREATE_ON_DEVICE_ACCESS_CONTROLLER_EXCEPTION =
            "3ac767d8-2867-4d60-97c2-ae9d378a5521";
    protected static final long WAIT_FOR_CURRENT_LOCATION_TIMEOUT_MILLIS =
            TimeUnit.SECONDS.toMillis(180);
    protected static final long KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT_MILLIS =
            TimeUnit.MINUTES.toMillis(30);
    protected static final int DEFAULT_S2_LEVEL = 12;
    private static final int DEFAULT_LOCATION_FRESH_DURATION_SECONDS = 600;
    private static final boolean DEFAULT_SATELLITE_ACCESS_ALLOW = true;
    private static final String ALLOW_MOCK_MODEM_PROPERTY = "persist.radio.allow_mock_modem";
    private static final String BOOT_ALLOW_MOCK_MODEM_PROPERTY = "ro.boot.radio.allow_mock_modem";
    private static final boolean DEBUG = !"user".equals(Build.TYPE);
    private static final int MAX_CACHE_SIZE = 50;

    private static final int CMD_IS_SATELLITE_COMMUNICATION_ALLOWED = 1;
    protected static final int EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT = 2;
    protected static final int EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT = 3;
    protected static final int EVENT_CONFIG_DATA_UPDATED = 4;

    private static SatelliteAccessController sInstance;

    /** Feature flags to control behavior and errors. */
    @NonNull
    private final FeatureFlags mFeatureFlags;
    @GuardedBy("mLock")
    @Nullable
    protected SatelliteOnDeviceAccessController mSatelliteOnDeviceAccessController;
    @NonNull
    private final LocationManager mLocationManager;
    @NonNull
    private final TelecomManager mTelecomManager;
    @NonNull
    private final TelephonyCountryDetector mCountryDetector;
    @NonNull
    private final SatelliteController mSatelliteController;
    @NonNull
    private final ResultReceiver mInternalSatelliteSupportedResultReceiver;
    @NonNull
    protected final Object mLock = new Object();
    @GuardedBy("mLock")
    @NonNull
    private final Set<ResultReceiver> mSatelliteAllowResultReceivers = new HashSet<>();
    @NonNull
    private List<String> mSatelliteCountryCodes;
    private boolean mIsSatelliteAllowAccessControl;
    @Nullable
    private File mSatelliteS2CellFile;
    private long mLocationFreshDurationNanos;
    @GuardedBy("mLock")
    private boolean mIsOverlayConfigOverridden = false;
    @NonNull
    private List<String> mOverriddenSatelliteCountryCodes;
    private boolean mOverriddenIsSatelliteAllowAccessControl;
    @Nullable
    private File mOverriddenSatelliteS2CellFile;
    private long mOverriddenLocationFreshDurationNanos;
    @GuardedBy("mLock")
    @NonNull
    private final Map<SatelliteOnDeviceAccessController.LocationToken, Boolean>
            mCachedAccessRestrictionMap = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(
                Entry<SatelliteOnDeviceAccessController.LocationToken, Boolean> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };
    @GuardedBy("mLock")
    @Nullable
    CancellationSignal mLocationRequestCancellationSignal = null;
    private int mS2Level = DEFAULT_S2_LEVEL;
    @GuardedBy("mLock")
    @Nullable
    private Location mFreshLastKnownLocation = null;

    /** These are used for CTS test */
    private Path mCtsSatS2FilePath = null;
    protected static final String GOOGLE_US_SAN_SAT_S2_FILE_NAME = "google_us_san_sat_s2.dat";

    /** These are for config updater config data */
    private static final String SATELLITE_ACCESS_CONTROL_DATA_DIR = "satellite_access_control";
    private static final String CONFIG_UPDATER_S2_CELL_FILE_NAME = "config_updater_sat_s2.dat";
    private static final int MIN_S2_LEVEL = 0;
    private static final int MAX_S2_LEVEL = 30;
    private static final String CONFIG_UPDATER_SATELLITE_COUNTRY_CODES_KEY =
            "config_updater_satellite_country_codes";
    private static final String CONFIG_UPDATER_SATELLITE_IS_ALLOW_ACCESS_CONTROL_KEY =
            "config_updater_satellite_is_allow_access_control";
    private SharedPreferences mSharedPreferences;
    private final ConfigUpdaterMetricsStats mConfigUpdaterMetricsStats;

    /**
     * Map key: binder of the callback, value: callback to receive the satellite communication
     * allowed state changed events.
     */
    private final ConcurrentHashMap<IBinder, ISatelliteCommunicationAllowedStateCallback>
            mSatelliteCommunicationAllowedStateChangedListeners = new ConcurrentHashMap<>();
    private final Object mSatelliteCommunicationAllowStateLock = new Object();
    @GuardedBy("mSatelliteCommunicationAllowStateLock")
    private boolean mCurrentSatelliteAllowedState = false;

    /**
     * Create a SatelliteAccessController instance.
     *
     * @param context                           The context associated with the
     *                                          {@link SatelliteAccessController} instance.
     * @param featureFlags                      The FeatureFlags that are supported.
     * @param locationManager                   The LocationManager for querying current location of
     *                                          the device.
     * @param looper                            The Looper to run the SatelliteAccessController on.
     * @param satelliteOnDeviceAccessController The on-device satellite access controller instance.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected SatelliteAccessController(@NonNull Context context,
            @NonNull FeatureFlags featureFlags, @NonNull Looper looper,
            @NonNull LocationManager locationManager, @NonNull TelecomManager telecomManager,
            @Nullable SatelliteOnDeviceAccessController satelliteOnDeviceAccessController,
            @Nullable File s2CellFile) {
        super(looper);
        mFeatureFlags = featureFlags;
        mLocationManager = locationManager;
        mTelecomManager = telecomManager;
        mSatelliteOnDeviceAccessController = satelliteOnDeviceAccessController;
        mCountryDetector = TelephonyCountryDetector.getInstance(context);
        mSatelliteController = SatelliteController.getInstance();
        initSharedPreferences(context);
        loadOverlayConfigs(context);
        // loadConfigUpdaterConfigs has to be called after loadOverlayConfigs
        // since config updater config has higher priority and thus can override overlay config
        loadConfigUpdaterConfigs();
        mSatelliteController.registerForConfigUpdateChanged(this, EVENT_CONFIG_DATA_UPDATED,
                context);
        if (s2CellFile != null) {
            mSatelliteS2CellFile = s2CellFile;
        }
        mInternalSatelliteSupportedResultReceiver = new ResultReceiver(this) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                handleIsSatelliteSupportedResult(resultCode, resultData);
            }
        };
        mConfigUpdaterMetricsStats = ConfigUpdaterMetricsStats.getOrCreateInstance();
        // Init the SatelliteOnDeviceAccessController so that the S2 level can be cached
        initSatelliteOnDeviceAccessController();
    }

    private void updateCurrentSatelliteAllowedState(boolean isAllowed) {
        logd("updateCurrentSatelliteAllowedState");
        synchronized (mSatelliteCommunicationAllowStateLock) {
            if (isAllowed != mCurrentSatelliteAllowedState) {
                logd("updatedValue = " + isAllowed + " | mCurrentSatelliteAllowedState = "
                        + mCurrentSatelliteAllowedState);
                mCurrentSatelliteAllowedState = isAllowed;
                notifySatelliteCommunicationAllowedStateChanged(isAllowed);
            }
        }
    }

    /** @return the singleton instance of {@link SatelliteAccessController} */
    public static synchronized SatelliteAccessController getOrCreateInstance(
            @NonNull Context context, @NonNull FeatureFlags featureFlags) {
        if (sInstance == null) {
            HandlerThread handlerThread = new HandlerThread("SatelliteAccessController");
            handlerThread.start();
            sInstance = new SatelliteAccessController(context, featureFlags,
                    handlerThread.getLooper(), context.getSystemService(LocationManager.class),
                    context.getSystemService(TelecomManager.class), null, null);
        }
        return sInstance;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case CMD_IS_SATELLITE_COMMUNICATION_ALLOWED:
                handleCmdIsSatelliteAllowedForCurrentLocation(
                        (Pair<Integer, ResultReceiver>) msg.obj);
                break;
            case EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT:
                handleWaitForCurrentLocationTimedOutEvent();
                break;
            case EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT:
                cleanupOnDeviceAccessControllerResources();
                break;
            case EVENT_CONFIG_DATA_UPDATED:
                AsyncResult ar = (AsyncResult) msg.obj;
                updateSatelliteConfigData((Context) ar.userObj);
                break;
            default:
                logw("SatelliteAccessControllerHandler: unexpected message code: " + msg.what);
                break;
        }
    }

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param subId  The subId of the subscription to check whether satellite communication is
     *               allowed for the current location for.
     * @param result The result receiver that returns whether satellite communication is allowed
     *               for the current location if the request is successful or an error code
     *               if the request failed.
     */
    public void requestIsCommunicationAllowedForCurrentLocation(int subId,
            @NonNull ResultReceiver result) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            logd("oemEnabledSatelliteFlag is disabled");
            result.send(SATELLITE_RESULT_REQUEST_NOT_SUPPORTED, null);
            return;
        }
        sendRequestAsync(CMD_IS_SATELLITE_COMMUNICATION_ALLOWED, new Pair<>(subId, result));
    }

    /**
     * This API should be used by only CTS tests to override the overlay configs of satellite
     * access controller.
     */
    public boolean setSatelliteAccessControlOverlayConfigs(boolean reset, boolean isAllowed,
            @Nullable String s2CellFile, long locationFreshDurationNanos,
            @Nullable List<String> satelliteCountryCodes) {
        if (!isMockModemAllowed()) {
            logd("setSatelliteAccessControllerOverlayConfigs: mock modem is not allowed");
            return false;
        }
        logd("setSatelliteAccessControlOverlayConfigs: reset=" + reset
                + ", isAllowed" + isAllowed + ", s2CellFile=" + s2CellFile
                + ", locationFreshDurationNanos=" + locationFreshDurationNanos
                + ", satelliteCountryCodes=" + ((satelliteCountryCodes != null)
                ? String.join(", ", satelliteCountryCodes) : null));
        synchronized (mLock) {
            if (reset) {
                mIsOverlayConfigOverridden = false;
                cleanUpCtsResources();
            } else {
                mIsOverlayConfigOverridden = true;
                mOverriddenIsSatelliteAllowAccessControl = isAllowed;
                if (!TextUtils.isEmpty(s2CellFile)) {
                    mOverriddenSatelliteS2CellFile = getTestSatelliteS2File(s2CellFile);
                    if (!mOverriddenSatelliteS2CellFile.exists()) {
                        logd("The overriding file "
                                + mOverriddenSatelliteS2CellFile.getAbsolutePath()
                                + " does not exist");
                        mOverriddenSatelliteS2CellFile = null;
                    }
                } else {
                    mOverriddenSatelliteS2CellFile = null;
                }
                mOverriddenLocationFreshDurationNanos = locationFreshDurationNanos;
                if (satelliteCountryCodes != null) {
                    mOverriddenSatelliteCountryCodes = satelliteCountryCodes;
                } else {
                    mOverriddenSatelliteCountryCodes = new ArrayList<>();
                }
            }
            cleanupOnDeviceAccessControllerResources();
            initSatelliteOnDeviceAccessController();
        }
        return true;
    }

    protected File getTestSatelliteS2File(String fileName) {
        logd("getTestSatelliteS2File: fileName=" + fileName);
        if (TextUtils.equals(fileName, GOOGLE_US_SAN_SAT_S2_FILE_NAME)) {
            mCtsSatS2FilePath = copyTestSatS2FileToPhoneDirectory(GOOGLE_US_SAN_SAT_S2_FILE_NAME);
            if (mCtsSatS2FilePath != null) {
                return mCtsSatS2FilePath.toFile();
            } else {
                loge("getTestSatelliteS2File: mCtsSatS2FilePath is null");
            }
        }
        return new File(fileName);
    }

    @Nullable
    private static Path copyTestSatS2FileToPhoneDirectory(String sourceFileName) {
        PhoneGlobals phoneGlobals = PhoneGlobals.getInstance();
        File ctsFile = phoneGlobals.getDir("cts", Context.MODE_PRIVATE);
        if (!ctsFile.exists()) {
            ctsFile.mkdirs();
        }

        Path targetDir = ctsFile.toPath();
        Path targetSatS2FilePath = targetDir.resolve(sourceFileName);
        try {
            InputStream inputStream = phoneGlobals.getAssets().open(sourceFileName);
            if (inputStream == null) {
                loge("copyTestSatS2FileToPhoneDirectory: Resource=" + sourceFileName
                        + " not found");
            } else {
                Files.copy(inputStream, targetSatS2FilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            loge("copyTestSatS2FileToPhoneDirectory: ex=" + ex);
        }
        return targetSatS2FilePath;
    }

    @Nullable
    private static File copySatS2FileToLocalDirectory(@NonNull File sourceFile) {
        PhoneGlobals phoneGlobals = PhoneGlobals.getInstance();
        File satelliteAccessControlFile = phoneGlobals.getDir(
                SATELLITE_ACCESS_CONTROL_DATA_DIR, Context.MODE_PRIVATE);
        if (!satelliteAccessControlFile.exists()) {
            satelliteAccessControlFile.mkdirs();
        }

        Path targetDir = satelliteAccessControlFile.toPath();
        Path targetSatS2FilePath = targetDir.resolve(CONFIG_UPDATER_S2_CELL_FILE_NAME);
        try {
            InputStream inputStream = new FileInputStream(sourceFile);
            if (inputStream == null) {
                loge("copySatS2FileToPhoneDirectory: Resource=" + sourceFile.getAbsolutePath()
                        + " not found");
                return null;
            } else {
                Files.copy(inputStream, targetSatS2FilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            loge("copySatS2FileToPhoneDirectory: ex=" + ex);
            return null;
        }
        return targetSatS2FilePath.toFile();
    }

    @Nullable
    private File getConfigUpdaterSatS2CellFileFromLocalDirectory() {
        PhoneGlobals phoneGlobals = PhoneGlobals.getInstance();
        File satelliteAccessControlFile = phoneGlobals.getDir(
                SATELLITE_ACCESS_CONTROL_DATA_DIR, Context.MODE_PRIVATE);
        if (!satelliteAccessControlFile.exists()) {
            return null;
        }

        Path satelliteAccessControlFileDir = satelliteAccessControlFile.toPath();
        Path configUpdaterSatS2FilePath = satelliteAccessControlFileDir.resolve(
                CONFIG_UPDATER_S2_CELL_FILE_NAME);
        return configUpdaterSatS2FilePath.toFile();
    }

    private boolean isS2CellFileValid(@NonNull File s2CellFile) {
        try {
            SatelliteOnDeviceAccessController satelliteOnDeviceAccessController =
                    SatelliteOnDeviceAccessController.create(s2CellFile);
            int s2Level = satelliteOnDeviceAccessController.getS2Level();
            if (s2Level < MIN_S2_LEVEL || s2Level > MAX_S2_LEVEL) {
                loge("isS2CellFileValid: invalid s2 level = " + s2Level);
                satelliteOnDeviceAccessController.close();
                return false;
            }
            satelliteOnDeviceAccessController.close();
        } catch (Exception ex) {
            loge("isS2CellFileValid: Got exception in reading the file, ex=" + ex);
            return false;
        }
        return true;
    }

    private void cleanUpCtsResources() {
        if (mCtsSatS2FilePath != null) {
            try {
                Files.delete(mCtsSatS2FilePath);
            } catch (IOException ex) {
                loge("cleanUpCtsResources: ex=" + ex);
            }
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected long getElapsedRealtimeNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }

    /**
     * @param countryCodes list of country code (two letters based on the ISO 3166-1).
     * @return {@code true} if the countryCode is valid {@code false} otherwise.
     */
    private boolean isValidCountryCodes(@Nullable List<String> countryCodes) {
        if (countryCodes == null || countryCodes.isEmpty()) {
            return false;
        }
        for (String countryCode : countryCodes) {
            if (!TelephonyUtils.isValidCountryCode(countryCode)) {
                loge("invalid country code : " + countryCode);
                return false;
            }
        }
        return true;
    }

    private boolean updateSharedPreferencesCountryCodes(
            @NonNull Context context, @NonNull List<String> value) {
        if (mSharedPreferences == null) {
            logd("updateSharedPreferencesCountryCodes: mSharedPreferences is null");
            initSharedPreferences(context);
        }
        if (mSharedPreferences == null) {
            loge("updateSharedPreferencesCountryCodes: mSharedPreferences is null");
            return false;
        }
        try {
            mSharedPreferences.edit().putStringSet(
                    CONFIG_UPDATER_SATELLITE_COUNTRY_CODES_KEY, new HashSet<>(value)).apply();
            return true;
        } catch (Exception ex) {
            loge("updateSharedPreferencesCountryCodes error : " + ex);
            return false;
        }
    }

    private boolean updateSharedPreferencesIsAllowAccessControl(
            @NonNull Context context, boolean value) {
        if (mSharedPreferences == null) {
            logd("updateSharedPreferencesIsAllowAccessControl: mSharedPreferences is null");
            initSharedPreferences(context);
        }
        if (mSharedPreferences == null) {
            loge("updateSharedPreferencesIsAllowAccessControl: mSharedPreferences is null");
            return false;
        }
        try {
            mSharedPreferences.edit().putBoolean(
                    CONFIG_UPDATER_SATELLITE_IS_ALLOW_ACCESS_CONTROL_KEY,
                    value).apply();
            return true;
        } catch (Exception ex) {
            loge("updateSharedPreferencesAllowRegion : " + ex);
            return false;
        }
    }

    /**
     * Update country codes and S2CellFile with the new data from ConfigUpdater
     */
    private void updateSatelliteConfigData(Context context) {
        logd("updateSatelliteConfigData");

        SatelliteConfig satelliteConfig = mSatelliteController.getSatelliteConfig();
        if (satelliteConfig == null) {
            loge("satelliteConfig is null");
            mConfigUpdaterMetricsStats.reportOemAndCarrierConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_NO_SATELLITE_DATA);
            return;
        }

        List<String> satelliteCountryCodes = satelliteConfig.getDeviceSatelliteCountryCodes();
        if (!isValidCountryCodes(satelliteCountryCodes)) {
            logd("country codes is invalid");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_DEVICE_DATA_INVALID_COUNTRY_CODE);
            return;
        }

        Boolean isSatelliteDataForAllowedRegion = satelliteConfig.isSatelliteDataForAllowedRegion();
        if (isSatelliteDataForAllowedRegion == null) {
            loge("Satellite allowed is not configured with country codes");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_DEVICE_DATA_INVALID_S2_CELL_FILE);
            return;
        }

        File configUpdaterS2CellFile = satelliteConfig.getSatelliteS2CellFile(context);
        if (configUpdaterS2CellFile == null || !configUpdaterS2CellFile.exists()) {
            logd("No S2 cell file configured or the file does not exist");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_DEVICE_DATA_INVALID_S2_CELL_FILE);
            return;
        }

        if (!isS2CellFileValid(configUpdaterS2CellFile)) {
            loge("The configured S2 cell file is not valid");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_DEVICE_DATA_INVALID_S2_CELL_FILE);
            return;
        }

        File localS2CellFile = copySatS2FileToLocalDirectory(configUpdaterS2CellFile);
        if (localS2CellFile == null || !localS2CellFile.exists()) {
            loge("Fail to copy S2 cell file to local directory");
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_IO_ERROR);
            return;
        }

        if (!updateSharedPreferencesCountryCodes(context, satelliteCountryCodes)) {
            loge("Fail to copy country coeds into shared preferences");
            localS2CellFile.delete();
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_IO_ERROR);
            return;
        }

        if (!updateSharedPreferencesIsAllowAccessControl(
                context, isSatelliteDataForAllowedRegion.booleanValue())) {
            loge("Fail to copy allow access control into shared preferences");
            localS2CellFile.delete();
            mConfigUpdaterMetricsStats.reportOemConfigError(
                    SatelliteConstants.CONFIG_UPDATE_RESULT_IO_ERROR);
            return;
        }

        mSatelliteS2CellFile = localS2CellFile;
        mSatelliteCountryCodes = satelliteCountryCodes;
        mIsSatelliteAllowAccessControl = satelliteConfig.isSatelliteDataForAllowedRegion();
        logd("Use s2 cell file=" + mSatelliteS2CellFile.getAbsolutePath() + ", country codes="
                + String.join(",", mSatelliteCountryCodes)
                + ", mIsSatelliteAllowAccessControl=" + mIsSatelliteAllowAccessControl
                + " from ConfigUpdater");

        // Clean up resources so that the new config data will be used when serving new requests
        cleanupOnDeviceAccessControllerResources();

        // Clean up cached data based on previous geofence data
        synchronized (mLock) {
            logd("clear mCachedAccessRestrictionMap");
            mCachedAccessRestrictionMap.clear();
        }

        mConfigUpdaterMetricsStats.reportConfigUpdateSuccess();
    }

    private void loadOverlayConfigs(@NonNull Context context) {
        mSatelliteCountryCodes = getSatelliteCountryCodesFromOverlayConfig(context);
        mIsSatelliteAllowAccessControl = getSatelliteAccessAllowFromOverlayConfig(context);
        String satelliteS2CellFileName = getSatelliteS2CellFileFromOverlayConfig(context);
        mSatelliteS2CellFile = TextUtils.isEmpty(satelliteS2CellFileName)
                ? null : new File(satelliteS2CellFileName);
        if (mSatelliteS2CellFile != null && !mSatelliteS2CellFile.exists()) {
            loge("The satellite S2 cell file " + satelliteS2CellFileName + " does not exist");
            mSatelliteS2CellFile = null;
        }
        mLocationFreshDurationNanos = getSatelliteLocationFreshDurationFromOverlayConfig(context);
    }

    private void loadConfigUpdaterConfigs() {
        if (mSharedPreferences == null) {
            loge("loadConfigUpdaterConfigs : mSharedPreferences is null");
            return;
        }

        Set<String> countryCodes =
                mSharedPreferences.getStringSet(CONFIG_UPDATER_SATELLITE_COUNTRY_CODES_KEY, null);

        if (countryCodes == null || countryCodes.isEmpty()) {
            loge("config updater country codes are either null or empty");
            return;
        }

        boolean isSatelliteAllowAccessControl =
                mSharedPreferences.getBoolean(
                        CONFIG_UPDATER_SATELLITE_IS_ALLOW_ACCESS_CONTROL_KEY, true);

        File s2CellFile = getConfigUpdaterSatS2CellFileFromLocalDirectory();
        if (s2CellFile == null) {
            loge("s2CellFile is null");
            return;
        }

        logd("use config updater config data");
        mSatelliteS2CellFile = s2CellFile;
        mSatelliteCountryCodes = countryCodes.stream().collect(Collectors.toList());
        mIsSatelliteAllowAccessControl = isSatelliteAllowAccessControl;
    }

    private long getLocationFreshDurationNanos() {
        synchronized (mLock) {
            if (mIsOverlayConfigOverridden) {
                return mOverriddenLocationFreshDurationNanos;
            }
            return mLocationFreshDurationNanos;
        }
    }

    @NonNull
    private List<String> getSatelliteCountryCodes() {
        synchronized (mLock) {
            if (mIsOverlayConfigOverridden) {
                return mOverriddenSatelliteCountryCodes;
            }
            return mSatelliteCountryCodes;
        }
    }

    @Nullable
    private File getSatelliteS2CellFile() {
        synchronized (mLock) {
            if (mIsOverlayConfigOverridden) {
                return mOverriddenSatelliteS2CellFile;
            }
            return mSatelliteS2CellFile;
        }
    }

    private boolean isSatelliteAllowAccessControl() {
        synchronized (mLock) {
            if (mIsOverlayConfigOverridden) {
                return mOverriddenIsSatelliteAllowAccessControl;
            }
            return mIsSatelliteAllowAccessControl;
        }
    }

    private void handleCmdIsSatelliteAllowedForCurrentLocation(
            @NonNull Pair<Integer, ResultReceiver> requestArguments) {
        synchronized (mLock) {
            mSatelliteAllowResultReceivers.add(requestArguments.second);
            if (mSatelliteAllowResultReceivers.size() > 1) {
                logd("requestIsCommunicationAllowedForCurrentLocation is already being "
                        + "processed");
                return;
            }
            mSatelliteController.requestIsSatelliteSupported(
                    requestArguments.first, mInternalSatelliteSupportedResultReceiver);
        }
    }

    private void handleWaitForCurrentLocationTimedOutEvent() {
        logd("Timed out to wait for current location");
        synchronized (mLock) {
            if (mLocationRequestCancellationSignal != null) {
                mLocationRequestCancellationSignal.cancel();
                mLocationRequestCancellationSignal = null;
                onCurrentLocationAvailable(null);
            } else {
                loge("handleWaitForCurrentLocationTimedOutEvent: "
                        + "mLocationRequestCancellationSignal is null");
            }
        }
    }

    private void handleIsSatelliteSupportedResult(int resultCode, Bundle resultData) {
        logd("handleIsSatelliteSupportedResult: resultCode=" + resultCode);
        synchronized (mLock) {
            if (resultCode == SATELLITE_RESULT_SUCCESS) {
                if (resultData.containsKey(KEY_SATELLITE_SUPPORTED)) {
                    boolean isSatelliteSupported = resultData.getBoolean(KEY_SATELLITE_SUPPORTED);
                    if (!isSatelliteSupported) {
                        logd("Satellite is not supported");
                        Bundle bundle = new Bundle();
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_COMMUNICATION_ALLOWED,
                                false);
                        sendSatelliteAllowResultToReceivers(resultCode, bundle, false);
                    } else {
                        checkSatelliteAccessRestrictionForCurrentLocation();
                    }
                } else {
                    loge("KEY_SATELLITE_SUPPORTED does not exist.");
                    sendSatelliteAllowResultToReceivers(resultCode, resultData, false);
                }
            } else {
                sendSatelliteAllowResultToReceivers(resultCode, resultData, false);
            }
        }
    }

    private void sendSatelliteAllowResultToReceivers(int resultCode, Bundle resultData,
            boolean allowed) {
        updateCurrentSatelliteAllowedState(allowed);
        synchronized (mLock) {
            for (ResultReceiver resultReceiver : mSatelliteAllowResultReceivers) {
                resultReceiver.send(resultCode, resultData);
            }
            mSatelliteAllowResultReceivers.clear();
        }
    }

    /**
     * Telephony-internal logic to verify if satellite access is restricted at the current location.
     */
    private void checkSatelliteAccessRestrictionForCurrentLocation() {
        synchronized (mLock) {
            List<String> networkCountryIsoList = mCountryDetector.getCurrentNetworkCountryIso();
            if (!networkCountryIsoList.isEmpty()) {
                logd("Use current network country codes=" + String.join(", ",
                        networkCountryIsoList));

                boolean allowed = isSatelliteAccessAllowedForLocation(networkCountryIsoList);
                Bundle bundle = new Bundle();
                bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, allowed);
                sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle, allowed);
            } else {
                if (shouldUseOnDeviceAccessController()) {
                    // This will be an asynchronous check when it needs to wait for the current
                    // location from location service
                    checkSatelliteAccessRestrictionUsingOnDeviceData();
                } else {
                    // This is always a synchronous check
                    checkSatelliteAccessRestrictionUsingCachedCountryCodes();
                }
            }
        }
    }

    /**
     * This function synchronously checks if satellite is allowed at current location using cached
     * country codes.
     */
    private void checkSatelliteAccessRestrictionUsingCachedCountryCodes() {
        Pair<String, Long> locationCountryCodeInfo =
                mCountryDetector.getCachedLocationCountryIsoInfo();
        Map<String, Long> networkCountryCodeInfoMap =
                mCountryDetector.getCachedNetworkCountryIsoInfo();
        List<String> countryCodeList;

        // Check if the cached location country code's timestamp is newer than all cached network
        // country codes
        if (!TextUtils.isEmpty(locationCountryCodeInfo.first) && isGreaterThanAll(
                locationCountryCodeInfo.second, networkCountryCodeInfoMap.values())) {
            // Use cached location country code
            countryCodeList = Arrays.asList(locationCountryCodeInfo.first);
        } else {
            // Use cached network country codes
            countryCodeList = networkCountryCodeInfoMap.keySet().stream().toList();
        }
        logd("Use cached country codes=" + String.join(", ", countryCodeList));

        boolean allowed = isSatelliteAccessAllowedForLocation(countryCodeList);
        Bundle bundle = new Bundle();
        bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, allowed);
        sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle, allowed);
    }

    /**
     * This function asynchronously checks if satellite is allowed at the current location using
     * on-device data. Asynchronous check happens when it needs to wait for the current location
     * from location service.
     */
    private void checkSatelliteAccessRestrictionUsingOnDeviceData() {
        synchronized (mLock) {
            logd("Use on-device data");
            if (mFreshLastKnownLocation != null) {
                checkSatelliteAccessRestrictionForLocation(mFreshLastKnownLocation);
                mFreshLastKnownLocation = null;
            } else {
                Location freshLastKnownLocation = getFreshLastKnownLocation();
                if (freshLastKnownLocation != null) {
                    checkSatelliteAccessRestrictionForLocation(freshLastKnownLocation);
                } else {
                    queryCurrentLocation();
                }
            }
        }
    }

    private void queryCurrentLocation() {
        synchronized (mLock) {
            if (mLocationRequestCancellationSignal != null) {
                logd("Request for current location was already sent to LocationManager");
                return;
            }
            mLocationRequestCancellationSignal = new CancellationSignal();
            mLocationManager.getCurrentLocation(LocationManager.GPS_PROVIDER,
                    new LocationRequest.Builder(0)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .setLocationSettingsIgnored(true)
                            .build(),
                    mLocationRequestCancellationSignal, this::post,
                    this::onCurrentLocationAvailable);
            startWaitForCurrentLocationTimer();
        }
    }

    private void onCurrentLocationAvailable(@Nullable Location location) {
        logd("onCurrentLocationAvailable " + (location != null));
        synchronized (mLock) {
            stopWaitForCurrentLocationTimer();
            mLocationRequestCancellationSignal = null;
            if (location != null) {
                checkSatelliteAccessRestrictionForLocation(location);
            } else {
                checkSatelliteAccessRestrictionUsingCachedCountryCodes();
            }
        }
    }

    private void checkSatelliteAccessRestrictionForLocation(@NonNull Location location) {
        synchronized (mLock) {
            try {
                SatelliteOnDeviceAccessController.LocationToken locationToken =
                        SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                                location.getLatitude(),
                                location.getLongitude(), mS2Level);
                boolean satelliteAllowed;
                if (mCachedAccessRestrictionMap.containsKey(locationToken)) {
                    satelliteAllowed = mCachedAccessRestrictionMap.get(locationToken);
                } else {
                    if (!initSatelliteOnDeviceAccessController()) {
                        loge("Failed to init SatelliteOnDeviceAccessController");
                        checkSatelliteAccessRestrictionUsingCachedCountryCodes();
                        return;
                    }
                    satelliteAllowed = mSatelliteOnDeviceAccessController
                            .isSatCommunicationAllowedAtLocation(locationToken);
                    updateCachedAccessRestrictionMap(locationToken, satelliteAllowed);
                }
                Bundle bundle = new Bundle();
                bundle.putBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED, satelliteAllowed);
                sendSatelliteAllowResultToReceivers(SATELLITE_RESULT_SUCCESS, bundle,
                        satelliteAllowed);
            } catch (Exception ex) {
                loge("checkSatelliteAccessRestrictionForLocation: ex=" + ex);
                reportAnomaly(UUID_ON_DEVICE_LOOKUP_EXCEPTION,
                        "On-device satellite lookup exception");
                checkSatelliteAccessRestrictionUsingCachedCountryCodes();
            }
        }
    }

    private void updateCachedAccessRestrictionMap(
            @NonNull SatelliteOnDeviceAccessController.LocationToken locationToken,
            boolean satelliteAllowed) {
        synchronized (mLock) {
            mCachedAccessRestrictionMap.put(locationToken, satelliteAllowed);
        }
    }

    private boolean isGreaterThanAll(
            long comparedItem, @NonNull Collection<Long> itemCollection) {
        for (long item : itemCollection) {
            if (comparedItem <= item) return false;
        }
        return true;
    }

    private boolean isSatelliteAccessAllowedForLocation(
            @NonNull List<String> networkCountryIsoList) {
        if (isSatelliteAllowAccessControl()) {
            // The current country is unidentified, we're uncertain and thus returning false
            if (networkCountryIsoList.isEmpty()) {
                return false;
            }

            // In case of allowed list, satellite is allowed if all country codes are be in the
            // allowed list
            return getSatelliteCountryCodes().containsAll(networkCountryIsoList);
        } else {
            // No country is barred, thus returning true
            if (getSatelliteCountryCodes().isEmpty()) {
                return true;
            }

            // The current country is unidentified, we're uncertain and thus returning false
            if (networkCountryIsoList.isEmpty()) {
                return false;
            }

            // In case of disallowed list, if any country code is in the list, satellite will be
            // disallowed
            for (String countryCode : networkCountryIsoList) {
                if (getSatelliteCountryCodes().contains(countryCode)) {
                    return false;
                }
            }
            return true;
        }
    }

    private boolean shouldUseOnDeviceAccessController() {
        if (getSatelliteS2CellFile() == null) {
            return false;
        }

        if (isInEmergency() || mLocationManager.isLocationEnabled()) {
            return true;
        }

        Location freshLastKnownLocation = getFreshLastKnownLocation();
        if (freshLastKnownLocation != null) {
            synchronized (mLock) {
                mFreshLastKnownLocation = freshLastKnownLocation;
            }
            return true;
        } else {
            synchronized (mLock) {
                mFreshLastKnownLocation = null;
            }
        }
        return false;
    }

    @Nullable
    private Location getFreshLastKnownLocation() {
        Location lastKnownLocation = getLastKnownLocation();
        if (lastKnownLocation != null) {
            long lastKnownLocationAge =
                    getElapsedRealtimeNanos() - lastKnownLocation.getElapsedRealtimeNanos();
            if (lastKnownLocationAge <= getLocationFreshDurationNanos()) {
                return lastKnownLocation;
            }
        }
        return null;
    }

    private boolean isInEmergency() {
        // Check if emergency call is ongoing
        if (mTelecomManager.isInEmergencyCall()) {
            return true;
        }
        // Check if the device is in emergency callback mode
        for (Phone phone : PhoneFactory.getPhones()) {
            if (phone.isInEcm()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private Location getLastKnownLocation() {
        Location result = null;
        for (String provider : mLocationManager.getProviders(true)) {
            Location location = mLocationManager.getLastKnownLocation(provider);
            if (location != null && (result == null
                    || result.getElapsedRealtimeNanos() < location.getElapsedRealtimeNanos())) {
                result = location;
            }
        }
        return result;
    }

    private void initSharedPreferences(@NonNull Context context) {
        try {
            mSharedPreferences =
                    context.getSharedPreferences(SATELLITE_SHARED_PREF, Context.MODE_PRIVATE);
        } catch (Exception e) {
            loge("Cannot get default shared preferences: " + e);
        }
    }

    /**
     * @return {@code true} if successfully initialize the {@link SatelliteOnDeviceAccessController}
     * instance, {@code false} otherwise.
     * @throws IllegalStateException in case of getting any exception in creating the
     *                               {@link SatelliteOnDeviceAccessController} instance and the
     *                               device is using a user build.
     */
    private boolean initSatelliteOnDeviceAccessController() throws IllegalStateException {
        synchronized (mLock) {
            if (getSatelliteS2CellFile() == null) return false;

            // mSatelliteOnDeviceAccessController was already initialized successfully
            if (mSatelliteOnDeviceAccessController != null) {
                restartKeepOnDeviceAccessControllerResourcesTimer();
                return true;
            }

            try {
                mSatelliteOnDeviceAccessController =
                        SatelliteOnDeviceAccessController.create(getSatelliteS2CellFile());
                restartKeepOnDeviceAccessControllerResourcesTimer();
                mS2Level = mSatelliteOnDeviceAccessController.getS2Level();
                logd("mS2Level=" + mS2Level);
            } catch (Exception ex) {
                loge("Got exception in creating an instance of SatelliteOnDeviceAccessController,"
                        + " ex=" + ex + ", sat s2 file="
                        + getSatelliteS2CellFile().getAbsolutePath());
                reportAnomaly(UUID_CREATE_ON_DEVICE_ACCESS_CONTROLLER_EXCEPTION,
                        "Exception in creating on-device satellite access controller");
                mSatelliteOnDeviceAccessController = null;
                if (!mIsOverlayConfigOverridden) {
                    mSatelliteS2CellFile = null;
                }
                return false;
            }
            return true;
        }
    }

    private void cleanupOnDeviceAccessControllerResources() {
        synchronized (mLock) {
            logd("cleanupOnDeviceAccessControllerResources="
                    + (mSatelliteOnDeviceAccessController != null));
            if (mSatelliteOnDeviceAccessController != null) {
                try {
                    mSatelliteOnDeviceAccessController.close();
                } catch (Exception ex) {
                    loge("cleanupOnDeviceAccessControllerResources: ex=" + ex);
                }
                mSatelliteOnDeviceAccessController = null;
                stopKeepOnDeviceAccessControllerResourcesTimer();
            }
        }
    }

    private static boolean getSatelliteAccessAllowFromOverlayConfig(@NonNull Context context) {
        Boolean accessAllowed = null;
        try {
            accessAllowed = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_oem_enabled_satellite_access_allow);
        } catch (Resources.NotFoundException ex) {
            loge("getSatelliteAccessAllowFromOverlayConfig: got ex=" + ex);
        }
        if (accessAllowed == null && isMockModemAllowed()) {
            logd("getSatelliteAccessAllowFromOverlayConfig: Read "
                    + "config_oem_enabled_satellite_access_allow from device config");
            accessAllowed = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_oem_enabled_satellite_access_allow", DEFAULT_SATELLITE_ACCESS_ALLOW);
        }
        if (accessAllowed == null) {
            logd("Use default satellite access allow=true control");
            accessAllowed = true;
        }
        return accessAllowed;
    }

    @Nullable
    private static String getSatelliteS2CellFileFromOverlayConfig(@NonNull Context context) {
        String s2CellFile = null;
        try {
            s2CellFile = context.getResources().getString(
                    com.android.internal.R.string.config_oem_enabled_satellite_s2cell_file);
        } catch (Resources.NotFoundException ex) {
            loge("getSatelliteS2CellFileFromOverlayConfig: got ex=" + ex);
        }
        if (TextUtils.isEmpty(s2CellFile) && isMockModemAllowed()) {
            logd("getSatelliteS2CellFileFromOverlayConfig: Read "
                    + "config_oem_enabled_satellite_s2cell_file from device config");
            s2CellFile = DeviceConfig.getString(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_oem_enabled_satellite_s2cell_file", null);
        }
        logd("s2CellFile=" + s2CellFile);
        return s2CellFile;
    }

    @NonNull
    private static List<String> getSatelliteCountryCodesFromOverlayConfig(
            @NonNull Context context) {
        String[] countryCodes = readStringArrayFromOverlayConfig(context,
                com.android.internal.R.array.config_oem_enabled_satellite_country_codes);
        if (countryCodes.length == 0 && isMockModemAllowed()) {
            logd("getSatelliteCountryCodesFromOverlayConfig: Read "
                    + "config_oem_enabled_satellite_country_codes from device config");
            String countryCodesStr = DeviceConfig.getString(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_oem_enabled_satellite_country_codes", "");
            countryCodes = countryCodesStr.split(",");
        }
        return Arrays.stream(countryCodes)
                .map(x -> x.toUpperCase(Locale.US))
                .collect(Collectors.toList());
    }

    @NonNull
    private static String[] readStringArrayFromOverlayConfig(
            @NonNull Context context, @ArrayRes int id) {
        String[] strArray = null;
        try {
            strArray = context.getResources().getStringArray(id);
        } catch (Resources.NotFoundException ex) {
            loge("readStringArrayFromOverlayConfig: id= " + id + ", ex=" + ex);
        }
        if (strArray == null) {
            strArray = new String[0];
        }
        return strArray;
    }

    private static long getSatelliteLocationFreshDurationFromOverlayConfig(
            @NonNull Context context) {
        Integer freshDuration = null;
        try {
            freshDuration = context.getResources().getInteger(com.android.internal.R.integer
                    .config_oem_enabled_satellite_location_fresh_duration);
        } catch (Resources.NotFoundException ex) {
            loge("getSatelliteLocationFreshDurationFromOverlayConfig: got ex=" + ex);
        }
        if (freshDuration == null && isMockModemAllowed()) {
            logd("getSatelliteLocationFreshDurationFromOverlayConfig: Read "
                    + "config_oem_enabled_satellite_location_fresh_duration from device config");
            freshDuration = DeviceConfig.getInt(DeviceConfig.NAMESPACE_TELEPHONY,
                    "config_oem_enabled_satellite_location_fresh_duration",
                    DEFAULT_LOCATION_FRESH_DURATION_SECONDS);
        }
        if (freshDuration == null) {
            logd("Use default satellite location fresh duration="
                    + DEFAULT_LOCATION_FRESH_DURATION_SECONDS);
            freshDuration = DEFAULT_LOCATION_FRESH_DURATION_SECONDS;
        }
        return TimeUnit.SECONDS.toNanos(freshDuration);
    }

    private void startWaitForCurrentLocationTimer() {
        synchronized (mLock) {
            if (hasMessages(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT)) {
                logw("WaitForCurrentLocationTimer is already started");
                removeMessages(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT);
            }
            sendEmptyMessageDelayed(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT,
                    WAIT_FOR_CURRENT_LOCATION_TIMEOUT_MILLIS);
        }
    }

    private void stopWaitForCurrentLocationTimer() {
        synchronized (mLock) {
            removeMessages(EVENT_WAIT_FOR_CURRENT_LOCATION_TIMEOUT);
        }
    }

    private void restartKeepOnDeviceAccessControllerResourcesTimer() {
        synchronized (mLock) {
            if (hasMessages(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT)) {
                logd("KeepOnDeviceAccessControllerResourcesTimer is already started. "
                        + "Restarting it...");
                removeMessages(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT);
            }
            sendEmptyMessageDelayed(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT,
                    KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT_MILLIS);
        }
    }

    private void stopKeepOnDeviceAccessControllerResourcesTimer() {
        synchronized (mLock) {
            removeMessages(EVENT_KEEP_ON_DEVICE_ACCESS_CONTROLLER_RESOURCES_TIMEOUT);
        }
    }

    private void reportAnomaly(@NonNull String uuid, @NonNull String log) {
        loge(log);
        AnomalyReporter.reportAnomaly(UUID.fromString(uuid), log);
    }

    private static boolean isMockModemAllowed() {
        return (DEBUG || SystemProperties.getBoolean(ALLOW_MOCK_MODEM_PROPERTY, false)
                || SystemProperties.getBoolean(BOOT_ALLOW_MOCK_MODEM_PROPERTY, false));
    }

    /**
     * Posts the specified command to be executed on the main thread and returns immediately.
     *
     * @param command  command to be executed on the main thread
     * @param argument additional parameters required to perform of the operation
     */
    private void sendRequestAsync(int command, @NonNull Object argument) {
        Message msg = this.obtainMessage(command, argument);
        msg.sendToTarget();
    }

    /**
     * Registers for the satellite communication allowed state changed.
     *
     * @param subId    The subId of the subscription to register for the satellite communication
     *                 allowed state changed.
     * @param callback The callback to handle the satellite communication allowed state changed
     *                 event.
     * @return The {@link SatelliteManager.SatelliteResult} result of the operation.
     */
    @SatelliteManager.SatelliteResult
    public int registerForCommunicationAllowedStateChanged(int subId,
            @NonNull ISatelliteCommunicationAllowedStateCallback callback) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            logd("registerForCommunicationAllowedStateChanged: oemEnabledSatelliteFlag is "
                    + "disabled");
            return SatelliteManager.SATELLITE_RESULT_REQUEST_NOT_SUPPORTED;
        }

        mSatelliteCommunicationAllowedStateChangedListeners.put(callback.asBinder(), callback);
        return SATELLITE_RESULT_SUCCESS;
    }

    /**
     * Unregisters for the satellite communication allowed state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param subId    The subId of the subscription to unregister for the satellite communication
     *                 allowed state changed.
     * @param callback The callback that was passed to
     *                 {@link #registerForCommunicationAllowedStateChanged(int,
     *                 ISatelliteCommunicationAllowedStateCallback)}.
     */
    public void unregisterForCommunicationAllowedStateChanged(
            int subId, @NonNull ISatelliteCommunicationAllowedStateCallback callback) {
        if (!mFeatureFlags.oemEnabledSatelliteFlag()) {
            logd("unregisterForCommunicationAllowedStateChanged: "
                    + "oemEnabledSatelliteFlag is disabled");
            return;
        }

        mSatelliteCommunicationAllowedStateChangedListeners.remove(callback.asBinder());
    }

    private void notifySatelliteCommunicationAllowedStateChanged(boolean allowState) {
        logd("notifySatelliteCommunicationAllowedStateChanged: allowState=" + allowState);

        List<ISatelliteCommunicationAllowedStateCallback> deadCallersList = new ArrayList<>();
        mSatelliteCommunicationAllowedStateChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteCommunicationAllowedStateChanged(allowState);
            } catch (RemoteException e) {
                logd("handleEventNtnSignalStrengthChanged RemoteException: " + e);
                deadCallersList.add(listener);
            }
        });
        deadCallersList.forEach(listener -> {
            mSatelliteCommunicationAllowedStateChangedListeners.remove(listener.asBinder());
        });
    }

    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    private static void logw(@NonNull String log) {
        Rlog.w(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Rlog.e(TAG, log);
    }
}
