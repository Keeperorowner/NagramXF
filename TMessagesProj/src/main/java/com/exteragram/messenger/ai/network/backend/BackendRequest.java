package com.exteragram.messenger.ai.network.backend;

import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;

/** Immutable description of one generation request. */
public final class BackendRequest {
    private final Service service;
    private final Role role;
    private final String prompt;
    private final String imagePath;
    private final boolean useHistory;
    private final boolean stream;
    private final float temperature;

    public BackendRequest(Service service, Role role, String prompt, String imagePath, boolean useHistory, boolean stream, float temperature) {
        this.service = service;
        this.role = role;
        this.prompt = prompt;
        this.imagePath = imagePath;
        this.useHistory = useHistory;
        this.stream = stream;
        this.temperature = temperature;
    }

    public Service getService() {
        return service;
    }

    public Role getRole() {
        return role;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getImagePath() {
        return imagePath;
    }

    public boolean isUseHistory() {
        return useHistory;
    }

    public boolean isStream() {
        return stream;
    }

    public float getTemperature() {
        return temperature;
    }
}
