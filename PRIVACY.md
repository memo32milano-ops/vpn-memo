# VPN Memo Pro – Privacy Policy

_Last updated: 13 November 2026_

VPN Memo Pro ("the app", package `com.pro.vpnmemo`) is published on Google Play by
**Venti Studios**.

## What the app does
The app creates an encrypted VPN tunnel (OpenVPN, using Android's `VpnService`)
between your device and a public VPN server you choose. While the VPN is on, your
device's internet traffic goes through that server. This is the app's only purpose.

The servers are **not operated by us**. They are free public servers run by
independent volunteers of the **VPN Gate Academic Experiment** (University of
Tsukuba, Japan – https://www.vpngate.net).

## Data we collect
**None.** The app has no account, no advertising, no analytics and no crash-reporting
SDKs. We do not collect, store, sell or share:
* your browsing history or traffic,
* your IP address or location,
* contacts, files, or any personal information.

Your settings (for example the last tab you used) are stored only on your device.

## Network requests made by the app
* It downloads the public VPN Gate server list from `www.vpngate.net`.
  This is a plain download; no personal data is sent.
* It briefly opens a TCP connection to the listed servers to check that they are
  online and to measure their response time. No data is exchanged.
* When you connect, all your traffic is encrypted between your device and the
  selected VPN Gate server.

## Logging by the VPN servers (please read)
Because the servers are run by volunteers, their handling of data is governed by the
VPN Gate service, not by us. According to VPN Gate's published policy, server
operators and VPN Gate keep **connection logs** (for example your IP address and the
time of connection) for a limited period, and may disclose them where required by law.
Therefore:
* Do not use the service for any illegal activity.
* Do not rely on it for strong anonymity.

## Permissions
* `INTERNET`, `ACCESS_NETWORK_STATE` – to connect and to detect network changes.
* `BIND_VPN_SERVICE` / VPN permission – to create the VPN tunnel (Android asks you first).
* `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` – to keep the VPN running and show its status.
* `RECEIVE_BOOT_COMPLETED` – only used if you enable "Always-on VPN" in Android settings.

## Children
The app is not directed to children under 13.

## Open source
The app is based on "OpenVPN for Android" by Arne Schwabe (GNU GPL v2). Source code:
https://github.com/memo32milano-ops/vpn-memo

## Contact
Please open an issue at https://github.com/memo32milano-ops/vpn-memo/issues
