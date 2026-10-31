# Release builds use R8 and resource shrinking. Library-provided consumer rules remain active.
# Keep metadata used by generated/reflection-based Android, Hilt, Room and Google libraries.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Room constructs database implementations by generated name. DAOs/entities and their
# generated adapters are retained conservatively to protect existing database/restore paths.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-keep class com.hisabkhata.data.local.** { *; }

# Transfer/backup APIs are shared with the separate device test APK. Preserve
# names and members so tests exercise the release implementation, not copies.
-keep class com.hisabkhata.data.transfer.** { *; }
-keep class com.hisabkhata.domain.backup.** { *; }
-keep class com.hisabkhata.domain.interchange.** { *; }

# Hilt/Dagger generated component and factory entry points; their consumer rules also apply.
-keep class dagger.hilt.** { *; }
-keep class **_HiltComponents* { *; }
-keep class **_HiltModules* { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }

# AndroidX test runner references tracing from the target APK during optional
# minified-release instrumentation. Keep its API available to the runner.
-keep class androidx.tracing.** { *; }

# Future WorkManager workers (none are currently implemented by this app).
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Google Identity / Play Services authorization and the app's direct HTTPS Drive client.
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.api.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.hisabkhata.data.drive.** { *; }

# Gson is used for the explicit backup/Drive JSON tree. FastExcel writes OOXML; Excel
# import uses Android SAX/ZIP, and PDF export uses android.graphics.pdf.PdfDocument.
-keep class com.google.gson.** { *; }
-keep class org.dhatim.fastexcel.** { *; }
