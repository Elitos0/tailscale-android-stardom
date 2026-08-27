// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.tailscale.ipn.util.TSLog;
/**
 * A worker that exists to support IPNReceiver.
 */
public final class StopVPNWorker extends Worker {

    public StopVPNWorker(
            Context appContext,
            WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        TSLog.d("VpnLifecycle", "StopVPNWorker.doWork executing");
        try {
            UninitializedApp.get().stopVPN();
            TSLog.d("VpnLifecycle", "StopVPNWorker.doWork stopVPN completed successfully");
            return Result.success();
        } catch (Exception e) {
            TSLog.e("VpnLifecycle", "StopVPNWorker.doWork stopVPN failed: " + e.getMessage(), e);
            return Result.failure();
        }
    }
}
