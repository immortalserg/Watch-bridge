# Gadgetbridge compatibility port

Goal: cover every upstream device family, including non-wearable gadgets, directly from Home Assistant wherever the transport permits. Upstream: https://codeberg.org/Freeyourgadget/Gadgetbridge/ (AGPL-3.0; preserve notices and review license compatibility before porting code).

Keep the existing rogbid_bridge domain and MQTT/BLE journal compatibility. Implement a Python adapter registry with capabilities, discovery, authentication, transport requirements, history cursor, and normalized records. BLE adapters use HA Bluetooth and shared connection management; Classic Bluetooth requires a separate host transport where HA Bluetooth proxies cannot serve it. Never assume a discovered model is supported until its adapter is tested.

Port order: standard GATT heart rate, battery, weight and blood pressure; Xiaomi/Huami; Huawei/Honor; Garmin; FitPro/Da Fit/GloryFit/FitCloud; remaining wearable and non-wearable families. Maintain a versioned upstream model inventory and per-model status (catalogued, discovery, pairing, live data, history, verified). Do not advertise unsupported features.

Preserve local credential storage, user-approved pairing, per-device synchronization and deduplication, and existing device history. Each adapter requires protocol fixtures and tests before enabling automatic discovery. No phone dependency for direct adapters; platform-specific devices may require a separate bridge.

This document is a plan, not a claim of implemented device compatibility.
