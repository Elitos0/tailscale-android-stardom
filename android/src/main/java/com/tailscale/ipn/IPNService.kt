// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import android.system.OsConstants
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.product.policy.VpnServiceRunCoordinator
import com.tailscale.ipn.product.policy.VpnServiceStartRejectionBoundary
import com.tailscale.ipn.product.policy.VpnStartOrigin
import com.tailscale.ipn.product.policy.VpnStopCommandRegistration
import com.tailscale.ipn.product.policy.VpnWantRunningWriter
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import libtailscale.Libtailscale

open class IPNService : VpnService(), libtailscale.IPNService {
  private val TAG = "IPNService"
  private val randomID: String = UUID.randomUUID().toString()
  private lateinit var app: App
  private lateinit var runCoordinator: VpnServiceRunCoordinator
  private lateinit var startRejectionBoundary: VpnServiceStartRejectionBoundary
  private lateinit var stopCommandRegistration: VpnStopCommandRegistration
  private val serviceJob = SupervisorJob()
  private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
  private val closed = AtomicBoolean(false)
  private val coordinatorLock = Any()
  @Volatile private var isDestroyed = false
  private var pendingStartOrigin: VpnStartOrigin? = null


  override fun id(): String {
    return randomID
  }

  override fun updateVpnStatus(status: Boolean) {
    app.getAppScopedViewModel().setVpnActive(status)
    runCoordinator.updateVpnStatus(status)
  }

  override fun onCreate() {
    super.onCreate()
    // grab app to make sure it initializes
    app = App.get()
    app.activeIpnService = this
    runCoordinator = createRunCoordinator()
    startRejectionBoundary =
        VpnServiceStartRejectionBoundary(app.vpnEntitlementController::revokeRejectedRuntimeStart)
    stopCommandRegistration = app.vpnStopCommandDispatcher.register(::handleStopCommand)
    NetworkChangeCallback.setUnderlyingNetworkListener { network ->
      updateUnderlyingNetwork(network)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_VPN -> {
        handleStopCommand()
      }
      ACTION_START_MONITOR -> {
        showForegroundNotification()
      }
      ACTION_RESTART_VPN -> {
        scope.launch {
          if (!app.vpnEntitlementController.authorizeStart(VpnStartOrigin.ServiceRestart)) {
            rejectRuntimeStart()
            return@launch
          }
          app.setWantRunning(
              false,
              onSuccess = {
                if (!closed.get()) {
                  close()
                  app.startVPN()
                }
              },
              onFailure = { rejectRuntimeStart() },
          )
        }
      }
      ACTION_START_FOREGROUND_ONLY -> {
        // Start the foreground service notification without creating a VPN tunnel.
        // This is used during interactive login so that Android does not freeze the process
        // or restrict network access while the user completes auth in the browser.
        showForegroundNotification()
      }
      ACTION_START_VPN -> {
        val originName = intent.getStringExtra(EXTRA_ORIGIN)
        val origin =
            originName?.let { runCatching { VpnStartOrigin.valueOf(it) }.getOrNull() }
                ?: VpnStartOrigin.ServiceStart
        showForegroundNotification()
        authorizeAndRequestVpn(origin)
      }
      "android.net.VpnService" -> {
        // This means we were started by Android due to Always On VPN.
        // We show a non-foreground notification because we weren't
        // started as a foreground service.
        scope.launch {
          if (!app.vpnEntitlementController.authorizeStart(VpnStartOrigin.AlwaysOn)) {
            rejectRuntimeStart()
            return@launch
          }
          // Collect the first value of hideDisconnectAction asynchronously.
          val hideDisconnectAction = MDMSettings.forceEnabled.flow.first()
          val exitNodeName =
              UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
          requestVpnAfterAuthorization(VpnStartOrigin.AlwaysOn) {
            app.notifyStatus(true, hideDisconnectAction.value, exitNodeName)
          }
        }
      }
      else -> {
        // Service restarted by Android OS (e.g. after process kill / OOM).
        val isOnDemandEnabled = app.onDemandRepository.config.value.enabled
        if (isOnDemandEnabled) {
          // START_STICKY only restores monitor mode, never auto-starts VPN from cached state.
          // OnDemandController will observe the network state and start the VPN if the
          // current network rules require it.
          synchronized(coordinatorLock) {
            closed.set(false)
            runCoordinator = createRunCoordinator()
          }
          showForegroundNotification()
        } else {
          // If On Demand is disabled, a killed process must never be restarted
          // from a cached backend-ready bit.
          stopSelf()
        }
      }
    }
    return if (app.onDemandRepository.config.value.enabled) START_STICKY else START_NOT_STICKY
  }

  private fun authorizeAndRequestVpn(origin: VpnStartOrigin) {
    scope.launch {
      if (!app.vpnEntitlementController.authorizeStart(origin)) {
        rejectRuntimeStart()
        return@launch
      }
      requestVpnAfterAuthorization(origin)
    }
  }

