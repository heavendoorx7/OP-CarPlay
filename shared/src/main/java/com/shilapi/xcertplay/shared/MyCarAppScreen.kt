package com.shilapi.xcertplay.shared

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

class MyCarAppScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder("Hardware transport is not configured. Board I2C needs a /dev/i2c-N path and OS/SELinux permission; CH341 needs deployed VID/PID configuration.")
            .setHeaderAction(Action.APP_ICON)
            .setTitle("xcertplay hardware status")
            .build()
    }
}
