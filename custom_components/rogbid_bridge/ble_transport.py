"""Shared Home Assistant BLE connection helper for Watch Bridge devices.

Protocol-specific GATT reads remain in their respective receivers.
"""
import asyncio
import logging

from bleak import BleakClient
from bleak_retry_connector import establish_connection
from homeassistant.components import bluetooth

_LOGGER = logging.getLogger(__name__)


async def connect_watch(hass, address: str, device_name: str, *, timeout: float = 45):
    """Connect through HA's selected local adapter or ESPHome Bluetooth proxy.

    Always resolve the BLEDevice from Home Assistant's Bluetooth manager; never
    instantiate BleakClient with a raw MAC address.
    """
    device = bluetooth.async_ble_device_from_address(
        hass, address, connectable=True
    )
    if device is None:
        _LOGGER.debug("Watch Bridge BLE: no connectable route for %s", address)
        return None
    _LOGGER.debug("Watch Bridge BLE: connecting to %s (%s)", address, device_name)
    client = await asyncio.wait_for(
        establish_connection(BleakClient, device, device_name, max_attempts=3),
        timeout=timeout,
    )
    _LOGGER.debug("Watch Bridge BLE: connected to %s", address)
    return client
