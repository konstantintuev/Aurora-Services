package com.aurora.services;

interface IProfilerCallback {
    void handleResult(in boolean success, in String error);
}