  fun startVpnFromApp(origin: VpnStartOrigin = VpnStartOrigin.ServiceStart) {
    showForegroundNotification()
    authorizeAndRequestVpn(origin)
  }

  private fun requestVpnAfterAuthorization(
      origin: VpnStartOrigin,
      beforeRequest: () -> Unit = {},
  ) {
    synchronized(coordinatorLock) {
      if (isDestroyed) return
      if (closed.get()) {
        TSLog.d(TAG, "requestVpnAfterAuthorization: teardown in flight, queueing start for origin=$origin")
        pendingStartOrigin = origin
        return
      }
      runCoordinator.beginAuthorizedStart(
          origin = origin,
          requestVpn = {
            beforeRequest()
            Libtailscale.requestVPN(this@IPNService)
          },
          rejectStart = { rejectRuntimeStart() },
      )
    }
  }

  private fun rejectRuntimeStart() {
    if (closed.get()) return
    startRejectionBoundary.reject()
  }

  private fun createRunCoordinator(): VpnServiceRunCoordinator {
    return VpnServiceRunCoordinator(
        runtime = app.vpnRuntimeTracker,
        authorizer = app.vpnEntitlementController,
        wantRunningWriter =
            VpnWantRunningWriter { wantRunning, onComplete ->
              app.setWantRunning(
                  wantRunning,
                  onSuccess = { onComplete(Result.success(Unit)) },
                  onFailure = { error -> onComplete(Result.failure(error)) },
              )
            },
        scope = scope,
    )
  }

  private fun handleStopCommand() {
    app.setWantRunning(false)
    val isOnDemandEnabled = app.onDemandRepository.config.value.enabled
    if (isOnDemandEnabled) {
      // Keep IPNService running in foreground monitor mode for On Demand!
      // Order teardown before recreate: replacement coordinator must only be installed
      // AFTER the previous coordinator's close fence (including any deferred action) has completed.
      synchronized(coordinatorLock) {
        if (closed.get()) {
          return
        }
        closed.set(true)
        val coordinatorToClose = runCoordinator
        coordinatorToClose.close {
          if (isDestroyed || (::app.isInitialized && app.activeIpnService !== this@IPNService && app.activeIpnService != null)) {
            TSLog.w(TAG, "handleStopCommand: suppressed disconnect from superseded/destroyed IPNService instance")
          } else {
            Notifier.setState(Ipn.State.Stopped)
            Libtailscale.serviceDisconnect(this)
            synchronized(coordinatorLock) {
              if (runCoordinator === coordinatorToClose && app.onDemandRepository.config.value.enabled && !isDestroyed) {
                runCoordinator = createRunCoordinator()
                closed.set(false)
                showForegroundNotification()

                val queuedOrigin = pendingStartOrigin
                pendingStartOrigin = null
                if (queuedOrigin != null) {
                  TSLog.d(TAG, "handleStopCommand: executing queued start for origin=$queuedOrigin")
                  requestVpnAfterAuthorization(queuedOrigin)
                }
              } else {
                pendingStartOrigin = null
              }
            }
          }
        }
      }
    } else {
      close()
    }
  }

  override fun close() {
    synchronized(coordinatorLock) {
      pendingStartOrigin = null
      if (!closed.compareAndSet(false, true)) {
        stopSelf()
        return
      }
      val coordinatorToClose = runCoordinator
      coordinatorToClose.close {
        if (::app.isInitialized && app.activeIpnService !== this@IPNService && app.activeIpnService != null) {
          TSLog.w(TAG, "close: suppressed disconnect from superseded IPNService instance")
        } else {
          Notifier.setState(Ipn.State.Stopping)
          stopSelf()
          Libtailscale.serviceDisconnect(this)
        }
      }
    }
  }

  override fun disconnectVPN() {
    val isOnDemandEnabled = ::app.isInitialized && app.onDemandRepository.config.value.enabled
    if (isOnDemandEnabled && !isDestroyed) {
      TSLog.d(TAG, "disconnectVPN: preserving service in monitor mode")
      handleStopCommand()
    } else {
      stopSelf()
    }
  }

  override fun onDestroy() {
    isDestroyed = true
    synchronized(coordinatorLock) {
      pendingStartOrigin = null
    }
    if (::app.isInitialized && app.activeIpnService === this) {
      app.activeIpnService = null
    }
    NetworkChangeCallback.setUnderlyingNetworkListener(null)
    serviceJob.cancel()
    close()
    stopCommandRegistration.unregister()
    updateVpnStatus(false)
    super.onDestroy()
  }

  override fun onRevoke() {
    // VPN permission was granted to another app, so tell the Go backend and then set prepared to be
    // false so that when user attempts to connect again, VpnService.prepare() is called
    app.setWantRunning(false)
    setVpnPrepared(false)
    close()
    updateVpnStatus(false)
    super.onRevoke()
  }

