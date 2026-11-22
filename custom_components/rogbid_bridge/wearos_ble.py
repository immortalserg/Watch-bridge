"""Read latest Wear OS Watch Bridge GATT journal after verifying device identity.

Initial BLE transport: latest record only, not a historical replay protocol.
"""
import asyncio
import json
import logging
import time

from bleak import BleakClient
from homeassistant.components import bluetooth

_LOGGER = logging.getLogger(__name__)
SERVICE_UUID = "f3e9b8c0-4471-4a42-a14b-6f2f7a91c001"
JOURNAL_UUID = "f3e9b8c0-4471-4a42-a14b-6f2f7a91c003"
IDENTITY_UUID = "f3e9b8c0-4471-4a42-a14b-6f2f7a91c004"
KINDS = frozenset(("heart", "oxygen", "steps", "bia", "ecg", "ecg_beats"))


class WearOsBleReceiver:
    def __init__(self, hass, device_id, journal, on_record):
        self.hass = hass
        self.device_id = device_id
        self.journal = journal
        self.on_record = on_record
        self.task = None
        self.last_attempt = 0.0

    def on_advertisement(self, service_info):
        if not service_info.connectable:
            return
        if self.task and not self.task.done():
            return
        seen = getattr(service_info, "time", None)
        if isinstance(seen, (int, float)) and time.monotonic() - seen > 15:
            return
        # BLE addresses may rotate; verify identity after connecting.
        if time.monotonic() - self.last_attempt < 30:
            return
        self.last_attempt = time.monotonic()
        self.task = self.hass.async_create_task(self._read_latest(service_info.address))

    async def _read_latest(self, address):
        client = None
        try:
            device = bluetooth.async_ble_device_from_address(
                self.hass, address, connectable=True
            )
            if device is None:
                return
            client = BleakClient(device, timeout=15.0)
            await asyncio.wait_for(client.connect(), timeout=20)
            if client.services.get_service(SERVICE_UUID) is None:
                return
            identity = bytes(await asyncio.wait_for(
                client.read_gatt_char(IDENTITY_UUID), timeout=10
            )).decode("utf-8")
            if identity != self.device_id:
                _LOGGER.debug("Wear OS BLE identity mismatch for %s", address)
                return
            raw = bytes(await asyncio.wait_for(
                client.read_gatt_char(JOURNAL_UUID), timeout=10
            ))
            if not raw or len(raw) > 4096:
                return
            record = json.loads(raw.decode("utf-8"))
            if not isinstance(record, dict):
                return
            kind = record.get("kind")
            record_id = record.get("id")
            measured_at = record.get("measured_at")
            if (kind not in KINDS or not isinstance(record_id, str)
                    or not 0 < len(record_id) <= 200
                    or not isinstance(measured_at, str)
                    or not 0 < len(measured_at) <= 64):
                return
            inserted = await self.hass.async_add_executor_job(
                self.journal.insert, self.device_id, kind,
                record_id, measured_at, record
            )
            if inserted:
                self.on_record(kind, record)
                _LOGGER.info("Wear OS BLE saved %s record for %s", kind, self.device_id)
        except asyncio.CancelledError:
            raise
        except (Exception,) as exc:
            _LOGGER.debug("Wear OS BLE read failed for %s: %s", address, exc)
        finally:
            if client is not None and client.is_connected:
                try:
                    await asyncio.wait_for(client.disconnect(), timeout=10)
                except Exception:
                    pass

    def close(self):
        if self.task is not None and not self.task.done():
            self.task.cancel()
