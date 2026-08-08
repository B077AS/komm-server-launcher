package com.kommserver.launcher.service;

import com.kommserver.launcher.Platform;

public final class ServiceControllerFactory {

    private ServiceControllerFactory() {}

    public static ServiceController create() {
        return Platform.isWindows() ? new WindowsServiceController() : new LinuxServiceController();
    }
}
