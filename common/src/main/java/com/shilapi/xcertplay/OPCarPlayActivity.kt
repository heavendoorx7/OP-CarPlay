package com.shilapi.xcertplay

/**
 * Fresh component identity: E28 caches manifest system-bar policy by component
 * in system_server across APK updates. The earlier Host policy was always-show.
 * Reuse all host behavior and preferences without rebooting the vehicle.
 */
class OPCarPlayActivity : CarPlayHostActivity()
