# Keep Nubrick symbols readable in crash traces while still allowing shrinking and optimization.
-keep,allowshrinking,allowoptimization class app.nubrick.nubrick.** { *; }
