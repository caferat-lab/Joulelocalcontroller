# Joule Local Controller — Android MVP

This is a native Android Studio project for the **original ChefSteps Joule**.

It is designed for local Bluetooth control and does not require the Joule cloud service or a Joule/Breville account.

## What this MVP does

- Scans for the original Joule over BLE.
- Connects to the Joule service.
- Enables the Service Changed indication and the Joule data notification channel.
- Performs the Joule application-level authorization handshake.
- Saves the per-device authorization key locally.
- Starts the live feed.
- Decodes and displays current water temperature in °C and °F.
- Includes start/stop cook commands using the documented manual-program format.

## Important

The Joule protocol is not a generic Bluetooth thermometer. The device uses Protocol Buffers over BLE and performs its own authorization handshake. On first connection, the Joule may flash its top button; press it when prompted.

The protocol implementation is based on publicly documented interoperability work and the hardware-validated `ha-joule` project. The Joule's BLE UUIDs and protocol sequence are documented in the sources listed below.

## Build

Open this folder in Android Studio, let Gradle sync, then run the `app` configuration on an Android phone with Bluetooth Low Energy.

No external Android libraries beyond the Android SDK and AndroidX Core are required by the app code.

## Current limitation

The Android BLE stack's handling of writes larger than the negotiated ATT payload can vary by Android/device firmware. The authorization message is 24 bytes and may require a long-write path on some phones. If your phone connects but the Joule never responds to the authorization request, the next version should add an explicit ATT Prepare Write / Execute Write implementation.

The cook timer fields are intentionally not sent as a device cook-time field in this MVP. The known working manual-program format uses the target temperature and app/device state; the UI timer is reserved for the next iteration.

## Sources

- ChefSteps Bluetooth-only support: https://support.chefsteps.com/hc/en-us/articles/36606559991319-Pairing-ChefSteps-Joule-to-the-Breville-app-using-Bluetooth-only
- Hardware-validated local Joule protocol research: https://notebook.catorcini.com/the-two-byte-bug/
- Open-source Home Assistant Joule integration: https://github.com/acato/ha-joule


## Build without Android Studio

This project includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`. Upload the project to a GitHub repository, then use **Actions → Build Joule APK → Run workflow**. The resulting `app-debug.apk` is available as a workflow artifact and can be installed on an Android phone.

The APK is an unsigned debug build intended for personal sideloading/testing.
