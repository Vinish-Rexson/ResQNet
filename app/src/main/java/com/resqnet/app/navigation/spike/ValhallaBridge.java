package com.resqnet.app.navigation.spike;

import com.valhalla.valhalla.ValhallaKotlin;

/**
 * Clean Java bridge to Valhalla C++ native wrapper.
 * Directly communicates with ValhallaKotlin native methods (createActor, route, deleteActor)
 * bypassing internal Kotlin visibility restrictions.
 */
public class ValhallaBridge {

    private final ValhallaKotlin nativeWrapper;
    private long actorHandle = 0L;

    public ValhallaBridge() {
        this.nativeWrapper = new ValhallaKotlin();
    }

    public synchronized void init(String configPath) {
        if (actorHandle != 0L) {
            close();
        }
        actorHandle = nativeWrapper.createActor(configPath);
        if (actorHandle == 0L) {
            throw new IllegalStateException("Valhalla native createActor failed for config: " + configPath);
        }
    }

    public synchronized boolean isInitialized() {
        return actorHandle != 0L;
    }

    public synchronized String route(String requestJson) {
        if (actorHandle == 0L) {
            throw new IllegalStateException("ValhallaBridge is not initialized. Call init(configPath) first.");
        }
        return nativeWrapper.route(actorHandle, requestJson);
    }

    public synchronized void close() {
        if (actorHandle != 0L) {
            nativeWrapper.deleteActor(actorHandle);
            actorHandle = 0L;
        }
    }
}
