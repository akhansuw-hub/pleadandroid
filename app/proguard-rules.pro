# Plead release rules (minify is off for now; kept for when R8 is enabled).
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class app.plead.android.**$$serializer { *; }
-keepclassmembers class app.plead.android.** { *** Companion; }
