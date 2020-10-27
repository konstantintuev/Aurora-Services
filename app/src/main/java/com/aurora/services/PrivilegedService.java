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

import android.app.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.aurora.services.activities.AuroraActivity;
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

    private NotificationManager notificationManager;

    private NotificationCompat.Builder importantNotificationBuilder = null;
    private Integer notificationId = 0;

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
                try {
                    PackageInfo info = getApplicationContext().getPackageManager().getPackageArchiveInfo(new File(packageURI.getPath()).getAbsolutePath(), 0);
                    callback.handleResult(info.packageName, PackageInstaller.STATUS_FAILURE);
                } catch (RemoteException remoteException) {
                    remoteException.printStackTrace();
                    notifyError(remoteException.getMessage());
                }
                return;
            }
            iPrivilegedCallback = callback;
            doSplitPackageStage(Collections.singletonList(packageURI));
        }

        @Override
        public void installSplitPackage(List<Uri> uriList, int flags, String installerPackageName,
                                        IPrivilegedCallback callback) {
            if (!helper.isCallerAllowed()) {
                try {
                    String packageName = "";
                    for (Uri uri: uriList){
                        PackageInfo info = getApplicationContext().getPackageManager().getPackageArchiveInfo(new File(uri.getPath()).getAbsolutePath(), 0);
                        if (info == null){ continue; }
                        packageName = info.packageName;
                        break;
                    }
                    callback.handleResult(packageName, PackageInstaller.STATUS_FAILURE);
                } catch (RemoteException remoteException) {
                    remoteException.printStackTrace();
                    notifyError(remoteException.getMessage());
                }
                return;
            }

            iPrivilegedCallback = callback;
            doSplitPackageStage(uriList);
        }

        @Override
        public void deletePackage(String packageName, int flags, IPrivilegedCallback callback) {

            if (!helper.isCallerAllowed()) {
                try {
                    callback.handleResult(packageName, PackageInstaller.STATUS_FAILURE);
                } catch (RemoteException remoteException) {
                    remoteException.printStackTrace();
                }
                return;
            }

            adbWifi = new AdbWifi(PrivilegedService.this);
            try {
                Log.d(ensureCommandSucceeded(adbWifi.exec("pm clear " + packageName)));
                Log.d(ensureCommandSucceeded(adbWifi.exec("pm uninstall " + packageName)));
            } catch (Throwable e){
                notifyError(e.getMessage());
            } finally {
                adbWifi.terminate();
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        helper = new AccessProtectionHelper(this);
        logManager = new LogManager(this);
        Intent settingsIntent = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager = getSystemService(NotificationManager.class);


            NotificationChannel channel = new NotificationChannel("service", "Installer service (hide if you want)", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Hide this service as it doesn't show important information");
            notificationManager.createNotificationChannel(channel);

            channel = new NotificationChannel("main", "Important notifications", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Important status/error notifications");
            channel.enableVibration(true);
            channel.enableLights(true);
            notificationManager.createNotificationChannel(channel);

            settingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "service");

            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            importantNotificationBuilder = new NotificationCompat.Builder(this, channel.getId())
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);
        } else {
            settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            settingsIntent.setData(uri);
        }
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 3243333, settingsIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        Notification notification =
                new NotificationCompat.Builder(this, "service")
                        .setContentTitle("Running install service (Aurora)")
                        .setContentText("Click to hide")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentIntent(pendingIntent)
                        .build();



        startForeground(3242, notification);
        android.os.Debug.waitForDebugger();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private static AdbWifi adbWifi;

    private void doSplitPackageStage(List<Uri> uriList) {
        adbWifi = new AdbWifi(this);
        String packageName = "";
        try {
            List<File> apkFiles = new ArrayList<>();
            for (Uri uri : uriList) {
                apkFiles.add(new File(uri.getPath()));
            }
            for (File apkFile: apkFiles){
                PackageInfo info = getApplicationContext().getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
                if (info == null){ continue; }
                packageName = info.packageName;
                break;
            }
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
                notifyError(commitSessionResult);
            }
        } catch (Throwable e) {
            e.printStackTrace();
            try {
                iPrivilegedCallback.handleResult(packageName, PackageInstaller.STATUS_FAILURE);
                notifyError(e.getMessage());
            } catch (RemoteException remoteException) {
                remoteException.printStackTrace();
            }
            Log.w(e.getMessage());
        } finally {
            adbWifi.terminate();
        }
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

    private void notifyError(String error){
        if (importantNotificationBuilder != null){
            Intent notificationIntent = new Intent(Intent.ACTION_VIEW);
            notificationIntent.setData(Uri.parse("https://www.google.com/search?q=" + error));
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            final PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                    PendingIntent.FLAG_ONE_SHOT);
            Notification notify = importantNotificationBuilder
                    .setContentTitle("Aurora Services Error (Tap to search)")
                    .setContentText(error)
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(error))
                    .setContentIntent(pendingIntent)
                    .build();
            notificationManager.notify(notificationId++, notify);
        }

    }
}
