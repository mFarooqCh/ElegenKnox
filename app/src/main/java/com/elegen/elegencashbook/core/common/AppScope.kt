package com.elegen.elegencashbook.core.common

import javax.inject.Qualifier

/** Application-lifetime CoroutineScope for singletons that must observe app-wide streams. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope
