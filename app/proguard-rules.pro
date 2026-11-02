# Preserve reflection-driven protocol implementations used by SMBJ and SSHJ.
-keep class com.hierynomus.** { *; }
-keep class net.schmizz.** { *; }

# Keep Bouncy Castle providers that may be discovered by security-provider name.
-keep class org.bouncycastle.** { *; }

# Keep runtime generic and annotation metadata used by dependency injection and serialization.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# SMBJ/SSHJ reference optional Java SE authentication APIs that Android does not ship.
# These are only used by optional Kerberos/GSSAPI authentication paths; MalekPlay uses
# the supported password-based SMB/SSH authentication flows.
-dontwarn javax.el.**
-dontwarn javax.security.auth.login.**
-dontwarn org.ietf.jgss.**
