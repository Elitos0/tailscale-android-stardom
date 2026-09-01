// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

enum class VpnState {
  DISCONNECTED,
  RESOLVING_STAR_ROUTE,
  HANDSHAKING_CIPHER,
  AUTHENTICATING_NODE,
  SECURED,
  DISCONNECTING,
  ERROR;

  val isConnected: Boolean
    get() = this == SECURED

  val isConnecting: Boolean
    get() =
        this == RESOLVING_STAR_ROUTE || this == HANDSHAKING_CIPHER || this == AUTHENTICATING_NODE

  val isError: Boolean
    get() = this == ERROR
}

enum class ConnectionMode {
  AUTO,
  MANUAL
}

enum class AppLanguage(val title: String, val code: String) {
  RU("Русский", "RU"),
  EN("English", "EN")
}

enum class VpnProtocol(
    val displayName: String,
    val cipher: String,
    val port: Int,
    val enabled: Boolean = true
) {
  WIREGUARD("WireGuard® High-Throughput", "ChaCha20-Poly1305", 51820, enabled = true),
  SHADOWSOCKS_2022("Shadowsocks-2022 Blind", "AEAD-2022-Blake3", 8388, enabled = false),
  V2RAY_VMESS("V2Ray / VMess Over TLS", "AES-128-GCM", 443, enabled = false),
  IKEV2_IPSEC("IKEv2 / IPsec StrongSwan", "AES-256-GCM", 500, enabled = false)
}

enum class DnsProvider(val displayName: String, val address: String) {
  STARDOM_ZERO_KNOWLEDGE("Stardom Zero-Knowledge (RAM-Only)", "10.64.0.1"),
  CLOUDFLARE_DOH("Cloudflare 1.1.1.1 (DoH)", "1.1.1.1"),
  QUAD9_SECURE("Quad9 Secure Filtered", "9.9.9.9"),
  CUSTOM_ENCRYPTED("Custom Encrypted DNS", "Custom")
}

data class StarServerNode(
    val id: String,
    val starName: String,
    val constellation: String,
    val city: String,
    val countryCode: String,
    val coordinates: String,
    val basePingMs: Int,
    val loadPercent: Int,
    val ipAddress: String,
    val cipher: String = "ChaCha20-Poly1305",
    val isStarred: Boolean = false
)

data class AccountProfile(
    val accountId: String = "STAR-4096-ALPHA",
    val tier: String = "ORBITAL APEX // PRO",
    val publicKey: String = "ed25519:7a4f89d31ce02b66",
    val validUntil: String = "2028.12.31",
    val activeDevices: Int = 1,
    val maxDevices: Int = 5,
    val bandwidthUsedGb: Double = 0.0,
    val totalQuota: String = "UNLIMITED",
    val isStub: Boolean = false
)

data class TelemetryState(
    val currentPingMs: Int = 24,
    val downloadSpeedMbps: Float = 0.0f,
    val uploadSpeedMbps: Float = 0.0f,
    val totalDownloadedMb: Double = 0.0,
    val totalUploadedMb: Double = 0.0,
    val sessionDurationSeconds: Long = 0L,
    val assignedVirtualIp: String = "100.64.0.1"
)

object StardomLocalization {

  fun isSecured(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "СОЕДИНЕНИЕ // АКТИВНО"
        AppLanguage.EN -> "LINK // SECURED"
      }

  fun isOffline(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "УЗЕЛ // ОФФЛАЙН"
        AppLanguage.EN -> "NODE // OFFLINE"
      }

  fun isError(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "СБОЙ // ДОСТУП ОГРАНИЧЕН"
        AppLanguage.EN -> "ERROR // ACCESS RESTRICTED"
      }

  fun statusHeader(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ТЕКУЩИЙ СТАТУС"
        AppLanguage.EN -> "CURRENT STATUS"
      }

  fun powerButtonAction(state: VpnState, lang: AppLanguage): String =
      when (state) {
        VpnState.DISCONNECTED -> if (lang == AppLanguage.RU) "ПОДКЛЮЧИТЬ" else "INITIALIZE"
        VpnState.RESOLVING_STAR_ROUTE,
        VpnState.HANDSHAKING_CIPHER,
        VpnState.AUTHENTICATING_NODE ->
            if (lang == AppLanguage.RU) "ИНИЦИАЛИЗАЦИЯ..." else "INITIALIZING..."
        VpnState.SECURED -> if (lang == AppLanguage.RU) "ОТКЛЮЧИТЬ" else "TERMINATE"
        VpnState.DISCONNECTING -> if (lang == AppLanguage.RU) "ОТКЛЮЧЕНИЕ..." else "CLOSING..."
        VpnState.ERROR -> if (lang == AppLanguage.RU) "ПОВТОРИТЬ" else "RETRY"
      }

