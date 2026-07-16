# kotlinx.serialization keeps generated serializers; keep them for models.
-keepclassmembers class eu.euroswarms.surgeon.** {
    *** Companion;
}
-keepclasseswithmembers class eu.euroswarms.surgeon.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class eu.euroswarms.surgeon.**$$serializer { *; }
