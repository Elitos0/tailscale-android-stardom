// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import java.io.File

sealed interface UpdateState {
  data object Idle : UpdateState

  data object Checking : UpdateState

  data class UpdateAvailable(val manifest: UpdateManifest, val isForced: Boolean) : UpdateState

  data class Downloading(
      val manifest: UpdateManifest,
      val progress: Float,
      val bytesDownloaded: Long,
      val totalBytes: Long,
      val isForced: Boolean,
  ) : UpdateState

  data class Downloaded(
      val manifest: UpdateManifest,
      val apkFile: File,
      val isForced: Boolean,
  ) : UpdateState

  data class Installing(val manifest: UpdateManifest, val isForced: Boolean) : UpdateState

  data class Error(
      val message: String,
      val canRetry: Boolean,
      val isForced: Boolean,
      val manifest: UpdateManifest? = null,
  ) : UpdateState

  data object UpToDate : UpdateState
}
