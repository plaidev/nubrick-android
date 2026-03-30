# Keep package names stable for className-based crash-origin checks.
-keeppackagenames io.nubrick.nubrick

# Keep SDK symbol names while still allowing unused code shrinking.
-keep,allowshrinking class io.nubrick.nubrick.**
-keepclassmembers,allowshrinking class io.nubrick.nubrick.** { *; }

# Bridge APIs can be invoked indirectly, so keep them hard.
-keep @io.nubrick.nubrick.FlutterBridgeApi class * { *; }
-keepclassmembers class * {
    @io.nubrick.nubrick.FlutterBridgeApi *;
}
