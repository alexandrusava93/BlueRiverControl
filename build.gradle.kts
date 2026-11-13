
buildscript {
    configurations.classpath {
        resolutionStrategy {
            // Build-ul pica daca Gradle cere un kotlin-stdlib care nu are jar publicat
            force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
            force("org.jetbrains.kotlin:kotlin-reflect:2.0.21")
        }
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
