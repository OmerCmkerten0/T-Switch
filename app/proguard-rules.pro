# T-Switch - no proguard rules needed for debug builds
# For release builds, keep SIP and RTP classes:
-keep class com.tnoktasi.tswitch.sip.** { *; }
-keep class com.tnoktasi.tswitch.rtp.** { *; }
-keep class com.tnoktasi.tswitch.gsm.** { *; }
