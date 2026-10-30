# O WorkManager cria o banco Room e o trabalho por REFLEXÃO (o incidente da
# 0.13.0 do Sereno, D75): sem estas regras o R8 tira os construtores.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Health Connect: os tipos de registro são usados por KClass e pelo SDK.
-keep class androidx.health.connect.client.** { *; }
-dontwarn androidx.health.connect.client.**
