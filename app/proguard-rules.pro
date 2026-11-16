# CapsulePal release rules

# Room generated implementations
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# Lunar calendar library (reflection-free, but keep public API names for clarity)
-keep class com.nlf.calendar.** { *; }

# Keep notification receiver / service entry points
-keep class com.capsulepal.app.scheduler.** { *; }
-keep class com.capsulepal.app.service.** { *; }
-keep class com.capsulepal.app.signals.MediaNotificationListenerService { *; }
