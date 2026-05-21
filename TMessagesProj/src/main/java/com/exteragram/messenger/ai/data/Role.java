package com.exteragram.messenger.ai.data;

import com.exteragram.messenger.ai.AiConfig;

import java.io.Serializable;
import java.util.Objects;

public class Role implements Comparable<Role>, Serializable {
    private String name;
    private String prompt;
    private boolean isSuggestion;

    public Role(String name, String prompt) {
        this.name = name;
        this.prompt = prompt;
    }

    @Override
    public int compareTo(Role other) {
        return this.name.compareTo(other.getName());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        return this.name.equals(((Role) obj).name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    public String getName() {
        return name;
    }

    public String getPrompt() {
        return prompt;
    }

    public boolean isSuggestion() {
        return isSuggestion;
    }

    public Role setSuggestion(boolean suggestion) {
        this.isSuggestion = suggestion;
        return this;
    }

    public boolean isSelected() {
        return Objects.equals(AiConfig.getSelectedRole(), this.name);
    }
}
