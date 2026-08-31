// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package android.util;

/**
 * This is a mock class for the android.util.Log class. It is used to print log messages to the console.
 */
public class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    public interface LogSink {
        void onLog(int priority, String tag, String msg, Throwable tr);
    }

    public static volatile LogSink sink = null;

    public static int v(String tag, String msg) {
        if (sink != null) sink.onLog(VERBOSE, tag, msg, null);
        System.out.println("VERBOSE: " + tag + ": " + msg);
        return 0;
    }

    public static int v(String tag, String msg, Throwable tr) {
        if (sink != null) sink.onLog(VERBOSE, tag, msg, tr);
        System.out.println("VERBOSE: " + tag + ": " + msg + (tr != null ? "\n" + getStackTraceString(tr) : ""));
        return 0;
    }

    public static int d(String tag, String msg) {
        if (sink != null) sink.onLog(DEBUG, tag, msg, null);
        System.out.println("DEBUG: " + tag + ": " + msg);
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        if (sink != null) sink.onLog(DEBUG, tag, msg, tr);
        System.out.println("DEBUG: " + tag + ": " + msg + (tr != null ? "\n" + getStackTraceString(tr) : ""));
        return 0;
    }

    public static int i(String tag, String msg) {
        if (sink != null) sink.onLog(INFO, tag, msg, null);
        System.out.println("INFO: " + tag + ": " + msg);
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        if (sink != null) sink.onLog(INFO, tag, msg, tr);
        System.out.println("INFO: " + tag + ": " + msg + (tr != null ? "\n" + getStackTraceString(tr) : ""));
        return 0;
    }

    public static int w(String tag, String msg) {
        if (sink != null) sink.onLog(WARN, tag, msg, null);
        System.out.println("WARN: " + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        if (sink != null) sink.onLog(WARN, tag, msg, tr);
        System.out.println("WARN: " + tag + ": " + msg + (tr != null ? "\n" + getStackTraceString(tr) : ""));
        return 0;
    }

    public static int w(String tag, Throwable tr) {
        if (sink != null) sink.onLog(WARN, tag, "", tr);
        System.out.println("WARN: " + tag + ": " + (tr != null ? getStackTraceString(tr) : ""));
        return 0;
    }

    public static int e(String tag, String msg) {
        if (sink != null) sink.onLog(ERROR, tag, msg, null);
        System.out.println("ERROR: " + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        if (sink != null) sink.onLog(ERROR, tag, msg, tr);
        System.out.println("ERROR: " + tag + ": " + msg + (tr != null ? "\n" + getStackTraceString(tr) : ""));
        return 0;
    }

    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            tr.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch (Throwable t) {
            return tr.getClass().getName();
        }
    }

    public static int println(int priority, String tag, String msg) {
        System.out.println(tag + ": " + msg);
        return 0;
    }
}
