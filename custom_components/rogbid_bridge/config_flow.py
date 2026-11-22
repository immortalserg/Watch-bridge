"""UI configuration for Rogbid Bridge: discover watches from MQTT."""
import asyncio
import re

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.components import mqtt

from .const import DOMAIN, CONF_DEVICE_ID

WATCH_TOPIC = re.compile(r"^rogbid/((?:z10|wearos)_[a-f0-9]{32})/state$")
DISCOVERY_SECONDS = 3


class RogbidBridgeConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Select a watch seen on MQTT or enter its device ID manually."""

    VERSION = 1

    async def _discover_watches(self):
        found = set()

        def on_state(message):
            match = WATCH_TOPIC.fullmatch(message.topic)
            if match:
                found.add(match.group(1))

        unsubscribe = None
        try:
            unsubscribe = await mqtt.async_subscribe(
                self.hass, "rogbid/+/state", on_state, 0
            )
            # The watch state is retained; broker replays it on subscribe.
            await asyncio.sleep(DISCOVERY_SECONDS)
        except Exception:
            # Keep manual entry available even if MQTT is not yet connected.
            pass
        finally:
            if unsubscribe is not None:
                unsubscribe()
        return sorted(found)

    async def async_step_user(self, user_input=None):
        errors = {}
        if user_input is not None:
            manual_id = (user_input.get("manual_device_id") or "").strip()
            selected_id = (user_input.get(CONF_DEVICE_ID) or "").strip()
            device_id = manual_id or selected_id
            if not re.fullmatch(r"[A-Za-z0-9_-]+", device_id):
                errors["base"] = "invalid_device_id"
            else:
                await self.async_set_unique_id(device_id)
                self._abort_if_unique_id_configured()
                return self.async_create_entry(
                    title=f"{'Wear OS' if device_id.startswith('wearos_') else 'Rogbid'} {device_id[-8:]}",
                    data={CONF_DEVICE_ID: device_id},
                )

        watches = await self._discover_watches()
        if watches:
            schema = vol.Schema({
                vol.Optional(CONF_DEVICE_ID): vol.In({
                    device_id: f"{'Wear OS' if device_id.startswith('wearos_') else 'Rogbid Z10'} · {device_id[-8:]}"
                    for device_id in watches
                }),
                vol.Optional("manual_device_id"): str,
            })
        else:
            schema = vol.Schema({vol.Required(CONF_DEVICE_ID): str})
        return self.async_show_form(
            step_id="user", data_schema=schema, errors=errors
        )
