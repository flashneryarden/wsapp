package com.wsapp.taskviewer.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

public final class ConnectivityLiveData extends LiveData<Boolean> {
    private static ConnectivityLiveData instance;

    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback callback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    postValue(isOnline());
                }

                @Override
                public void onLost(@NonNull Network network) {
                    postValue(isOnline());
                }

                @Override
                public void onCapabilitiesChanged(
                        @NonNull Network network,
                        @NonNull NetworkCapabilities capabilities) {
                    postValue(isOnline());
                }
            };

    private ConnectivityLiveData(Context context) {
        connectivityManager =
                (ConnectivityManager) context.getApplicationContext()
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
        setValue(isOnline());
    }

    public static synchronized ConnectivityLiveData get(Context context) {
        if (instance == null) {
            instance = new ConnectivityLiveData(context);
        }
        return instance;
    }

    public boolean isOnline() {
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) return false;
        NetworkCapabilities capabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override
    protected void onActive() {
        super.onActive();
        connectivityManager.registerDefaultNetworkCallback(callback);
        postValue(isOnline());
    }

    @Override
    protected void onInactive() {
        connectivityManager.unregisterNetworkCallback(callback);
        super.onInactive();
    }
}
