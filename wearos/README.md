# Watch Bridge for Wear OS — v0.3.0

Tested by the project owner on Samsung Galaxy Watch4. Wear OS application and the Home Assistant integration remain independently upgradable.

## Features
- Heart rate and SpO₂ measurement, local history and MQTT transport
- Steps from the Android step-counter sensor, local daily history, MQTT on changes and manual resend
- Body composition (BIA) measurement and rounded local history; MQTT BIA metrics
- ECG measurement, waveform display, local CSV export (3 decimal places), experimental R-peak timing markers sent over MQTT
- Independent MQTT and Bluetooth transport switches
- Nine appearance themes, body profile for BIA
- Home Assistant integration can store BIA and ECG metadata and experimental beat markers

## Settings
On watch: **Настройки**.
- **Настройки приложения** — theme only
- **Параметры тела** — body profile for BIA
- **MQTT / Home Assistant** — toggle transmission, broker hostname, port, username, password, TLS, connection test
- **Bluetooth** — independent BLE transmission switch and status

## MQTT
Topic: `rogbid/<device_id>/history/<kind>`, QoS 1.
Kinds include `heart`, `oxygen`, `steps`, `bia`, `ecg`, and `ecg_beats`.
ECG raw waveform is **not sent over MQTT**. The `ecg_beats` message contains experimental beat offsets in integer milliseconds, **not medically validated R-peaks**. Full signal remains on-watch in CSV.

TLS defaults to port 8883. For local plain TCP use port 1883 with TLS disabled; do not expose unencrypted MQTT to the internet.
MQTT runs only while the application process is alive; durable background delivery and offline replay are not yet implemented.
Credentials are in Android private application preferences, not an encrypted credential store.

## Bluetooth
BLE GATT service: `f3e9b8c0-4471-4a42-a14b-6f2f7a91c001`.
- `...c002`: legacy SpO₂/pulse characteristic
- `...c003`: latest journal JSON, readable with GATT offsets; single-byte update notification

BLE currently lacks a guaranteed offline queue and a Home Assistant receiver for the JSON journal characteristic. This is planned for subsequent releases.

## Known limitations and next steps
- Samsung Health authoritative steps and sleep history are not yet integrated; calories and distance in step messages are placeholders.
- ECG peak extraction is experimental; it must not be used for diagnosis.
- Reliable background syncing, BLE ingestion and richer Home Assistant history cards are planned.

## Build
Signed APK: [GitHub Actions](https://github.com/immortalserg/Watch-bridge/actions/workflows/wearos.yml).
