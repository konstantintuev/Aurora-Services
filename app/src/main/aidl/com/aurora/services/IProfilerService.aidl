package com.aurora.services;

import com.aurora.services.IProfilerCallback;

interface IProfilerService {

    boolean hasPrivilegedPermissions();

    oneway void applyProfile(in String rawProfile, in IProfilerCallback callback);
}