  fun powerButtonStatus(state: VpnState, lang: AppLanguage): String =
      when (state) {
        VpnState.DISCONNECTED -> if (lang == AppLanguage.RU) "ОТКЛЮЧЕНО" else "DE-ORBITED"
        VpnState.RESOLVING_STAR_ROUTE ->
            if (lang == AppLanguage.RU) "ПОИСК МАРШРУТА" else "RESOLVING ROUTE"
        VpnState.HANDSHAKING_CIPHER ->
            if (lang == AppLanguage.RU) "РУКОПОЖАТИЕ ШИФРА" else "CIPHER HANDSHAKE"
        VpnState.AUTHENTICATING_NODE ->
            if (lang == AppLanguage.RU) "АВТОРИЗАЦИЯ УЗЛА" else "NODE AUTHENTICATION"
        VpnState.SECURED -> if (lang == AppLanguage.RU) "НА ОРБИТЕ" else "IN ORBIT"
        VpnState.DISCONNECTING -> if (lang == AppLanguage.RU) "РАЗРЫВ СВЯЗИ" else "DE-ORBITING"
        VpnState.ERROR -> if (lang == AppLanguage.RU) "СБОЙ СВЯЗИ" else "LINK ERROR"
      }

  fun modeRoutingTag(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "МАРШРУТИЗАЦИЯ"
        AppLanguage.EN -> "ROUTING"
      }

