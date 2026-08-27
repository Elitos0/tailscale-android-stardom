// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tailscale.ipn.product.policy.VpnStartDispatchResult
import com.tailscale.ipn.product.policy.VpnStartOrigin
import com.tailscale.ipn.util.TSLog

/** A package-scoped receiver helper that still passes the application entitlement boundary. */
class StartVPNWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    TSLog.d("VpnLifecycle", "StartVPNWorker.doWork executing")
    try {
      val app = App.get()
      val prepared = VpnService.prepare(app) == null
      val ableToStart = app.isAbleToStartVPN()
      TSLog.d(
          "VpnLifecycle",
          "StartVPNWorker worker start check: ableToStart=$ableToStart prepared=$prepared")
      if (ableToStart && prepared) {
        when (val dispatch = app.startVPNIfAuthorized(VpnStartOrigin.InternalWorker)) {
          VpnStartDispatchResult.Dispatched -> {
            TSLog.d("VpnLifecycle", "StartVPNWorker VPN start dispatched successfully")
            return Result.success()
          }
          VpnStartDispatchResult.Denied ->
              TSLog.e("VpnLifecycle", "StartVPNWorker worker start denied by entitlement")
          is VpnStartDispatchResult.Failed ->
              TSLog.e(
                  "VpnLifecycle",
                  "StartVPNWorker worker start dispatch failed: ${dispatch.error.message}",
                  dispatch.error)
        }
      } else {
        TSLog.e(
            "VpnLifecycle",
            "StartVPNWorker worker start unavailable ableToStart=$ableToStart prepared=$prepared")
      }

      TSLog.e("StartVPNWorker", "VPN start failed; user interaction required")
      val notificationManager =
          app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channelId = "start_vpn_channel"
      app.createNotificationChannel(
          channelId,
          applicationContext.getString(R.string.vpn_start),
          applicationContext.getString(
              R.string
                  .notifications_delivered_when_user_interaction_is_required_to_establish_the_vpn_tunnel),
          NotificationManager.IMPORTANCE_HIGH,
      )
      val intent =
          checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
          }
      val pendingIntentFlags =
          PendingIntent.FLAG_ONE_SHOT or
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE
              else 0
      val pendingIntent = PendingIntent.getActivity(app, 0, intent, pendingIntentFlags)
      val notification =
          Notification.Builder(app, channelId)
              .setContentTitle(app.getString(R.string.title_connection_failed))
              .setContentText(app.getString(R.string.body_open_tailscale))
              .setSmallIcon(R.drawable.ic_notification)
              .setContentIntent(pendingIntent)
              .setAutoCancel(true)
              .build()
      notificationManager.notify(1, notification)
      return Result.failure()
    } catch (e: Exception) {
      TSLog.e("VpnLifecycle", "StartVPNWorker unexpected error: ${e.message}", e)
      return Result.failure()
    }
  }
}
