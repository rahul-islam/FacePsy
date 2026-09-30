package com.rahulislam.facepsy.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Returns true if every permission in [permissionList] has been granted. */
fun Context.hasPermissions(permissionList: Array<String>): Boolean {
    for (permission in permissionList) {
        if (!hasPermission(permission)) return false
    }
    return true
}

/** Returns true if [permission] has been granted. */
fun Context.hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
