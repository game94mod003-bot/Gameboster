# libbox is driven through its Java API using reflection; keep its reflected
# SetupOptions, PlatformInterface, CommandServer and model accessors intact.
-keep class io.nekohasekai.libbox.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
# SnakeYAML uses reflection for object construction and YAML tag handling.
-keep class org.yaml.snakeyaml.** { *; }
