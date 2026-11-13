# VPN Memo Pro

Free Android VPN client for the public **VPN Gate** volunteer servers
(VPN Gate Academic Experiment, University of Tsukuba, Japan).

* Real encrypted OpenVPN tunnel (Android `VpnService`).
* Downloads the public VPN Gate server list and checks every server, so only
  servers that answer right now are shown. If a server stops answering while
  connecting, the app automatically moves to the next fastest one.
* Tabs: fastest servers, Arab-country servers (when volunteers offer them), all countries.
* No account, no ads, no analytics, no data collection.

Privacy policy: [PRIVACY.md](PRIVACY.md)

## License

This app is based on **OpenVPN for Android** (ics-openvpn) by Arne Schwabe and on
OpenVPN. Like the original, it is distributed under the **GNU GPL v2** with the
additional terms in [doc/LICENSE.txt](doc/LICENSE.txt). The original README is kept in
[README-ics-openvpn.md](README-ics-openvpn.md).

The VPN Memo Pro code lives in `main/src/skeleton/java/com/pro/vpnmemo/`.

## Build

```
git clone --recurse-submodules https://github.com/memo32milano-ops/vpn-memo
./gradlew bundleSkeletonOvpn2Release
```
