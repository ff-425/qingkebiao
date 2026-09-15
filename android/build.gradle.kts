// 版本钉死成一组已知能互相兼容的组合：
// AGP 8.5.2 需要 Gradle 8.7+（CI 用 8.9）；Kotlin 2.0 起 Compose 编译器
// 由 org.jetbrains.kotlin.plugin.compose 提供，不再写 composeOptions。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
