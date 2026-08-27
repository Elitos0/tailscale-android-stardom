// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
import android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.product.StardomProductionRoutes
import com.tailscale.ipn.product.StardomRoute
import com.tailscale.ipn.product.StardomSessionController
import com.tailscale.ipn.product.policy.PolicyApiClient
import com.tailscale.ipn.product.policy.VpnStartOrigin
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.universalFit
import com.tailscale.ipn.ui.view.AboutView
import com.tailscale.ipn.ui.view.BugReportView
import com.tailscale.ipn.ui.view.DNSSettingsView
import com.tailscale.ipn.ui.view.ExitNodePicker
import com.tailscale.ipn.ui.view.HealthView
import com.tailscale.ipn.ui.view.IntroView
import com.tailscale.ipn.ui.view.LoginQRView
import com.tailscale.ipn.ui.view.LoginWithCustomControlURLView
import com.tailscale.ipn.ui.view.MDMSettingsDebugView
import com.tailscale.ipn.ui.view.MainView
import com.tailscale.ipn.ui.view.MainViewNavigation
import com.tailscale.ipn.ui.view.ManagedByView
import com.tailscale.ipn.ui.view.NotificationsView
import com.tailscale.ipn.ui.view.PeerDetails
import com.tailscale.ipn.ui.view.PermissionsView
import com.tailscale.ipn.ui.view.PrimaryActionButton
import com.tailscale.ipn.ui.view.RunExitNodeView
import com.tailscale.ipn.ui.view.SearchView
import com.tailscale.ipn.ui.view.SettingsView
import com.tailscale.ipn.ui.view.SplitTunnelAppPickerView
import com.tailscale.ipn.ui.view.SubnetRoutingView
import com.tailscale.ipn.ui.view.TaildropDirView
import com.tailscale.ipn.ui.view.TaildropDirectoryPickerPrompt
import com.tailscale.ipn.ui.view.TailnetLockSetupView
import com.tailscale.ipn.ui.view.UserSwitcherNav
import com.tailscale.ipn.ui.view.UserSwitcherView
import com.tailscale.ipn.ui.viewModel.AppViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.MainViewModel
import com.tailscale.ipn.ui.viewModel.MainViewModelFactory
import com.tailscale.ipn.ui.viewModel.PermissionsViewModel
import com.tailscale.ipn.ui.viewModel.PingViewModel
import com.tailscale.ipn.ui.viewModel.SettingsNav
import com.tailscale.ipn.util.ShareFileHelper
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  private lateinit var navController: NavHostController
  private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
  private lateinit var appViewModel: AppViewModel
  private lateinit var viewModel: MainViewModel
  private lateinit var stardomSessionController: StardomSessionController

  val permissionsViewModel: PermissionsViewModel by viewModels()

  companion object {
    private const val TAG = "Main Activity"
    private const val START_AT_ROOT = "startAtRoot"
  }

  private fun Context.isLandscapeCapable(): Boolean {
    return (resources.configuration.screenLayout and SCREENLAYOUT_SIZE_MASK) >=
        SCREENLAYOUT_SIZE_LARGE
  }
  // The loginQRCode is used to track whether or not we should be rendering a QR code
  // to the user.  This is used only on TV platforms with no browser in lieu of
  // simply opening the URL.  This should be consumed once it has been handled.
  private val loginQRCode: StateFlow<String?> = MutableStateFlow(null)

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  @SuppressLint("SourceLockedOrientationActivity")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // grab app to make sure it initializes
    App.get()
    stardomSessionController = (application as App).stardomSessionController
    appViewModel = (application as App).getAppScopedViewModel()
    viewModel =
        ViewModelProvider(
                this,
                MainViewModelFactory(
                    appViewModel,
                    (application as App).vpnEntitlementController,
                ))
            .get(MainViewModel::class.java)
    resumeFixedControlLoginIfPending()

    val rm = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    MDMSettings.update(App.get(), rm)
    if (MDMSettings.onboardingFlow.flow.value.value == ShowHide.Hide) {
      setIntroScreenViewed(true)
    }
    // (jonathan) TODO: Force the app to be portrait on small screens until we have
    // proper landscape layout support
    if (!isLandscapeCapable()) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    installSplashScreen()
    vpnPermissionLauncher =
        registerForActivityResult(VpnPermissionContract()) { granted ->
          if (granted) {
            lifecycleScope.launch {
              val app = application as App
              if (app.vpnEntitlementController.authorizeStart(VpnStartOrigin.PermissionResult)) {
                TSLog.d("VpnPermission", "VPN permission granted after entitlement recheck")
                appViewModel.setVpnPrepared(true)
                app.startVPN(VpnStartOrigin.PermissionResult)
              } else {
                TSLog.d("VpnPermission", "VPN permission result rejected by entitlement policy")
                appViewModel.setVpnPrepared(false)
              }
            }
          } else {
            if (isAnotherVpnActive(this)) {
              TSLog.d("VpnPermission", "Another VPN is likely active")
              showOtherVPNConflictDialog()
            } else {
              TSLog.d("VpnPermission", "Permission was denied by the user")
              appViewModel.setVpnPrepared(false)

              AlertDialog.Builder(this)
                  .setTitle(R.string.vpn_permission_needed)
                  .setMessage(R.string.vpn_explainer)
                  .setPositiveButton(R.string.try_again) { _, _ ->
                    viewModel.showVPNPermissionLauncherIfUnauthorized()
                  }
                  .setNegativeButton(R.string.cancel, null)
                  .show()
            }
          }
        }
    viewModel.setVpnPermissionLauncher(vpnPermissionLauncher)
    val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
          if (uri != null) {
            try {
              // Try to take persistable permissions for both read and write.
              contentResolver.takePersistableUriPermission(
                  uri,
                  Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
              TSLog.e("MainActivity", "Failed to persist permissions: $e")
            }
            // Check if write permission is actually granted.
            val writePermission =
                this.checkUriPermission(
                    uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (writePermission == PackageManager.PERMISSION_GRANTED) {
              TSLog.d("MainActivity", "Write permission granted for $uri")

              lifecycleScope.launch(Dispatchers.IO) {
                try {
                  TaildropDirectoryStore.saveFileDirectory(uri)
                  permissionsViewModel.refreshCurrentDir()
                  ShareFileHelper.notifyDirectoryReady()
                  ShareFileHelper.setUri(uri.toString())
                } catch (e: Exception) {
                  TSLog.e("MainActivity", "Failed to set Taildrop root: $e")
                }
              }
            } else {
              TSLog.d(
                  "MainActivity",
                  "Write access not granted for $uri. Falling back to internal storage.")
              // Don't save directory URI and fall back to internal storage.
            }
          } else {
            TSLog.d(
                "MainActivity", "Taildrop directory not saved. Will fall back to internal storage.")
            // Fall back to internal storage.
          }
        }

    appViewModel.directoryPickerLauncher = directoryPickerLauncher

    setContent {
      var showDialog by remember { mutableStateOf(false) }

      LaunchedEffect(Unit) { appViewModel.triggerDirectoryPicker.collect { showDialog = true } }

      if (showDialog) {
        AppTheme {
          AlertDialog(
              onDismissRequest = {
                showDialog = false
                appViewModel.directoryPickerLauncher?.launch(null)
              },
              title = {
                Text(text = stringResource(id = R.string.taildrop_directory_picker_title))
              },
              text = { TaildropDirectoryPickerPrompt() },
              confirmButton = {
                PrimaryActionButton(
                    onClick = {
                      showDialog = false
                      appViewModel.directoryPickerLauncher?.launch(null)
                    }) {
                      Text(text = stringResource(id = R.string.taildrop_directory_picker_button))
                    }
              })
        }
      }

      navController = rememberNavController()

      AppTheme {
        Surface(color = MaterialTheme.colorScheme.inverseSurface) { // Background for the letterbox
          Surface(modifier = Modifier.universalFit()) { // Letterbox for AndroidTV
            NavHost(
                navController = navController,
                startDestination = StardomRoute.MAIN.path,
                enterTransition = {
                  slideInHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      initialOffsetX = { it }) +
                      fadeIn(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                },
                exitTransition = {
                  slideOutHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      targetOffsetX = { -it }) +
                      fadeOut(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                },
                popEnterTransition = {
                  slideInHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      initialOffsetX = { -it }) +
                      fadeIn(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                },
                popExitTransition = {
                  slideOutHorizontally(
                      animationSpec = tween(250, easing = LinearOutSlowInEasing),
                      targetOffsetX = { it }) +
                      fadeOut(animationSpec = tween(500, easing = LinearOutSlowInEasing))
                }) {
                  fun backTo(route: String): () -> Unit = {
                    navController.popBackStack(route = route, inclusive = false)
                  }
                  val mainViewNav =
                      MainViewNavigation(
                          onNavigateToSettings = {
                            navController.navigate(StardomRoute.SETTINGS.path)
                          },
                          onNavigateStardomLogin = {
                            stardomSessionController.clearSession()
                            navController.navigate(StardomRoute.LOGIN_WITH_STARDOM.path)
                          },
                          onNavigateToPeerDetails = {
                            navController.navigate(StardomProductionRoutes.peerDetails(it.StableID))
                          },
                          onNavigateToExitNodes = {
                            navController.navigate(StardomRoute.EXIT_NODES.path)
                          },
                          onNavigateToHealth = { navController.navigate(StardomRoute.HEALTH.path) },
                          onNavigateToSearch = {
                            viewModel.enableSearchAutoFocus()
                            navController.navigate(StardomRoute.SEARCH.path)
                          })
                  val settingsNav =
                      SettingsNav(
                          onNavigateToBugReport = {
                            navController.navigate(StardomRoute.BUG_REPORT.path)
                          },
                          onNavigateToAbout = { navController.navigate(StardomRoute.ABOUT.path) },
                          onNavigateToDNSSettings = {
                            navController.navigate(StardomRoute.DNS_SETTINGS.path)
                          },
                          onNavigateToSplitTunneling = {
                            navController.navigate(StardomRoute.SPLIT_TUNNELING.path)
                          },
                          onNavigateToTailnetLock = {
                            navController.navigate(StardomRoute.TAILNET_LOCK.path)
                          },
                          onNavigateToSubnetRouting = {
                            navController.navigate(StardomRoute.SUBNET_ROUTING.path)
                          },
                          onNavigateToMDMSettings = {
                            navController.navigate(StardomRoute.MDM_SETTINGS.path)
                          },
                          onNavigateToManagedBy = {
                            navController.navigate(StardomRoute.MANAGED_BY.path)
                          },
                          onNavigateToUserSwitcher = {
                            navController.navigate(StardomRoute.ACCOUNT.path)
                          },
                          onNavigateToPermissions = {
                            navController.navigate(StardomRoute.PERMISSIONS.path)
                          },
                          onBackToSettings = backTo(StardomRoute.SETTINGS.path),
                          onNavigateBackHome = backTo(StardomRoute.MAIN.path))
                  val exitNodePickerNav =
                      ExitNodePickerNav(
                          onNavigateBackHome = {
                            navController.popBackStack(
                                route = StardomRoute.MAIN.path, inclusive = false)
                          },
                          onNavigateBackToExitNodes = backTo(StardomRoute.EXIT_NODES.path),
                          onNavigateToMullvad = {},
                          onNavigateToMullvadInfo = {},
                          onNavigateBackToMullvad = {},
                          onNavigateToMullvadCountry = {},
                          onNavigateToRunAsExitNode = {
                            navController.navigate(StardomRoute.RUN_EXIT_NODE.path)
                          })
                  val userSwitcherNav =
                      UserSwitcherNav(
                          backToSettings = backTo(StardomRoute.SETTINGS.path),
                          onNavigateHome = backTo(StardomRoute.MAIN.path),
                          onReauthenticate = {
                            stardomSessionController.clearSession()
                            navController.navigate(StardomRoute.LOGIN_WITH_STARDOM.path)
                          },
                          onClearStardomSession = stardomSessionController::clearSession)

                  composable(
                      StardomRoute.MAIN.path,
                      enterTransition = { fadeIn(animationSpec = tween(150)) }) {
                        MainView(
                            loginAtUrl = ::login,
                            navigation = mainViewNav,
                            viewModel = viewModel,
                            sessionController = stardomSessionController,
                        )
                      }
                  composable(StardomRoute.SEARCH.path) {
                    val autoFocus = viewModel.autoFocusSearch
                    SearchView(
                        viewModel = viewModel,
                        navController = navController,
                        onNavigateBack = { navController.popBackStack() },
                        autoFocus = autoFocus)
                  }
                  composable(StardomRoute.SETTINGS.path) {
                    SettingsView(settingsNav = settingsNav, appViewModel = appViewModel)
                  }
                  composable(StardomRoute.EXIT_NODES.path) {
                    ExitNodePicker(exitNodePickerNav, stardomSessionController.accessState)
                  }
                  composable(StardomRoute.HEALTH.path) {
                    HealthView(backTo(StardomRoute.MAIN.path))
                  }
                  composable(StardomRoute.RUN_EXIT_NODE.path) { RunExitNodeView(exitNodePickerNav) }
                  composable(
                      StardomRoute.PEER_DETAILS.path,
                      arguments = listOf(navArgument("nodeId") { type = NavType.StringType })) {
                        PeerDetails(
                            { navController.popBackStack() },
                            it.arguments?.getString("nodeId") ?: "",
                            PingViewModel())
                      }
                  composable(StardomRoute.BUG_REPORT.path) {
                    BugReportView(backTo(StardomRoute.SETTINGS.path))
                  }
                  composable(StardomRoute.DNS_SETTINGS.path) {
                    DNSSettingsView(backTo(StardomRoute.SETTINGS.path))
                  }
                  composable(StardomRoute.SPLIT_TUNNELING.path) {
                    SplitTunnelAppPickerView(backTo(StardomRoute.SETTINGS.path))
                  }
                  composable(StardomRoute.TAILNET_LOCK.path) {
                    TailnetLockSetupView(backTo(StardomRoute.SETTINGS.path))
                  }
                  composable(StardomRoute.SUBNET_ROUTING.path) {
                    SubnetRoutingView(backTo(StardomRoute.SETTINGS.path))
                  }
                  composable(StardomRoute.ABOUT.path) {
                    AboutView(backTo(StardomRoute.SETTINGS.path))
                  }
                  composable(StardomRoute.MDM_SETTINGS.path) {
                    MDMSettingsDebugView(backTo(StardomRoute.SETTINGS.path))
                  }
                  composable(StardomRoute.MANAGED_BY.path) {
                    ManagedByView(backTo(StardomRoute.SETTINGS.path))
                  }
                  composable(StardomRoute.ACCOUNT.path) { UserSwitcherView(userSwitcherNav) }
                  composable(StardomRoute.PERMISSIONS.path) {
                    PermissionsView(
                        backTo(StardomRoute.SETTINGS.path),
                        { navController.navigate(StardomRoute.TAILDROP_DIR.path) },
                        { navController.navigate(StardomRoute.NOTIFICATIONS.path) })
                  }
                  composable(StardomRoute.TAILDROP_DIR.path) {
                    TaildropDirView(
                        backTo(StardomRoute.PERMISSIONS.path),
                        directoryPickerLauncher,
                        permissionsViewModel)
                  }
                  composable(StardomRoute.NOTIFICATIONS.path) {
                    NotificationsView(
                        backTo(StardomRoute.PERMISSIONS.path), ::openApplicationSettings)
                  }
                  composable(
                      StardomRoute.INTRO.path,
                      exitTransition = { fadeOut(animationSpec = tween(150)) }) {
                        IntroView(backTo(StardomRoute.MAIN.path))
                      }
                  composable(StardomRoute.LOGIN_WITH_STARDOM.path) {
                    LoginWithCustomControlURLView(
                        context = this@MainActivity,
                        authSessionRepository = stardomSessionController.authSessionRepository,
                        onNavigateHome = backTo(StardomRoute.MAIN.path),
                        backToSettings = backTo(StardomRoute.MAIN.path))
                  }
                }
            if (isIntroScreenViewedSet()) {
              navController.navigate(StardomRoute.INTRO.path)
              setIntroScreenViewed(true)
            }
          }
        }
        // Login actions are app wide.  If we are told about a browse-to-url, we should render it
        // over whatever screen we happen to be on.
        loginQRCode.collectAsState().value?.let {
          LoginQRView(onDismiss = { loginQRCode.set(null) })
        }
      }
    }
  }

  init {
    // Watch the model's browseToURL and launch the browser when it changes or
    // pop up a QR code to scan
    lifecycleScope.launch {
      Notifier.browseToURL.collect { url ->
        url?.let {
          when (useQRCodeLogin()) {
            false -> Dispatchers.Main.run { login(it) }
            true -> loginQRCode.set(it)
          }
        }
      }
    }
    // Once we see a loginFinished event, clear the QR code which will dismiss the QR dialog.
    lifecycleScope.launch { Notifier.loginFinished.collect { _ -> loginQRCode.set(null) } }
  }

  private fun showOtherVPNConflictDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.vpn_permission_denied)
        .setMessage(R.string.multiple_vpn_explainer)
        .setPositiveButton(R.string.go_to_settings) { _, _ ->
          // Intent to open the VPN settings
          val intent = Intent(Settings.ACTION_VPN_SETTINGS)
          startActivity(intent)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
  }

  fun isAnotherVpnActive(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = connectivityManager.activeNetwork
    if (activeNetwork != null) {
      val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
      if (networkCapabilities != null &&
          networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        return true
      }
    }
    return false
  }
  // Returns true if we should render a QR code instead of launching a browser
  // for login requests
  private fun useQRCodeLogin(): Boolean {
    return AndroidTVUtil.isAndroidTV()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    resumeFixedControlLoginIfPending()
    if (intent.getBooleanExtra(START_AT_ROOT, false)) {
      if (this::navController.isInitialized) {
        val previousEntry = navController.previousBackStackEntry
        TSLog.d("MainActivity", "onNewIntent: previousBackStackEntry = $previousEntry")
        if (previousEntry != null) {
          navController.popBackStack(route = StardomRoute.MAIN.path, inclusive = false)
        } else {
          TSLog.e(
              "MainActivity",
              "onNewIntent: No previous back stack entry, navigating directly to 'main'")
          navController.navigate(StardomRoute.MAIN.path) {
            popUpTo(StardomRoute.MAIN.path) { inclusive = true }
          }
        }
      }
    }
  }

  private fun resumeFixedControlLogin() {
    val authSession = stardomSessionController.authSessionRepository
    authSession.withFreshBearerToken(this) { tokenResult ->
      tokenResult.onSuccess { token ->
        lifecycleScope.launch(Dispatchers.IO) {
          val keyResult = PolicyApiClient().fetchNodeAuthKey(token)
          withContext(Dispatchers.Main) {
            keyResult.onSuccess { authKey ->
              viewModel.loginWithAuthKey(authKey) { result ->
                result.onSuccess {
                  if (this@MainActivity::navController.isInitialized) {
                    navController.popBackStack(route = StardomRoute.MAIN.path, inclusive = false)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private fun resumeFixedControlLoginIfPending() {
    if (stardomSessionController.consumeFixedHeadscaleContinuation()) {
      resumeFixedControlLogin()
    }
  }

  private fun login(urlString: String) {
    // Launch coroutine to listen for state changes. When the user completes login, relaunch
    // MainActivity to bring the app back to focus.
    App.get().applicationScope.launch {
      try {
        Notifier.state.collect { state ->
          if (state > Ipn.State.NeedsMachineAuth) {
            // Clear URL because if MainActivity is destroyed while backgrounded, the new instance
            // would hold the old auth URL
            Notifier.browseToURL.set(null)
            val intent =
                Intent(applicationContext, MainActivity::class.java).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                  action = Intent.ACTION_MAIN
                  addCategory(Intent.CATEGORY_LAUNCHER)
                  putExtra(START_AT_ROOT, true)
                }
            startActivity(intent)
            // Cancel coroutine once we've logged in
            this@launch.cancel()
          }
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "Login: failed to start MainActivity: $e")
      }
    }
    val url = urlString.toUri()
    try {
      val customTabsIntent = CustomTabsIntent.Builder().build()
      customTabsIntent.launchUrl(this, url)
    } catch (e: Exception) {
      // Fallback to a regular browser if CustomTabsIntent fails
      try {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, url)
        startActivity(fallbackIntent)
      } catch (e: Exception) {
        TSLog.e(TAG, "Login: failed to open browser: $e")
      }
    }
  }

  override fun onResume() {
    super.onResume()
    val restrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    lifecycleScope.launch(Dispatchers.IO) {
      MDMSettings.update(App.get(), restrictionsManager)
      (application as App).vpnEntitlementController.refreshRuntimeEntitlement()
    }
  }

  override fun onStop() {
    super.onStop()
    val restrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    lifecycleScope.launch(Dispatchers.IO) { MDMSettings.update(App.get(), restrictionsManager) }
  }

  private fun openApplicationSettings() {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
          putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
  }

  private fun isIntroScreenViewedSet(): Boolean {
    return !getSharedPreferences("introScreen", Context.MODE_PRIVATE).getBoolean("seen", false)
  }

  private fun setIntroScreenViewed(seen: Boolean) {
    getSharedPreferences("introScreen", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("seen", seen)
        .apply()
  }
}

class VpnPermissionContract : ActivityResultContract<Intent, Boolean>() {
  override fun createIntent(context: Context, input: Intent): Intent {
    return input
  }

  override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
    return resultCode == Activity.RESULT_OK
  }
}
