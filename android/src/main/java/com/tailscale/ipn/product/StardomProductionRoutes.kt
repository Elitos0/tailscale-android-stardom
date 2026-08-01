// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product

enum class StardomRoute(val path: String) {
  MAIN("main"),
  SEARCH("search"),
  SETTINGS("settings"),
  EXIT_NODES("exitNodes"),
  HEALTH("health"),
  RUN_EXIT_NODE("runExitNode"),
  PEER_DETAILS("peerDetails/{nodeId}"),
  BUG_REPORT("bugReport"),
  DNS_SETTINGS("dnsSettings"),
  SPLIT_TUNNELING("splitTunneling"),
  TAILNET_LOCK("tailnetLock"),
  SUBNET_ROUTING("subnetRouting"),
  ABOUT("about"),
  MDM_SETTINGS("mdmSettings"),
  MANAGED_BY("managedBy"),
  ACCOUNT("account"),
  PERMISSIONS("permissions"),
  TAILDROP_DIR("taildropDir"),
  NOTIFICATIONS("notifications"),
  INTRO("intro"),
  LOGIN_WITH_STARDOM("loginWithStardom"),
}

object StardomProductionRoutes {
  val paths: Set<String> = StardomRoute.entries.mapTo(linkedSetOf()) { it.path }

  fun peerDetails(nodeId: String): String = "peerDetails/$nodeId"
}
