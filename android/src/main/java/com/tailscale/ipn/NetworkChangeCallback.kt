// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.tailscale.ipn.util.TSLog
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import libtailscale.Libtailscale
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tailscale.ipn.product.ondemand.ActiveNetworkSnapshot
import com.tailscale.ipn.product.ondemand.NetworkTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NetworkCandidate<T>(
    val value: T,
    val internet: Boolean,
    val notVpn: Boolean,
    val validated: Boolean,
    val hasDns: Boolean,
    val nonMetered: Boolean,
)

internal fun <T> pickPreferredNetwork(candidates: List<NetworkCandidate<T>>): T? {
  fun pick(requireValidated: Boolean, requireDNS: Boolean): T? {
    val matching =
        candidates.filter {
          it.internet &&
              it.notVpn &&
              (!requireValidated || it.validated) &&
              (!requireDNS || it.hasDns)
        }

    return matching.firstOrNull { it.nonMetered }?.value ?: matching.firstOrNull()?.value
  }

  return pick(requireValidated = true, requireDNS = true)
      ?: pick(requireValidated = true, requireDNS = false)
      ?: pick(requireValidated = false, requireDNS = true)
      ?: pick(requireValidated = false, requireDNS = false)
}

object NetworkChangeCallback {

  private const val TAG = "NetworkChangeCallback"

  private data class NetworkInfo(
      var caps: NetworkCapabilities? = null,
      var linkProps: LinkProperties? = null,
  )

  private val lock = ReentrantLock()

  // All currently active non-VPN networks we know about.
  private val activeNetworks = mutableMapOf<Network, NetworkInfo>()

  // Cached chosen default network for outbound sockets.
  @Volatile
  var cachedDefaultNetwork: Network? = null
    private set

  // Cached info for the chosen default network.
  @Volatile private var cachedDefaultNetworkInfo: NetworkInfo? = null

  // Convenience: cached interface name for logging.
  @Volatile
  var cachedDefaultInterfaceName: String? = null
    private set

  @Volatile private var underlyingNetworkListener: ((Network?) -> Unit)? = null

  fun setUnderlyingNetworkListener(listener: ((Network?) -> Unit)?) {
    underlyingNetworkListener = listener
  }
  @Volatile private var ssidDiscoveryListener: ((String) -> Unit)? = null

  fun setSsidDiscoveryListener(listener: ((String) -> Unit)?) {
    ssidDiscoveryListener = listener
  }
  private val _activeNetworkSnapshot = MutableStateFlow(ActiveNetworkSnapshot())
  val activeNetworkSnapshot: StateFlow<ActiveNetworkSnapshot> = _activeNetworkSnapshot.asStateFlow()

  @Volatile private var appContext: Context? = null

  fun setApplicationContext(context: Context) {
    appContext = context.applicationContext
  }

