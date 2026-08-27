// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package android.util;

/**
 * This is a mock class for the android.util.Log class. It is used to print log messages to the console.
 */
public class Log {
    public static int v(String tag, String msg) {
        System.out.println("VERBOSE: " + tag + ": " + msg);
        return 0;
    }

    public static int v(String tag, String msg, Throwable tr) {
        System.out.println("VERBOSE: " + tag + ": " + msg + (tr != null ? "\n" + getStackTraceString(tr) : ""));
        return 0;
    }

    public static int d(String tag, String msg) {
        System.out.println("DEBUG: " + tag + ": " + msg);
        return 0;
    }

    public static int d(String tag, String msg, Throwable tr) {
        System.out.println("DEBUG: " + tag + ": " + msg + (tr != null ? "\n" + getStackTraceString(tr) : ""));
        return 0;
    }

    public static int i(String tag, String msg) {
        System.out.println("INFO: " + tag + ": " + msg);
        return 0;
    }

    public static int i(String tag, String msg, Throwable tr) {
        System.out.println("INFO: " + tag + ": " + msg + (tr != null ? "\n" + getStackTraceString(tr) : ""));
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println("WARN: " + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        System.out.println("WARN: " + tag + ": " + msg + (tr != null ? "\n" + getStackTraceString(tr) : ""));
        return 0;
    }

    public static int w(String tag, Throwable tr) {
        System.out.println("WARN: " + tag + ": " + (tr != null ? getStackTraceString(tr) : ""));
        return 0;
    }

    public static int e(String tag, String msg) {
        System.out.println("ERROR: " + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
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
