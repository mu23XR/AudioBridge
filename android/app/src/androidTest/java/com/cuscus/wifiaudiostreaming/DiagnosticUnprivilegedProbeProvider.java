package com.cuscus.wifiaudiostreaming;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.IOException;

/** Separate test APK UID; must run without the target APK's Kotlin runtime. */
public final class DiagnosticUnprivilegedProbeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"probe".equals(method) || arg == null) throw new IllegalArgumentException("Expected probe URI");
        Uri uri = Uri.parse(arg);
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null ||
                !uri.getAuthority().startsWith("io.github.mu23xr.audiobridge") || !"/bundle".equals(uri.getPath()))
            throw new IllegalArgumentException("Expected diagnostic bundle");
        long identity = Binder.clearCallingIdentity();
        try {
            boolean denied = false;
            try (ParcelFileDescriptor fd = getContext().getContentResolver().openFileDescriptor(uri, "r")) {
                if (fd == null) throw new IllegalStateException("Null export descriptor");
            } catch (SecurityException expected) {
                denied = true;
            } catch (IOException failure) {
                throw new IllegalStateException("Diagnostic probe setup failed", failure);
            }
            Bundle result = new Bundle();
            result.putBoolean("denied", denied);
            return result;
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
