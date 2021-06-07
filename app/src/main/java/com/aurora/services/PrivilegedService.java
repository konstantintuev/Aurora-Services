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
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import com.aurora.services.manager.LogManager;
import com.aurora.services.utils.AdbWifi;
import com.aurora.services.utils.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PrivilegedService extends Service {

    public static PrivilegedService instance = null;

    private AccessProtectionHelper helper;
    private LogManager logManager;

    private NotificationManager notificationManager;

    private NotificationCompat.Builder importantNotificationBuilder = null;
    private Integer notificationId = 0;

    private ThreadPoolExecutor executor;

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
        }

        @Override
        public void installSplitPackage(List<Uri> listURI, int flags, String installerPackageName, IPrivilegedCallback callback) throws RemoteException {

        }

        @Override
        public void installPackageX(String packageName, Uri uri, int flags, String installerPackageName, IPrivilegedCallback callback) throws RemoteException {
            if (!helper.isCallerAllowed()) {
                try {
                    PackageInfo info = getApplicationContext().getPackageManager().getPackageArchiveInfo(new File(uri.getPath()).getAbsolutePath(), 0);
                    callback.handleResultX(info.packageName, PackageInstaller.STATUS_FAILURE, "Not whitelisted!");
                } catch (RemoteException remoteException) {
                    remoteException.printStackTrace();
                    notifyError(remoteException.getMessage());
                }
                return;
            }
            iPrivilegedCallback = callback;
            doSplitPackageStage(Collections.singletonList(uri), packageName);
        }

        @Override
        public void installSplitPackageX(String packageName, List<Uri> uriList, int flags, String installerPackageName, IPrivilegedCallback callback) throws RemoteException {
            if (!helper.isCallerAllowed()) {
                try {
                    callback.handleResultX(packageName, PackageInstaller.STATUS_FAILURE, "Not whitelisted!");
                } catch (RemoteException remoteException) {
                    remoteException.printStackTrace();
                    notifyError(remoteException.getMessage());
                }
                return;
            }

            iPrivilegedCallback = callback;
            doSplitPackageStage(uriList, packageName);
        }

        @Override
        public void deletePackage(String packageName, int flags, IPrivilegedCallback callback) {
        }

        @Override
        public void deletePackageX(String packageName, int flags, String installerPackageName, IPrivilegedCallback callback) throws RemoteException {
            if (!helper.isCallerAllowed()) {
                try {
                    callback.handleResultX(packageName, PackageInstaller.STATUS_FAILURE, "Not whitelisted!");
                } catch (RemoteException remoteException) {
                    remoteException.printStackTrace();
                }
                return;
            }

            executor.execute(
                    () -> {
                        boolean success = false;
                        try {
                            adbWifi = new AdbWifi(PrivilegedService.this);
                            Log.d(ensureCommandSucceeded(adbWifi.exec("pm clear " + packageName)));
                            Log.d(ensureCommandSucceeded(adbWifi.exec("pm uninstall " + packageName)));
                            success = true;
                        } catch (Throwable e){
                            notifyError(e.getMessage());
                        } finally {
                            if (adbWifi != null) {
                                adbWifi.terminate();
                            }
                        }
                        boolean finalSuccess = success;
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (finalSuccess) {
                                    try {
                                        callback.handleResultX(packageName, PackageInstaller.STATUS_SUCCESS, "Done!");
                                    } catch (RemoteException remoteException) {
                                        remoteException.printStackTrace();
                                    }
                                } else {
                                    try {
                                        callback.handleResultX(packageName, PackageInstaller.STATUS_FAILURE, "Command failed!");
                                    } catch (RemoteException remoteException) {
                                        remoteException.printStackTrace();
                                    }
                                }
                            }
                        });
                    });
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
        executor = new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
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

    private void doSplitPackageStage(List<Uri> uriList, String packageName) {
        executor.execute(
                () -> {
                    try {
                        adbWifi = new AdbWifi(PrivilegedService.this);
                        List<File> apkFiles = new ArrayList<>();
                        for (Uri uri : uriList) {
                            String path = Environment.getExternalStorageDirectory()
                                    + "/Aurora/Store/Downloads"
                                    + uri.getPath().split("Aurora/Store/Downloads")[1];
                            apkFiles.add(new File(path));
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

                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (commitSessionResult.toLowerCase().contains("success")) {
                                        iPrivilegedCallback.handleResultX(packageName, PackageInstaller.STATUS_SUCCESS, "Done!");
                                        logManager.addToStats(packageName);
                                    } else {
                                        iPrivilegedCallback.handleResultX(packageName, PackageInstaller.STATUS_FAILURE, "Install command failed!");
                                        notifyError(commitSessionResult);
                                    }
                                } catch (RemoteException remoteException) {
                                    remoteException.printStackTrace();
                                }
                            }
                        });
                    } catch (Throwable e) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                e.printStackTrace();
                                try {
                                    iPrivilegedCallback.handleResultX(packageName, PackageInstaller.STATUS_FAILURE, e.getMessage());
                                    notifyError(e.getMessage());
                                } catch (RemoteException remoteException) {
                                    remoteException.printStackTrace();
                                }
                                Log.w(e.getMessage());
                            }
                        });
                    } finally {
                        if (adbWifi != null) {
                            adbWifi.terminate();
                        }
                    }
                });
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
