package top.mcfpp.language;

/** A resolved declaration and its portable documentation, before client presentation. */
public record McfppDocumentationData(
        String signature, String markdown, String namespace, String container, String receiver, String detail) {}
