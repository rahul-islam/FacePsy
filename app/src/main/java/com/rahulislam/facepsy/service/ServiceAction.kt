package com.rahulislam.facepsy.service

/**
 * Commands understood by [SensingService.onStartCommand], sent as the intent action
 * (`ServiceAction.X.name`).
 */
enum class ServiceAction {
    START,
    STOP
}
