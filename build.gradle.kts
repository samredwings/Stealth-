plugins {
    id('com.android.application')
    id('org.jetbrains.kotlin.android')
}

android {
    namespace 'com.monitor.messenger'
    compileSdk 34
    
    defaultConfig {
        applicationId 'com.android.system.service'  // Spoofed package name
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName '1.0'
    }
    
    buildTypes {
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')
        }
    }
}

dependencies {
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.9.0'
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
}
