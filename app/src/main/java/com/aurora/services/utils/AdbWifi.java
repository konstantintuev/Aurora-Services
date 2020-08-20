/*
 * Aurora Store
 * Copyright (C) 2019, Rahul Kumar Patel <whyorean@gmail.com>
 *
 * Aurora Store is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * Aurora Store is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Aurora Store.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package com.aurora.services.utils;

import android.content.Context;
import android.util.Log;

import java.io.*;
import java.util.Map;

public class AdbWifi {
    private static final String TAG = "SAIRoot";

    private Process mSuProcess;
    private boolean mIsAcquired = true;
    private boolean mIsTerminated;

    private BufferedWriter mWriter;
    private BufferedReader mReader;
    private BufferedReader mErrorReader;

    public ProcessBuilder buildProcessWithEnv(String cmd, File parentDir) {
        ProcessBuilder pb;
        pb = new ProcessBuilder(cmd.split(" "));

        // in our directory
        pb.directory(parentDir);
        Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", parentDir.getAbsolutePath());
        env.put("HOME", parentDir.getAbsolutePath());
        env.put("TMPDIR", parentDir.getAbsolutePath()+"/tmp");
        pb.redirectErrorStream(true);
        return pb;
    }

    public AdbWifi(Context context) {
        try {
            File outputFile = new File(context.getFilesDir(), "adb");
            if (!outputFile.exists()) {
                InputStream is = context.getAssets().open("adb");

                OutputStream out = new FileOutputStream(outputFile);
                outputFile.createNewFile();
                byte[] buffer = new byte[8 * 1024];
                int bytes = is.read(buffer);
                while (bytes >= 0) {
                    out.write(buffer, 0, bytes);
                    bytes = is.read(buffer);
                }
                out.flush();
                out.close();

                //make the executable executable with chmod
                Process chmod = Runtime.getRuntime().exec("/system/bin/chmod 777 " + outputFile);
                try {
                    chmod.waitFor();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            new File(context.getFilesDir(), "tmp").mkdir();
            /*try {
                buildProcessWithEnv( context.getFilesDir()+"/adb connect 127.0.0.1:5555", context.getFilesDir()).start().waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }*/
            int tries = 125;
            int triesNoDevices = 10;
            Boolean hasDevice = Boolean.FALSE;
            while ((hasDevice == Boolean.FALSE && tries > 0)
                    || (hasDevice == null && triesNoDevices > 0)) {
                if (!(triesNoDevices == 10 && tries == 125)) {
                    Thread.sleep(1000);
                }
                if (hasDevice == Boolean.FALSE) {
                    tries--;
                }
                if (hasDevice == null) {
                    triesNoDevices--;
                }
                Log.w(TAG, "triesNoDevices: "+triesNoDevices+" tries: "+tries);
                hasDevice = getDevices(context);
            }
            if (tries <= 0 || hasDevice == null) {
                Log.w(TAG, "failed check");
                mIsAcquired = false;
                mIsTerminated = true;
                return;
            }
            mIsAcquired = true;
            mSuProcess = buildProcessWithEnv(context.getFilesDir()+"/adb shell", context.getFilesDir()).start();
            mWriter = new BufferedWriter(new OutputStreamWriter(mSuProcess.getOutputStream()));
            mReader = new BufferedReader(new InputStreamReader(mSuProcess.getInputStream()));
            mErrorReader = new BufferedReader(new InputStreamReader(mSuProcess.getErrorStream()));
            exec("echo test");
        } catch (IOException | InterruptedException e) {
            mIsAcquired = false;
            mIsTerminated = true;
            Log.w(TAG, "Unable to acquire root access: ");
            Log.w(TAG, e);
        }
    }

    public Boolean getDevices(Context context) {
        try {
            Process proc = buildProcessWithEnv(context.getFilesDir() + "/adb devices", context.getFilesDir()).start();

            BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(proc.getInputStream()));

            BufferedReader stdError = new BufferedReader(new
                    InputStreamReader(proc.getErrorStream()));

            StringBuilder out = new StringBuilder();
// Read the output from the command
            System.out.println("Here is the standard output of the command:\n");
            String s = null;
            while ((s = stdInput.readLine()) != null) {
                System.out.println(s);
                out.append(s+"\n");
            }

// Read any errors from the attempted command
            System.out.println("Here is the standard error of the command (if any):\n");
            while ((s = stdError.readLine()) != null) {
                System.out.println(s);
                out.append(s+"\n");
            }
            String res = out.toString();
            if (!res.contains("\t")) {
                return null;
            }
            if (res.contains("\tdevice")) {
                return true;
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
        }
        return false;
    }

    public String exec(String command) {
        try {
            StringBuilder sb = new StringBuilder();
            String breaker = "『BREAKER』";//Echoed after main command and used to determine when to stop reading from the stream
            mWriter.write(command + "\necho " + breaker + "\n");
            mWriter.flush();

            char[] buffer = new char[256];
            while (true) {
                sb.append(buffer, 0, mReader.read(buffer));

                int bi = sb.indexOf(breaker);
                if (bi != -1) {
                    sb.delete(bi, bi + breaker.length());
                    break;
                }
            }

            return sb.toString().trim();
        } catch (Exception e) {
            mIsAcquired = false;
            mIsTerminated = true;
            Log.w(TAG, "Unable execute command: ");
            Log.w(TAG, e);
        }

        return null;
    }

    public String readError() {
        try {
            StringBuilder sb = new StringBuilder();
            String breaker = "『BREAKER』";
            mWriter.write("echo " + breaker + " >&2\n");
            mWriter.flush();

            char[] buffer = new char[256];
            while (true) {
                sb.append(buffer, 0, mErrorReader.read(buffer));

                int bi = sb.indexOf(breaker);
                if (bi != -1) {
                    sb.delete(bi, bi + breaker.length());
                    break;
                }
            }

            return sb.toString().trim();
        } catch (Exception e) {
            mIsAcquired = false;
            mIsTerminated = true;
            Log.w(TAG, "Unable execute command: ");
            Log.w(TAG, e);
        }

        return null;
    }

    public void terminate() {
        if (mIsTerminated)
            return;

        mIsTerminated = true;
        mSuProcess.destroy();
    }

    public boolean isTerminated() {
        return mIsTerminated;
    }

    public boolean isAcquired() {
        return mIsAcquired;
    }
}
