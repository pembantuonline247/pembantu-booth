# ProGuard rules
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