  // monitorDnsChanges sets up a network callback to monitor changes to the
  // system's network state and update the DNS configuration when interfaces
  // become available or properties of those interfaces change.
  fun monitorDnsChanges(connectivityManager: ConnectivityManager, dns: DnsConfig) {
    val networkConnectivityRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

    // Use registerNetworkCallback to listen for updates from all networks, and
    // then update DNS configs for the best network when LinkProperties are changed.
    // Per
    // https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback#onAvailable(android.net.Network), this happens after all other updates.
    //
    // Note that we can't use registerDefaultNetworkCallback because the
    // default network used by Tailscale will always show up with capability
    // NOT_VPN=false, and we must filter out NOT_VPN networks to avoid routing
    // loops.
    val callback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
              super.onAvailable(network)
              handleNetworkAvailable(network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
              super.onCapabilitiesChanged(network, capabilities)
              handleNetworkCapabilitiesChanged(network, capabilities, dns)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
              super.onLinkPropertiesChanged(network, linkProperties)
              handleNetworkLinkPropertiesChanged(network, linkProperties, dns)
            }

            override fun onLost(network: Network) {
              super.onLost(network)
              handleNetworkLost(network, dns)
            }
          }
        } else {
          object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
              super.onAvailable(network)
              handleNetworkAvailable(network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
              super.onCapabilitiesChanged(network, capabilities)
              handleNetworkCapabilitiesChanged(network, capabilities, dns)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
              super.onLinkPropertiesChanged(network, linkProperties)
              handleNetworkLinkPropertiesChanged(network, linkProperties, dns)
            }

            override fun onLost(network: Network) {
              super.onLost(network)
              handleNetworkLost(network, dns)
            }
          }
        }
    connectivityManager.registerNetworkCallback(networkConnectivityRequest, callback)
  }

  private fun handleNetworkAvailable(network: Network) {
    TSLog.d(TAG, "onAvailable: network $network")
    lock.withLock {
      activeNetworks[network] = NetworkInfo()
      recomputeDefaultNetworkLocked("onAvailable")
    }
  }

  private fun handleNetworkCapabilitiesChanged(
      network: Network,
      capabilities: NetworkCapabilities,
      dns: DnsConfig,
  ) {
    lock.withLock {
      activeNetworks[network]?.caps = capabilities

      if (recomputeDefaultNetworkLocked("onCapabilitiesChanged")) {
        maybeUpdateDNSConfig("onCapabilitiesChanged", dns)
      }
    }
  }

  private fun handleNetworkLinkPropertiesChanged(
      network: Network,
      linkProperties: LinkProperties,
      dns: DnsConfig,
  ) {
    lock.withLock {
      activeNetworks[network]?.linkProps = linkProperties
      recomputeDefaultNetworkLocked("onLinkPropertiesChanged")
      maybeUpdateDNSConfig("onLinkPropertiesChanged", dns)
    }
  }

  private fun handleNetworkLost(network: Network, dns: DnsConfig) {
    TSLog.d(TAG, "onLost: network $network")
    lock.withLock {
      activeNetworks.remove(network)
      recomputeDefaultNetworkLocked("onLost")
      maybeUpdateDNSConfig("onLost", dns)
    }
  }

  // pickDefaultNetwork returns a non-VPN network to use as the 'default'
  // network; one that is used as a gateway to the internet and from which we
  // obtain our DNS servers.
  //
  // Networks are preferred in this order:
  //   1. VALIDATED + INTERNET + NOT_VPN + DNS
  //   2. VALIDATED + INTERNET + NOT_VPN
  //   3. INTERNET + NOT_VPN + DNS
  //   4. INTERNET + NOT_VPN
  //   5. null
  //
  // Within each group, prefer a non-metered network. VALIDATED is preferred,
  // but not required, because per
  // https://developer.android.com/develop/connectivity/network-ops/reading-network-state,
  // newly available networks may be usable before Android has finished validating them.
  private fun pickDefaultNetwork(): Network? {
    return pickPreferredNetwork(
        activeNetworks.map { (network, info) ->
          NetworkCandidate(
              value = network,
              internet = info.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
              notVpn = info.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true,
              validated = info.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
              hasDns = info.linkProps?.dnsServers?.isNotEmpty() == true,
              nonMetered = info.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
          )
        })
  }

  // Update cached default network + log interface name. Return whether or not default network
  // changed.
  private fun recomputeDefaultNetworkLocked(why: String): Boolean {
    val oldNetwork = cachedDefaultNetwork
    val newNetwork = pickDefaultNetwork()

    cachedDefaultNetwork = newNetwork

    val info = if (newNetwork != null) activeNetworks[newNetwork] else null
    cachedDefaultNetworkInfo = info
    cachedDefaultInterfaceName = info?.linkProps?.interfaceName

    TSLog.d(
        TAG,
        "$why: cachedDefaultNetwork=$newNetwork iface=${cachedDefaultInterfaceName ?: "none"}",
    )
    updateActiveNetworkSnapshotLocked(info)

    if (newNetwork != oldNetwork) {
      underlyingNetworkListener?.invoke(newNetwork)
      return true
    }

    return false
  }

  internal fun updateNetworkForTesting(
      network: Network,
      caps: NetworkCapabilities? = null,
      linkProps: LinkProperties? = null,
  ) {
    lock.withLock {
      val info = activeNetworks.getOrPut(network) { NetworkInfo() }
      caps?.let { info.caps = it }
      linkProps?.let { info.linkProps = it }
      recomputeDefaultNetworkLocked("test")
    }
  }

  internal fun removeNetworkForTesting(network: Network) {
    lock.withLock {
      activeNetworks.remove(network)
      recomputeDefaultNetworkLocked("test")
    }
  }

  internal fun resetForTesting() {
    lock.withLock {
      activeNetworks.clear()
      cachedDefaultNetwork = null
      cachedDefaultNetworkInfo = null
      cachedDefaultInterfaceName = null
      underlyingNetworkListener = null
      ssidDiscoveryListener = null
      _activeNetworkSnapshot.value = ActiveNetworkSnapshot()
    }
  }

  // maybeUpdateDNSConfig will maybe update our DNS configuration based on the
  // current set of active Networks.
  private fun maybeUpdateDNSConfig(why: String, dns: DnsConfig) {
    val defaultNetwork = cachedDefaultNetwork
    if (defaultNetwork == null) {
      TSLog.d(TAG, "$why: no default network available; not updating DNS")
      return
    }

    val info = cachedDefaultNetworkInfo
    val linkProps = info?.linkProps
    if (linkProps == null) {
      Log.w(TAG, "$why: no link properties for default network; not updating DNS")
      return
    }

    val sb = StringBuilder()
    for (ip in linkProps.dnsServers) {
      sb.append(ip.hostAddress).append(" ")
    }

    val searchDomains: String? = linkProps.domains
    if (searchDomains != null) {
      sb.append("\n")
      sb.append(searchDomains)
    }

    if (dns.updateDNSFromNetwork(sb.toString())) {
      TSLog.d(TAG, "$why: updated DNS config for iface=${linkProps.interfaceName}")

      val gatewayIP =
          linkProps.routes
              .filter { it.isDefaultRoute && it.gateway != null }
              .sortedBy { if (it.gateway is java.net.Inet4Address) 0 else 1 }
              .firstNotNullOfOrNull { it.gateway?.hostAddress } ?: ""

      Libtailscale.onGatewayChanged(gatewayIP)
      Libtailscale.onDNSConfigChanged(linkProps.interfaceName)
    }
  }

  private fun updateActiveNetworkSnapshotLocked(info: NetworkInfo?) {
    val caps = info?.caps
    if (caps == null) {
      _activeNetworkSnapshot.value = ActiveNetworkSnapshot(
          transport = NetworkTransport.NONE,
          ssid = null,
          isValidated = false,
      )
      return
    }

    val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val transport = when {
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
      else -> NetworkTransport.NONE
    }

    val ssid = if (transport == NetworkTransport.WIFI) {
      extractWifiSsid(caps)
    } else {
      null
    }

    if (ssid != null && ssid.isNotBlank()) {
      ssidDiscoveryListener?.invoke(ssid)
    }
    _activeNetworkSnapshot.value = ActiveNetworkSnapshot(
        transport = transport,
        ssid = ssid,
        isValidated = isValidated,
    )
  }

  private fun extractWifiSsid(caps: NetworkCapabilities): String? {
    val context = appContext ?: return null

    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasLocationPermission) {
      return null
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val wifiInfo = caps.transportInfo as? WifiInfo
        val rawSsid = wifiInfo?.ssid
        if (!rawSsid.isNullOrBlank() && rawSsid != WifiManager.UNKNOWN_SSID && rawSsid != "<unknown ssid>") {
          return rawSsid.removeSurrounding("\"")
        }
      }

      val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      val rawSsid = wifiManager?.connectionInfo?.ssid
      if (!rawSsid.isNullOrBlank() && rawSsid != WifiManager.UNKNOWN_SSID && rawSsid != "<unknown ssid>") {
        return rawSsid.removeSurrounding("\"")
      }
    } catch (e: Exception) {
      TSLog.w(TAG, "Failed to extract Wi-Fi SSID: ${e.message}")
    }

    return null
  }
}
