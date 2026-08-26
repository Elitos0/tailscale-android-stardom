// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import java.util.Objects;

/** Debug-only receiver for emulator integration authentication. */
public final class IntegrationLoginReceiver extends BroadcastReceiver {
    public static final String ACTION_LOGIN = "com.tailscale.ipn.integration.LOGIN";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Objects.equals(intent.getAction(), ACTION_LOGIN)) return;

        Data input =
                new Data.Builder()
                        .putString(
                                IntegrationLoginWorker.EXTRA_CONTROL_URL,
                                intent.getStringExtra(IntegrationLoginWorker.EXTRA_CONTROL_URL))
                        .putString(
                                IntegrationLoginWorker.EXTRA_AUTH_KEY,
                                intent.getStringExtra(IntegrationLoginWorker.EXTRA_AUTH_KEY))
                        .build();
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(IntegrationLoginWorker.class)
                        .setInputData(input)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .addTag(IntegrationLoginWorker.WORK_NAME)
                        .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        IntegrationLoginWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }
}
