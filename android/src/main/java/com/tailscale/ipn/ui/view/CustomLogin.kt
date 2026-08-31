// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.product.auth.AuthSessionRepository
import com.tailscale.ipn.ui.components.StardomBackground
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors
import com.tailscale.ipn.ui.theme.StardomDimensions
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.LoginWithAuthKeyViewModel
import com.tailscale.ipn.ui.viewModel.LoginWithCustomControlURLViewModel
import com.tailscale.ipn.ui.viewModel.LoginWithCustomControlURLViewModelFactory

data class LoginViewStrings(
    var title: String,
    var explanation: String,
    var inputTitle: String,
    var placeholder: String,
)

@Composable
fun LoginWithCustomControlURLView(
    context: Context,
    authSessionRepository: AuthSessionRepository,
    onNavigateHome: BackNavigation,
    backToSettings: BackNavigation,
    viewModel: LoginWithCustomControlURLViewModel =
        viewModel(factory = LoginWithCustomControlURLViewModelFactory(authSessionRepository))
) {
  Scaffold(
      containerColor = StardomColors.Background,
      topBar = {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(StardomDimensions.TopBarHeight)
                    .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
              Box(
                  modifier =
                      Modifier.size(StardomDimensions.HeaderButtonSize)
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.Border)
                          .clickable(onClickLabel = "Back") { backToSettings() },
                  contentAlignment = Alignment.Center) {
                    Text(
                        text = "❮",
                        color = StardomColors.TextSecondary,
                        fontSize = 14.sp,
                        fontFamily = IbmPlexMono)
                  }

              Text(
                  text = "S T A R D O M",
                  color = StardomColors.TextPrimary,
                  fontFamily = SpaceGrotesk,
                  fontWeight = FontWeight.Medium,
                  fontSize = 16.sp,
                  letterSpacing = 0.2.sp)

              Box(modifier = Modifier.size(StardomDimensions.HeaderButtonSize))
            }
      }) { innerPadding ->
        val error by viewModel.errorDialog.collectAsState()
        error?.let { ErrorDialog(type = it, action = { viewModel.errorDialog.set(null) }) }

        Box(
            modifier =
                Modifier.fillMaxSize().background(StardomColors.Background).padding(innerPadding)) {
              StardomBackground()

              Column(
                  modifier =
                      Modifier.fillMaxSize()
                          .padding(horizontal = StardomDimensions.ScreenHorizontal),
                  verticalArrangement = Arrangement.Center,
                  horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(StardomColors.Panel)
                                .border(1.dp, StardomColors.BorderStrong)
                                .padding(24.dp)) {
                          Column(
                              horizontalAlignment = Alignment.CenterHorizontally,
                              modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                  Box(
                                      modifier =
                                          Modifier.size(4.dp)
                                              .background(StardomColors.BorderStrong))
                                  Spacer(modifier = Modifier.width(8.dp))
                                  Text(
                                      text = "AUTHENTICATION GATEWAY",
                                      color = StardomColors.TextSecondary,
                                      fontSize = 9.sp,
                                      fontFamily = IbmPlexMono,
                                      letterSpacing = 2.sp)
                                  Spacer(modifier = Modifier.width(8.dp))
                                  Box(
                                      modifier =
                                          Modifier.size(4.dp)
                                              .background(StardomColors.BorderStrong))
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text =
                                        stringResource(id = R.string.stardom_login_title)
                                            .uppercase(),
                                    color = StardomColors.TextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = SpaceGrotesk,
                                    letterSpacing = 1.sp,
                                    textAlign = TextAlign.Center)

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = stringResource(id = R.string.stardom_login_explanation),
                                    color = StardomColors.TextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = IbmPlexMono,
                                    lineHeight = 16.sp,
                                    textAlign = TextAlign.Center)

                                Spacer(modifier = Modifier.height(24.dp))

                                Box(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .height(1.dp)
                                            .background(StardomColors.Border))

                                Spacer(modifier = Modifier.height(20.dp))

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .testTag("login_button")
                                            .background(StardomColors.Selected)
                                            .clickable(onClickLabel = "Log In") {
                                              viewModel.setControlURL(context, onNavigateHome)
                                            }
                                            .padding(vertical = 14.dp)) {
                                      Text(
                                          text = "AUTHENTICATE VIA AUTHENTIK ❯",
                                          color = StardomColors.Background,
                                          fontSize = 11.sp,
                                          fontWeight = FontWeight.Bold,
                                          fontFamily = SpaceGrotesk,
                                          letterSpacing = 1.sp)
                                    }
                              }
                        }
                  }
            }
      }
}

