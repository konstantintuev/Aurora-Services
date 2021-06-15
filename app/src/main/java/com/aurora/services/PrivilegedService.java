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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;
import com.aurora.services.manager.LogManager;
import com.aurora.services.manager.TargetHostManager;
import com.aurora.services.model.item.HostItem;
import com.aurora.services.utils.Log;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;
import kotlin.Triple;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.*;
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
        public boolean isMoreMethodImplemented() {
            return true;
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
            doSplitPackageStage(Collections.singletonList(uri), null, packageName);
        }

        @Override
        public void installSplitPackageMore(String packageName, List<Uri> uriList, int flags, String installerPackageName, IPrivilegedCallback callback, List<String> fileList) {
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
            doSplitPackageStage(uriList, fileList, packageName);
        }

        @Override
        public void installSplitPackageX(String packageName, List<Uri> uriList, int flags, String installerPackageName, IPrivilegedCallback callback) {
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
            doSplitPackageStage(uriList, null, packageName);
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
                            HostItem hostItem = new TargetHostManager(PrivilegedService.this).getTargetHost();
                            Socket socket = new Socket(hostItem.host, hostItem.port);

                            File privateKey = new File(getFilesDir() + "/adbkey");
                            File publicKey = new File(getFilesDir() + "/adbkey.pub");
                            if (!privateKey.exists()) {
                                AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(data -> Base64.encodeToString(data, Base64.NO_WRAP));
                                crypto.saveAdbKeyPair(privateKey, publicKey);
                            }
                            AdbCrypto crypto = AdbCrypto.loadAdbKeyPair(data -> Base64.encodeToString(data, Base64.NO_WRAP),
                                    privateKey,
                                    publicKey);

                            connection = AdbConnection.create(socket, crypto);
                            connection.connect();
                            AdbStream stream = connection.open("shell:"+ "pm clear " + packageName);
                            stream.read();
                            stream.close();
                            stream = connection.open("shell:"+ "pm uninstall " + packageName);
                            String out = new String(stream.read()).trim();
                            stream.close();
                            if (out.toLowerCase().contains("success")) {
                                success = true;
                            }
                        } catch (Throwable e){
                            notifyError(e.getMessage());
                        } finally {
                            if (connection != null) {
                                try {
                                    connection.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
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
        //android.os.Debug.waitForDebugger();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private static AdbConnection connection = null;

    private void doSplitPackageStage(List<Uri> uriList, List<String> fileList, String packageName) {
        executor.execute(
                () -> {
                    try {
                        HostItem hostItem = new TargetHostManager(this).getTargetHost();
                        Socket socket = new Socket(hostItem.host, hostItem.port);

                        File privateKey = new File(getFilesDir() + "/adbkey");
                        File publicKey = new File(getFilesDir() + "/adbkey.pub");
                        if (!privateKey.exists()) {
                            AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(data -> Base64.encodeToString(data, Base64.NO_WRAP));
                            crypto.saveAdbKeyPair(privateKey, publicKey);
                        }
                        AdbCrypto crypto = AdbCrypto.loadAdbKeyPair(data -> Base64.encodeToString(data, Base64.NO_WRAP),
                                privateKey,
                                publicKey);

                        connection = AdbConnection.create(socket, crypto);
                        connection.connect();
                        ContentResolver resolver = getApplicationContext()
                                .getContentResolver();
                        //HashMap<filename, Triple<file, size, fullPath>>
                        HashMap<String, Triple<ParcelFileDescriptor, Long, String>> apkFiles = new HashMap<>();
                        int totalSize = 0;
                        for (Uri uri : uriList) {
                            Cursor returnCursor = resolver.query(uri, null, null, null, null);
                            int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
                            returnCursor.moveToFirst();
                            String readOnlyMode = "r";
                            long fileSize = returnCursor.getLong(sizeIndex);
                            String fileName = returnCursor.getString(nameIndex);
                            String fullFilePath = null;
                            if (fileList != null && !fileList.isEmpty()) {
                                for (String file : fileList) {
                                    if (new File(file).getName().equals(fileName)) {
                                        fullFilePath = file;
                                        break;
                                    }
                                }
                            }
                            apkFiles.put(fileName, new Triple(resolver.openFileDescriptor(uri, readOnlyMode), fileSize, fullFilePath));
                            totalSize += fileSize;
                            returnCursor.close();
                        }

                        AdbStream stream = connection.open("shell:"+ String.format(Locale.getDefault(),
                                "pm install-create -i com.android.vending --user %s -r -S %d",
                                "0",
                                totalSize));
                        String createSessionResult = new String(stream.read()).trim();
                        stream.close();

                        final Pattern sessionIdPattern = Pattern.compile("(\\d+)");
                        final Matcher sessionIdMatcher = sessionIdPattern.matcher(createSessionResult);
                        boolean found = sessionIdMatcher.find();
                        int sessionId = Integer.parseInt(sessionIdMatcher.group(1));

                        boolean forceUseUri = false;
                        int runs = 1;
                        while (runs > 0) {
                            for (Map.Entry<String, Triple<ParcelFileDescriptor, Long, String>> apkFile : apkFiles.entrySet()) {
                                if (!forceUseUri && apkFile.getValue().getThird() != null) {
                                    stream = connection.open("exec:" + String.format(Locale.getDefault(),
                                            "cat \"%s\" | pm install-write -S %d %d \"%s\"",
                                            apkFile.getValue().getThird(),
                                            apkFile.getValue().getSecond(),
                                            sessionId,
                                            apkFile.getKey()));
                                    String result = new String(stream.read()).trim();
                                    Log.d("pm install-write result: "+result);
                                    stream.close();
                                    if (result.contains("Permission denied")) {
                                        runs++;
                                        forceUseUri = true;
                                        break;
                                    }
                                    apkFile.getValue().getFirst().close();
                                } else {
                                    stream = connection.open("exec:" + String.format(Locale.getDefault(),
                                            "pm install-write -S %d %d \"%s\"",
                                            apkFile.getValue().getSecond(),
                                            sessionId,
                                            apkFile.getKey()));

                                    FileInputStream fis = new FileInputStream(apkFile.getValue().getFirst().getFileDescriptor());
                                    try {
                                        // larger buffer than 4 * 1024 timeouts adb and it stops the read
                                        // recommended and expected size is 1024
                                        // best performance with low risk of timeout - 2 * 1024
                                        byte[] buf = new byte[2 * 1024];
                                        while (fis.read(buf) > 0) {
                                            stream.write(buf);
                                        }
                                    } finally {
                                        stream.close();
                                        fis.close();
                                        apkFile.getValue().getFirst().close();
                                    }
                                }
                            }
                            runs--;
                        }

                        stream = connection.open("shell:"+ String.format(Locale.getDefault(),
                                "pm install-commit %d ",
                                sessionId));
                        String commitSessionResult = new String(stream.read()).trim();
                        stream.close();

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
                        if (connection != null) {
                            try {
                                connection.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (connection != null) {
            try {
                connection.close();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
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
