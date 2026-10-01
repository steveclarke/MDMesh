package com.mdmesh.core.di

import javax.inject.Qualifier

/** An independent HTTP client for APK origins, without management-server interceptors or state. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApkDownloadClient
