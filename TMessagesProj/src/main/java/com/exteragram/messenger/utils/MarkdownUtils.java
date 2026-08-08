package com.exteragram.messenger.utils;

import android.text.TextUtils;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public abstract class MarkdownUtils {
    private static final String[] MARKDOWN_TEXT_EXTENSIONS = {"txt", "text"};
    private static final String[] MARKDOWN_MIME_PREFIXES = {"text/plain", "text/x-diff", "text/x-patch", "text/csv", "text/xml", "text/yaml", "text/x-yaml", "text/css", "text/javascript", "application/json", "application/ld+json", "application/json5", "application/xml", "application/yaml", "application/x-yaml", "application/javascript", "application/x-javascript", "application/x-sh"};
    private static final HashMap<String, String> PREFORMATTED_EXTENSION_LANGUAGES = new HashMap<>();
    private static final HashMap<String, String> PREFORMATTED_FILENAMES = new HashMap<>();

    static {
        addLanguage("plain", "log");
        addLanguage("diff", "diff", "patch");
        addLanguage("json", "json", "webmanifest");
        addLanguage("json5", "json5");
        addLanguage("xml", "xml", "rss", "atom");
        addLanguage("svg", "svg");
        addLanguage("html", "html", "htm", "xhtml");
        addLanguage("css", "css");
        addLanguage("scss", "scss");
        addLanguage("sass", "sass");
        addLanguage("less", "less");
        addLanguage("javascript", "js", "mjs", "cjs");
        addLanguage("jsx", "jsx");
        addLanguage("typescript", "ts");
        addLanguage("tsx", "tsx");
        addLanguage("java", "java");
        addLanguage("kotlin", "kt", "kts");
        addLanguage("gradle", "gradle");
        addLanguage("groovy", "groovy");
        addLanguage("python", "py", "pyw", "plugin");
        addLanguage("bash", "sh", "bash", "zsh", "fish", "shell");
        addLanguage("powershell", "ps1", "psm1", "psd1");
        addLanguage("batch", "bat", "cmd");
        addLanguage("sql", "sql");
        addLanguage("yaml", "yaml", "yml");
        addLanguage("ini", "ini", "toml", "properties", "props", "conf", "cfg", "config", "env", "dotenv");
        addLanguage("csv", "csv", "tsv");
        addLanguage("docker", "dockerfile");
        addLanguage("makefile", "make", "mk", "mak");
        addLanguage("cmake", "cmake");
        addLanguage("go", "go");
        addLanguage("rust", "rs");
        addLanguage("swift", "swift");
        addLanguage("dart", "dart");
        addLanguage("php", "php", "phtml");
        addLanguage("ruby", "rb", "gemspec");
        addLanguage("c", "c");
        addLanguage("cpp", "h", "hh", "hpp", "hxx", "cpp", "cc", "cxx");
        addLanguage("csharp", "cs");
        addLanguage("fsharp", "fs", "fsx");
        addLanguage("visual-basic", "vb", "vba");
        addLanguage("lua", "lua");
        addLanguage("perl", "pl", "pm");
        addLanguage("r", "r");
        addLanguage("scala", "scala");
        addLanguage("haskell", "hs");
        addLanguage("elixir", "ex", "exs");
        addLanguage("erlang", "erl", "hrl");
        addLanguage("protobuf", "proto", "protobuf");
        addLanguage("graphql", "graphql", "gql");
        addLanguage("glsl", "glsl", "vert", "frag", "geom", "comp");
        addLanguage("http", "http");
        addFilename("docker", "Dockerfile");
        addFilename("makefile", "Makefile", "GNUmakefile");
        addFilename("cmake", "CMakeLists.txt");
        addFilename("git", ".gitignore", ".gitattributes", ".gitmodules");
        addFilename("docker", ".dockerignore");
        addFilename("ini", ".editorconfig", ".env");
    }

    public static boolean isExteraMarkdown(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        return isExteraMarkdownExtension(messageObject.getExtension()) || isExteraMarkdownMime(messageObject.getMimeType()) || !TextUtils.isEmpty(getPreformattedLanguage(getDocumentFileName(messageObject.getDocument()), messageObject.getExtension(), messageObject.getMimeType()));
    }

    public static boolean isExteraMarkdownExtension(String extension) {
        if (isMarkdownTextExtension(extension)) {
            return true;
        }
        return !TextUtils.isEmpty(getPreformattedLanguage(null, extension, null));
    }

    public static boolean isExteraMarkdownMime(String mimeType) {
        if (TextUtils.isEmpty(mimeType)) {
            return false;
        }
        String lowerCase = mimeType.toLowerCase(Locale.ROOT);
        for (String prefix : MARKDOWN_MIME_PREFIXES) {
            if (lowerCase.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static String getDocumentFileName(TLRPC.Document document) {
        if (document != null) {
            ArrayList<TLRPC.DocumentAttribute> attributes = document.attributes;
            if (attributes != null) {
                int size = attributes.size();
                for (int i = 0; i < size; i++) {
                    TLRPC.DocumentAttribute attribute = attributes.get(i);
                    if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                        return attribute.file_name;
                    }
                }
            }
        }
        return null;
    }

    public static String getPreformattedLanguage(String fileName, String extension, String mimeType) {
        String language = getPreformattedLanguageByFileName(fileName);
        if (!TextUtils.isEmpty(language)) {
            return language;
        }
        String normalizedExtension = normalizeExtension(extension);
        if (TextUtils.isEmpty(normalizedExtension)) {
            normalizedExtension = getExtensionFromFileName(fileName);
        }
        String languageFromExtension = PREFORMATTED_EXTENSION_LANGUAGES.get(normalizedExtension);
        if (!TextUtils.isEmpty(languageFromExtension)) {
            return languageFromExtension;
        }
        if (!TextUtils.isEmpty(mimeType)) {
            String lowerCase = mimeType.toLowerCase(Locale.ROOT);
            if (lowerCase.startsWith("text/x-diff") || lowerCase.startsWith("text/x-patch")) {
                return "diff";
            }
            if (lowerCase.startsWith("text/csv")) {
                return "csv";
            }
            if (lowerCase.startsWith("text/xml") || lowerCase.startsWith("application/xml")) {
                return "xml";
            }
            if (lowerCase.startsWith("application/json5")) {
                return "json5";
            }
            if (lowerCase.startsWith("application/json") || lowerCase.startsWith("application/ld+json")) {
                return "json";
            }
            if (lowerCase.startsWith("text/yaml") || lowerCase.startsWith("text/x-yaml") || lowerCase.startsWith("application/yaml") || lowerCase.startsWith("application/x-yaml")) {
                return "yaml";
            }
            if (lowerCase.startsWith("text/css")) {
                return "css";
            }
            if (lowerCase.startsWith("text/javascript") || lowerCase.startsWith("application/javascript") || lowerCase.startsWith("application/x-javascript")) {
                return "javascript";
            }
            if (lowerCase.startsWith("application/x-sh")) {
                return "bash";
            }
            return "";
        }
        return "";
    }

    public static void appendPreformattedBlocks(List<TL_iv.PageBlock> blocks, String text, String language, int maxBlockLength) {
        int maxLength = Math.max(1, maxBlockLength);
        if (TextUtils.isEmpty(text)) {
            TL_iv.pageBlockPreformatted emptyBlock = new TL_iv.pageBlockPreformatted();
            emptyBlock.text = plain("");
            emptyBlock.language = language == null ? "" : language;
            blocks.add(emptyBlock);
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxLength);
            if (end < text.length()) {
                int lastNewLine = text.lastIndexOf('\n', end - 1);
                if (lastNewLine > start) {
                    end = lastNewLine + 1;
                }
            }
            TL_iv.pageBlockPreformatted block = new TL_iv.pageBlockPreformatted();
            block.text = plain(text.substring(start, end));
            block.language = language == null ? "" : language;
            blocks.add(block);
            start = end;
        }
    }

    private static void addLanguage(String language, String... extensions) {
        for (String extension : extensions) {
            PREFORMATTED_EXTENSION_LANGUAGES.put(extension, language);
        }
    }

    private static void addFilename(String language, String... fileNames) {
        for (String fileName : fileNames) {
            PREFORMATTED_FILENAMES.put(fileName.toLowerCase(Locale.ROOT), language);
        }
    }

    private static boolean isMarkdownTextExtension(String extension) {
        String normalizedExtension = normalizeExtension(extension);
        for (String textExtension : MARKDOWN_TEXT_EXTENSIONS) {
            if (textExtension.equals(normalizedExtension)) {
                return true;
            }
        }
        return false;
    }

    private static String getPreformattedLanguageByFileName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        String lowerCase = getBaseName(fileName).toLowerCase(Locale.ROOT);
        String language = PREFORMATTED_FILENAMES.get(lowerCase);
        if (!TextUtils.isEmpty(language)) {
            return language;
        }
        if (lowerCase.startsWith("dockerfile.")) {
            return "docker";
        }
        if (lowerCase.startsWith("makefile.")) {
            return "makefile";
        }
        return lowerCase.startsWith(".env.") ? "ini" : "";
    }

    private static String getExtensionFromFileName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        String baseName = getBaseName(fileName);
        int lastDot = baseName.lastIndexOf('.');
        return lastDot > 0 && lastDot < baseName.length() - 1 ? normalizeExtension(baseName.substring(lastDot + 1)) : "";
    }

    private static String getBaseName(String fileName) {
        int lastSlash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        return lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;
    }

    private static String normalizeExtension(String extension) {
        if (TextUtils.isEmpty(extension)) {
            return "";
        }
        String trimmed = extension.trim();
        while (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static TL_iv.RichText plain(String text) {
        TL_iv.textPlain textPlain = new TL_iv.textPlain();
        textPlain.text = text == null ? "" : text;
        return textPlain;
    }
}