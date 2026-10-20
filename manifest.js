// Permissions this PC asks the TV for when pairing.
module.exports = {
  manifestVersion: 1,
  appVersion: "1.0",
  signed: {
    appId: "com.claude.tvtest",
    vendorId: "com.claude",
    created: "20261020",
    localizedAppNames: { "": "Claude TV Remote" },
    localizedVendorNames: { "": "Claude" },
    permissions: ["CONTROL_POWER", "READ_INSTALLED_APPS", "LAUNCH", "CONTROL_AUDIO",
      "CONTROL_DISPLAY", "CONTROL_INPUT_TV", "READ_TV_CURRENT_TIME", "READ_CURRENT_CHANNEL"],
    serial: "claude0001",
  },
  permissions: ["LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO", "CONTROL_DISPLAY",
    "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER",
    "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS",
    "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "READ_POWER_STATE", "READ_COUNTRY_INFO",
    "WRITE_NOTIFICATION_TOAST", "READ_NETWORK_STATE", "READ_UPDATE_INFO",
    "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_TV_SCREEN", "READ_LGE_SDX", "READ_NOTIFICATIONS",
    "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_ALERT", "CONTROL_INPUT_TEXT"],
  signatures: [{ signatureVersion: 1, signature: "none" }],
};
