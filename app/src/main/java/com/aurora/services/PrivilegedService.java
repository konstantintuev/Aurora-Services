/*
 * Copyright (C) 2015-2016 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright 2007, The Android Open Source Project
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

package com.aurora.services;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import com.aurora.services.manager.LogManager;
import com.aurora.services.utils.Log;
import com.aurora.services.utils.AdbWifi;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PrivilegedService extends Service {

    public static PrivilegedService instance = null;

    private AccessProtectionHelper helper;
    private LogManager logManager;

    private IPrivilegedCallback iPrivilegedCallback;

    private final IPrivilegedService.Stub binder = new IPrivilegedService.Stub() {

        @Override
        public boolean hasPrivilegedPermissions() {
            boolean callerIsAllowed = helper.isCallerAllowed();
            return callerIsAllowed;
        }

        @Override
        public void installPackage(Uri packageURI, int flags, String installerPackageName,
                                   IPrivilegedCallback callback) {
            if (!helper.isCallerAllowed()) {
                return;
            }
            iPrivilegedCallback = callback;
            doSplitPackageStage(Collections.singletonList(packageURI));
        }

        @Override
        public void installSplitPackage(List<Uri> uriList, int flags, String installerPackageName,
                                        IPrivilegedCallback callback) {
            if (!helper.isCallerAllowed()) {
                return;
            }

            iPrivilegedCallback = callback;
            doSplitPackageStage(uriList);
        }

        @Override
        public void deletePackage(String packageName, int flags, IPrivilegedCallback callback) {

            if (!helper.isCallerAllowed()) {
                return;
            }

            adbWifi = new AdbWifi(PrivilegedService.this);
            Log.d(ensureCommandSucceeded(adbWifi.exec("pm clear " + packageName)));
            Log.d(ensureCommandSucceeded(adbWifi.exec("pm uninstall " + packageName)));
            adbWifi.terminate();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        helper = new AccessProtectionHelper(this);
        logManager = new LogManager(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private static AdbWifi adbWifi;

    private void doSplitPackageStage(List<Uri> uriList) {
        adbWifi = new AdbWifi(this);
        List<File> apkFiles = new ArrayList<>();
        for (Uri uri : uriList) {
            apkFiles.add(new File(uri.getPath()));
        }
        PackageInfo info = getApplicationContext().getPackageManager().getPackageArchiveInfo(apkFiles.get(0).getAbsolutePath(), 0);
        String packageName = info.packageName;
        try {
            int totalSize = 0;
            for (File apkFile : apkFiles)
                totalSize += apkFile.length();

            final String createSessionResult = ensureCommandSucceeded(adbWifi.exec(String.format(Locale.getDefault(),
                    "pm install-create -i com.android.vending --user %s -r -S %d",
                    "0",
                    totalSize)));

            final Pattern sessionIdPattern = Pattern.compile("(\\d+)");
            final Matcher sessionIdMatcher = sessionIdPattern.matcher(createSessionResult);
            boolean found = sessionIdMatcher.find();
            int sessionId = Integer.parseInt(sessionIdMatcher.group(1));

            for (File apkFile : apkFiles)
                ensureCommandSucceeded(adbWifi.exec(String.format(Locale.getDefault(),
                        "cat \"%s\" | pm install-write -S %d %d \"%s\"",
                        apkFile.getAbsolutePath(),
                        apkFile.length(),
                        sessionId,
                        apkFile.getName())));

            final String commitSessionResult = ensureCommandSucceeded(adbWifi.exec(String.format(Locale.getDefault(),
                    "pm install-commit %d ",
                    sessionId)));

            if (commitSessionResult.toLowerCase().contains("success")) {
                iPrivilegedCallback.handleResult(packageName, PackageInstaller.STATUS_SUCCESS);
                logManager.addToStats(packageName);
            } else {
                iPrivilegedCallback.handleResult(packageName, PackageInstaller.STATUS_FAILURE);
            }
        } catch (Exception e) {
            Log.w(e.getMessage());
            try {
                iPrivilegedCallback.handleResult(packageName, PackageInstaller.STATUS_FAILURE);
            } catch (RemoteException remoteException) {
                remoteException.printStackTrace();
            }
        }
        adbWifi.terminate();
    }

    private String ensureCommandSucceeded(String result) {
        if (result == null || result.length() == 0)
            throw new RuntimeException(adbWifi.readError());
        return result;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        adbWifi.terminate();
    }
}
