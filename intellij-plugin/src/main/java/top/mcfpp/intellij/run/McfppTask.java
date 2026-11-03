package top.mcfpp.intellij.run;
public enum McfppTask {
    BUILD("Build","build"), INSTALL("Install","install"), UPDATE("Update","update"), CLEAN("Clean","clean"),
    TEST("Test","test"), LINT("Lint","--check-lint"), FORMAT_CHECK("Format Check","--check-format"), DUMP_IR("Dump IR","build");
    public final String title,command;
    McfppTask(String title,String command) { this.title=title; this.command=command; }
    @Override public String toString() { return title; }
}