  private fun setVpnPrepared(isPrepared: Boolean) {
    app.getAppScopedViewModel().setVpnPrepared(isPrepared)
  }
  private fun updateUnderlyingNetwork(network: Network?) {
    val networks = network?.let { arrayOf(it) } ?: emptyArray()
    try {
      if (!setUnderlyingNetworks(networks)) {
        TSLog.w(TAG, "Failed to set underlying network: $network")
      } else {
        TSLog.d(TAG, "Set underlying network: $network")
      }
    } catch (e: Exception) {
      TSLog.w(TAG, "Exception setting underlying network: $network", e)
    }
  }


  private fun showForegroundNotification(
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null
  ) {
    try {
      val isMonitorMode = app.onDemandRepository.config.value.enabled &&
          Notifier.state.value != Ipn.State.Running &&
          Notifier.state.value != Ipn.State.Starting
      val vpnRunning = !isMonitorMode
      val notification =
          UninitializedApp.get().buildStatusNotification(vpnRunning, hideDisconnectAction, exitNodeName)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val hasLocation =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val fgsType =
            if (hasLocation) {
              ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                  ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
              ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        startForeground(UninitializedApp.STATUS_NOTIFICATION_ID, notification, fgsType)
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val hasLocation =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
          startForeground(
              UninitializedApp.STATUS_NOTIFICATION_ID,
              notification,
              ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
          startForeground(UninitializedApp.STATUS_NOTIFICATION_ID, notification)
        }
      } else {
        startForeground(UninitializedApp.STATUS_NOTIFICATION_ID, notification)
      }
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to start foreground service: $e")
    }
  }

  private fun showForegroundNotification() {
    val hideDisconnectAction = MDMSettings.forceEnabled.flow.value.value
    val exitNodeName = UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
    showForegroundNotification(hideDisconnectAction, exitNodeName)
  }

  private fun configIntent(): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun allowApp(b: Builder, name: String) {
    try {
      b.addAllowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add allowed application: $e")
    }
  }

  private fun disallowApp(b: Builder, name: String) {
    try {
      b.addDisallowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add disallowed application: $e")
    }
  }

  override fun newBuilder(): VPNServiceBuilder {
    val b: Builder =
        Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      b.setMetered(false) // Inherit the metered status from the underlying networks.
    }
    val underlyingNetwork = NetworkChangeCallback.cachedDefaultNetwork
    b.setUnderlyingNetworks(underlyingNetwork?.let { arrayOf(it) } ?: emptyArray())

    val mdmAllowed =
        MDMSettings.includedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()
    val mdmDisallowed =
        MDMSettings.excludedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()

    var packagesList: List<String>
    var allowPackages: Boolean
    if (mdmAllowed.isNotEmpty()) {
      // An admin defined a list of packages that are exclusively allowed to be used via
      // Tailscale, so only allow those.
      packagesList = mdmAllowed
      allowPackages = true
      TSLog.d(TAG, "Included application packages were set via MDM: $mdmAllowed")
    } else if (mdmDisallowed.isNotEmpty()) {
      // An admin defined a list of packages that are excluded from accessing Tailscale,
      // so ignore user definitions and only exclude those
      packagesList = mdmDisallowed
      allowPackages = false
      TSLog.d(TAG, "Excluded application packages were set via MDM: $mdmDisallowed")
    } else {
      // Otherwise, prevent user manually disallowed apps from getting their traffic + DNS routed
      // via Tailscale
      packagesList = UninitializedApp.get().selectedPackageNames()
      allowPackages = UninitializedApp.get().allowSelectedPackages()
      TSLog.d(TAG, "Application packages were set by user: $packagesList")
    }

    packagesList =
        packagesForVpnBuilder(
            packagesList = packagesList,
            allowPackages = allowPackages,
            vpnPackageName = UninitializedApp.get().packageName,
            builtInDisallowedPackages = UninitializedApp.get().builtInDisallowedPackageNames)

    if (allowPackages) {
      if (packagesList.isNotEmpty()) {
        for (packageName in packagesList) {
          TSLog.d(TAG, "Including app: $packageName")
          allowApp(b, packageName)
        }
      }
    } else {
      for (packageName in packagesList) {
        TSLog.d(TAG, "Disallowing app: $packageName")
        disallowApp(b, packageName)
      }
    }

    return VPNServiceBuilder(b)
  }

  companion object {
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    const val ACTION_RESTART_VPN = "com.tailscale.ipn.RESTART_VPN"
    const val ACTION_START_FOREGROUND_ONLY = "com.tailscale.ipn.START_FOREGROUND_ONLY"
    const val ACTION_START_MONITOR = "com.tailscale.ipn.START_MONITOR"
    const val EXTRA_ORIGIN = "com.tailscale.ipn.EXTRA_ORIGIN"
  }
}

internal fun packagesForVpnBuilder(
    packagesList: List<String>,
    allowPackages: Boolean,
    vpnPackageName: String,
    builtInDisallowedPackages: List<String>,
): List<String> =
    if (allowPackages) {
      if (packagesList.isEmpty()) {
        emptyList()
      } else {
        (packagesList + vpnPackageName).distinct()
      }
    } else {
      (packagesList + builtInDisallowedPackages).filter { it != vpnPackageName }.distinct()
    }