@Composable
fun LoginWithAuthKeyView(
    onNavigateHome: BackNavigation,
    backToSettings: BackNavigation,
    viewModel: LoginWithAuthKeyViewModel = viewModel()
) {
  Scaffold(
      containerColor = StardomColors.Background,
      topBar = {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(StardomDimensions.TopBarHeight)
                    .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
              Box(
                  modifier =
                      Modifier.size(StardomDimensions.HeaderButtonSize)
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.Border)
                          .clickable(onClickLabel = "Back") { backToSettings() },
                  contentAlignment = Alignment.Center) {
                    Text(
                        text = "❮",
                        color = StardomColors.TextSecondary,
                        fontSize = 14.sp,
                        fontFamily = IbmPlexMono)
                  }

              Text(
                  text = "AUTH KEY",
                  color = StardomColors.TextPrimary,
                  fontFamily = SpaceGrotesk,
                  fontWeight = FontWeight.Medium,
                  fontSize = 16.sp,
                  letterSpacing = 0.2.sp)

              Box(modifier = Modifier.size(StardomDimensions.HeaderButtonSize))
            }
      }) { innerPadding ->
        val error by viewModel.errorDialog.collectAsState()
        val strings =
            LoginViewStrings(
                title = stringResource(id = R.string.auth_key_title),
                explanation = stringResource(id = R.string.auth_key_explanation),
                inputTitle = stringResource(id = R.string.auth_key_input_title),
                placeholder = stringResource(id = R.string.auth_key_placeholder),
            )
        error?.let { ErrorDialog(type = it, action = { viewModel.errorDialog.set(null) }) }

        LoginView(
            innerPadding = innerPadding,
            strings = strings,
            onSubmitAction = { viewModel.setAuthKey(it, onNavigateHome) })
      }
}

@Composable
fun LoginView(
    innerPadding: PaddingValues = PaddingValues(16.dp),
    strings: LoginViewStrings,
    onSubmitAction: (String) -> Unit,
) {
  var textVal by remember { mutableStateOf("") }

  Box(
      modifier =
          Modifier.fillMaxSize().background(StardomColors.Background).padding(innerPadding)) {
        StardomBackground()

        Column(
            modifier =
                Modifier.fillMaxSize().padding(horizontal = StardomDimensions.ScreenHorizontal),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.BorderStrong)
                          .padding(20.dp)) {
                    Column {
                      Text(
                          text = strings.title.uppercase(),
                          color = StardomColors.TextPrimary,
                          fontSize = 14.sp,
                          fontWeight = FontWeight.Bold,
                          fontFamily = SpaceGrotesk,
                          letterSpacing = 1.sp)

                      Spacer(modifier = Modifier.height(4.dp))

                      Text(
                          text = strings.explanation,
                          color = StardomColors.TextSecondary,
                          fontSize = 10.sp,
                          fontFamily = IbmPlexMono,
                          lineHeight = 14.sp)

                      Spacer(modifier = Modifier.height(16.dp))

                      Text(
                          text = strings.inputTitle.uppercase(),
                          color = StardomColors.TextMuted,
                          fontSize = 9.sp,
                          fontFamily = IbmPlexMono,
                          letterSpacing = 1.sp)

                      Spacer(modifier = Modifier.height(6.dp))

                      Box(
                          modifier =
                              Modifier.fillMaxWidth()
                                  .background(StardomColors.Background)
                                  .border(1.dp, StardomColors.Border)
                                  .padding(horizontal = 12.dp, vertical = 10.dp)) {
                            BasicTextField(
                                value = textVal,
                                onValueChange = { textVal = it },
                                textStyle =
                                    TextStyle(
                                        color = StardomColors.TextPrimary,
                                        fontSize = 12.sp,
                                        fontFamily = IbmPlexMono),
                                cursorBrush = SolidColor(StardomColors.TextPrimary),
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        imeAction = ImeAction.Go),
                                keyboardActions =
                                    KeyboardActions(onGo = { onSubmitAction(textVal) }),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { innerTextField ->
                                  if (textVal.isEmpty()) {
                                    Text(
                                        text = strings.placeholder,
                                        color = StardomColors.TextMuted,
                                        fontSize = 11.sp,
                                        fontFamily = IbmPlexMono)
                                  }
                                  innerTextField()
                                })
                          }

                      Spacer(modifier = Modifier.height(20.dp))

                      Box(
                          contentAlignment = Alignment.Center,
                          modifier =
                              Modifier.fillMaxWidth()
                                  .background(StardomColors.Selected)
                                  .clickable(onClickLabel = "Submit Auth Key") {
                                    onSubmitAction(textVal)
                                  }
                                  .padding(vertical = 12.dp)) {
                            Text(
                                text = "SUBMIT KEY ❯",
                                color = StardomColors.Background,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = SpaceGrotesk,
                                letterSpacing = 1.sp)
                          }
                    }
                  }
            }
      }
}