  fun modeRoutingTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "АВТО: БЫСТРЫЙ"
        AppLanguage.EN -> "AUTO: STAR ROUTE"
      }

  fun modeRoutingSubtitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "НИЗКИЙ ПИНГ"
        AppLanguage.EN -> "LOWEST LATENCY"
      }

  fun modeNodeTag(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "УЗЕЛ"
        AppLanguage.EN -> "NODE"
      }

  fun modeNodeTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ВЫБОР УЗЛА"
        AppLanguage.EN -> "MANUAL: STELLAR"
      }

  fun modeNodeSubtitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ВЫБОР УЗЛА"
        AppLanguage.EN -> "SELECT NODE"
      }

  fun activeNodeTag(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "АКТИВНЫЙ УЗЕЛ"
        AppLanguage.EN -> "ACTIVE NODE"
      }

  fun autoRouteTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "АВТО: НАИМЕНЬШИЙ ПИНГ"
        AppLanguage.EN -> "AUTO: LOWEST LATENCY"
      }

  fun autoRouteBadge(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "АВТО МАРШРУТ"
        AppLanguage.EN -> "AUTO ROUTE"
      }

  fun loadLabel(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "НАГРУЗКА"
        AppLanguage.EN -> "LOAD"
      }

  // Settings
  fun settingsTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "НАСТРОЙКИ СЕТИ И БЕЗОПАСНОСТИ"
        AppLanguage.EN -> "STELLAR ROUTING & SECURITY CONFIG"
      }

  fun settingsSubtitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ШИФРОВАНИЕ И ЗАЩИТА КАНАЛА"
        AppLanguage.EN -> "KERNEL CIPHER & HARDWARE DEFENSE"
      }

  fun doneBtn(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ГОТОВО ✕"
        AppLanguage.EN -> "DONE ✕"
      }

  fun languageSection(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ЯЗЫК ИНТЕРФЕЙСА"
        AppLanguage.EN -> "INTERFACE LANGUAGE"
      }

  fun protocolSection(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ПРОТОКОЛ ШИФРОВАНИЯ"
        AppLanguage.EN -> "ENCRYPTION PROTOCOL ENGINE"
      }

  fun dnsSection(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "DNS-РЕЗОЛВЕР И ПРИВАТНОСТЬ"
        AppLanguage.EN -> "RESOLVER & DNS ZERO-KNOWLEDGE"
      }

  fun securitySection(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ПАРАМЕТРЫ ЗАЩИТЫ"
        AppLanguage.EN -> "HARDWARE LOCKS & MASKING"
      }

  fun activeStatus(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "АКТИВЕН"
        AppLanguage.EN -> "ACTIVE"
      }

  fun comingSoonStatus(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "СКОРО"
        AppLanguage.EN -> "COMING SOON"
      }

  fun lockedStatus(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ВЫБРАН"
        AppLanguage.EN -> "LOCKED"
      }

  fun dnsManagedStatus(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "УПРАВЛЯЕТСЯ МАРШРУТОМ"
        AppLanguage.EN -> "MANAGED // SECURE ROUTE"
      }

  fun killSwitchTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "KILL SWITCH // БЛОКИРОВКА УТЕЧЕК"
        AppLanguage.EN -> "KILL SWITCH // ABSOLUTE LOCK"
      }

  fun killSwitchDesc(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "Блокировать незашифрованный трафик при обрыве соединения"
        AppLanguage.EN -> "Block non-encrypted socket traffic if celestial connection drops"
      }

  fun dnsGuardTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ЗАЩИТА ОТ УТЕЧЕК DNS"
        AppLanguage.EN -> "DNS LEAK DEFENSE"
      }

  fun dnsGuardDesc(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "Маршрутизация всех DNS-запросов через защищенные RAM-туннели"
        AppLanguage.EN -> "Route all host lookup requests through encrypted RAM tunnels"
      }

  fun obfuscationTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "МАСКИРОВКА ТРАФИКА (OBFUSCATION)"
        AppLanguage.EN -> "STEALTH SCRAMBLER (OBFUSCATION)"
      }

  fun obfuscationDesc(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "Маскировать VPN-трафик под обычный HTTPS TLS 1.3"
        AppLanguage.EN -> "Disguise VPN header signatures as ordinary HTTPS TLS 1.3 traffic"
      }

  fun autoWifiTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "АВТОПОДКЛЮЧЕНИЕ К ПУБЛИЧНЫМ WI-FI"
        AppLanguage.EN -> "AUTO-CONNECT ON UNTRUSTED WI-FI"
      }

  fun autoWifiDesc(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "Автоматически подключать быстрый узел при входе в неизвестные сети"
        AppLanguage.EN -> "Engage optimal constellation node on unknown public SSID links"
      }

  // Account
  fun accountDialogTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ИДЕНТИФИКАТОР STARDOM"
        AppLanguage.EN -> "STARDOM CRYPTOGRAPHIC ID"
      }

  fun accountIdentity(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ИДЕНТИФИКАТОР УЗЛА"
        AppLanguage.EN -> "NODE IDENTITY"
      }

  fun accountTier(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ТАРИФНЫЙ ПЛАН"
        AppLanguage.EN -> "CONSTELLATION TIER"
      }

  fun accountPublicKey(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ХЭШ ПУБЛИЧНОГО КЛЮЧА"
        AppLanguage.EN -> "PUBLIC KEY HASH"
      }

  fun accountActiveDevices(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "АКТИВНЫЕ УСТРОЙСТВА"
        AppLanguage.EN -> "ACTIVE SATELLITE LINKS"
      }

  fun accountDevicesSuffix(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "УСТРОЙСТВ"
        AppLanguage.EN -> "LINKS"
      }

  fun accountDataTransferred(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ПЕРЕДАНО ДАННЫХ"
        AppLanguage.EN -> "DATA TRANSFERRED"
      }

  fun accountUnlimited(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "БЕЗЛИМИТНО"
        AppLanguage.EN -> "UNLIMITED"
      }

  fun accountValidUntil(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ДЕЙСТВУЕТ ДО"
        AppLanguage.EN -> "SUBSCRIPTION HORIZON"
      }

  fun accountStubNotice(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ПРОФИЛЬ: ВРЕМЕННЫЙ СТАТУС (МЕТАДАННЫЕ УЗЛА)"
        AppLanguage.EN -> "PROFILE: TEMPORARY STUB (NODE METADATA)"
      }

  fun logoutBtn(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ВЫЙТИ ИЗ АККАУНТА"
        AppLanguage.EN -> "LOG OUT"
      }

  fun copyKeyBtn(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "СКОПИРОВАТЬ КЛЮЧ"
        AppLanguage.EN -> "COPY PUBLIC KEY"
      }

  fun confirmBtn(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ЗАКРЫТЬ ❯"
        AppLanguage.EN -> "CONFIRM ❯"
      }

  fun keyCopiedToast(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "Публичный ключ скопирован"
        AppLanguage.EN -> "Public key copied to clipboard"
      }

  // Server Directory
  fun serverDirectoryTitle(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "СПИСОК СЕРВЕРОВ"
        AppLanguage.EN -> "CONSTELLATION NODE NETWORK"
      }

  fun serverDirectorySubtitle(count: Int, lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "$count АКТИВНЫХ УЗЛОВ ОНЛАЙН"
        AppLanguage.EN -> "$count ACTIVE RELAY NODES ONLINE"
      }

  fun searchPrefix(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ПОИСК >"
        AppLanguage.EN -> "SEARCH >"
      }

  fun searchPlaceholder(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ПОИСК ПО УЗЛУ ИЛИ ГОРОДУ..."
        AppLanguage.EN -> "FILTER BY STAR OR CITY..."
      }

  fun closeBtn(lang: AppLanguage): String =
      when (lang) {
        AppLanguage.RU -> "ЗАКРЫТЬ ✕"
        AppLanguage.EN -> "CLOSE ✕"
      }
}
