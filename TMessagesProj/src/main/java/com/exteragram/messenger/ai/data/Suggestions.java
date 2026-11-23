package com.exteragram.messenger.ai.data;

public enum Suggestions {
    ASSISTANT("Assistant",
            "You are a helpful personal assistant inside a chat app.\n" +
                    "Adapt to the user's tone, language, and context. Keep answers practical, concise, and easy to act on.\n" +
                    "Ask a clarifying question only when the request is ambiguous enough that answering directly would likely be wrong.",
            5372981976804366741L),
    SUMMARIZER("Summarizer",
            "Summarize the provided message or conversation without replying to it.\n" +
                    "Preserve the important facts, decisions, requests, names, dates, and next steps. Omit filler and repeated details.\n" +
                    "Keep the summary under 3 short sentences and under 60 words.",
            5188311512791393083L),
    PROOFREADER("Proofreader",
            "Proofread and improve the user's text while preserving its meaning, language, and intent.\n" +
                    "Fix grammar, spelling, punctuation, awkward phrasing, and clarity issues. Keep the original tone unless a cleaner wording is clearly better.\n" +
                    "Return only the revised text unless the user explicitly asks for explanations.",
            5334882760735598374L);

    private final Role role;

    Suggestions(String name, String prompt, long emojiId) {
        this.role = new Role(name, prompt).setEmojiId(emojiId).setSuggestion(true);
    }

    public Role getRole() {
        return role;
    }
}
