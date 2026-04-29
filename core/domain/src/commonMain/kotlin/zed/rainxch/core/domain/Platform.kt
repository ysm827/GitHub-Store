package zed.rainxch.core.domain

import zed.rainxch.core.domain.model.Platform

expect fun getPlatform(): Platform

expect fun getOsVersion(): String

expect fun getSystemLocaleTag(): String
