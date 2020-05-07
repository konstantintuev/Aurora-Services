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

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;

import com.aurora.services.manager.ProfileManager;
import com.aurora.services.utils.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class ProfilerService extends Service {

    public static ProfilerService instance = null;

    private AccessProtectionHelper helper;

    private IProfilerCallback iProfilerCallback;

    private final IProfilerService.Stub binder = new IProfilerService.Stub() {


        @Override
        public boolean hasPrivilegedPermissions() throws RemoteException {
            boolean callerIsAllowed = helper.isCallerAllowed();
            return callerIsAllowed && hasPrivilegedPermissionsImpl();
        }

        @Override
        public void applyProfile(String rawProfile, IProfilerCallback callback) throws RemoteException {

            if (!helper.isCallerAllowed()) {
                return;
            }


            iProfilerCallback = callback;

            final JsonObject profile = new Gson().fromJson(rawProfile, JsonObject.class);
            Log.e(profile.toString());
            final ProfileManager profileManager = new ProfileManager(getApplicationContext(), profile);
            final boolean result = profileManager.switchProfile();
            iProfilerCallback.handleResult(result, "");
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        helper = new AccessProtectionHelper(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private boolean hasPrivilegedPermissionsImpl() {
        boolean hasInstallPermission = getPackageManager()
                .checkPermission(Manifest.permission.INSTALL_PACKAGES, getPackageName())
                == PackageManager.PERMISSION_GRANTED;
        boolean hasDeletePermission = getPackageManager()
                .checkPermission(Manifest.permission.DELETE_PACKAGES, getPackageName())
                == PackageManager.PERMISSION_GRANTED;
        return hasInstallPermission && hasDeletePermission;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
